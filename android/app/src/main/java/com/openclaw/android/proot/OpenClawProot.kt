package com.openclaw.android.proot

import android.content.Context
import android.util.Log as AndroidLog
import com.openclaw.android.OpenClawLogger
import com.openclaw.android.deleteRecursivelySafe
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenClawProot — núcleo de la integración proot + Alpine Linux.
 *
 * Ejecuta un rootfs Alpine ARM64 completo usando el binario `proot` estático,
 * con Node.js instalado via `apk` dentro del contenedor.
 *
 * Layout en disco (host Android):
 *
 *   filesDir/
 *     ├── alpine-rootfs/        Rootfs Alpine completo (~50 MB descomprimido)
 *     │   ├── bin/ etc/ usr/    ← sistema Alpine
 *     │   └── etc/resolv.conf   ← DNS escrito por nosotros
 *     ├── home/.openclaw/       OPENCLAW_HOME (visto como /data/home/.openclaw)
 *     │   └── tmp/              TMPDIR (fix EACCES link())     *     └── cacheDir/proot-tmp/   PROOT_TMP_DIR (Android no tiene /tmp en el host)
     *
     * Importante: PROOT_TMP_DIR usa cacheDir y NO filesDir porque algunos
     * dispositivos Android imponen restricciones de chmod y enlaces simbólicos
     * en filesDir, lo que provoca "Function not implemented" al ejecutar proot.
 *
 *   nativeLibraryDir/libproot.so    ← binario proot (green-green-avk, ~213 KB)
 *   nativeLibraryDir/libproot_loader.so    ← loader desacoplado (PROOT_UNBUNDLE_LOADER=1, ~5.5 KB)
 *
 * Layout visto desde dentro del proot Alpine:
 *
 *   /         → alpine-rootfs/
 *   /data/    → bind mount de filesDir
 *   /tmp/     → bind mount de proot-tmp/
 *
 * Variables CRÍTICAS:
 *   PROOT_TMP_DIR     — sin esto proot falla porque Android no tiene /tmp en host
 *   PROOT_NO_SECCOMP  — fix para kernels Android 12+ con seccomp estricto
 *   PROOT_LOADER      — ruta al loader desacoplado (necesario para Samsung Knox)
 *
 * El binario proot es de green-green-avk/build-proot-android compilado con
 * PROOT_UNBUNDLE_LOADER=1. Usa --link2symlink y -0 para compatibilidad con
 * Samsung Knox / Android 12+. El loader estático se inyecta en cada nuevo
 * proceso creado por proot, evitando depender del linker del sistema.
 *
 * Nunca usar LD_PRELOAD ni LD_LIBRARY_PATH: Alpine usa sus propias libs musl,
 * no las del NDK de Android.
 */
class OpenClawProot(private val context: Context) {

    companion object {
        const val TAG = "OpenClawProot"

        /** Versión de Alpine descargada. Cambiar requiere también verificar el SHA. */
        const val ALPINE_VERSION = "3.22.0"

        /** URL oficial del minirootfs ARM64 (HTTPS). */
        val ALPINE_URL: String =
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" +
            "alpine-minirootfs-$ALPINE_VERSION-aarch64.tar.gz"

        /** Fallback HTTP si HTTPS falla por problemas de certificados SSL en el dispositivo. */
        val ALPINE_HTTP_URL: String = ALPINE_URL.replace("https://", "http://")

        /** Path absoluto del openclaw.mjs dentro del rootfs Alpine (npm global). */
        private val OPENCLAW_PATHS_INSIDE_ALPINE = listOf(
            "usr/local/lib/node_modules/openclaw/openclaw.mjs",
            "usr/lib/node_modules/openclaw/openclaw.mjs"
        )
    }

    // ── Rutas en el host Android ─────────────────────────────────────────────

    val filesDir: File        get() = context.filesDir
    val nativeDir: String     get() = context.applicationInfo.nativeLibraryDir ?: ""
    val rootfs: File          get() = File(filesDir, "alpine-rootfs").apply { mkdirs() }
    val prootTmpDir: File     get() = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
    val homeDir: File         get() = File(filesDir, "home").apply { mkdirs() }
    val openclawHome: File    get() = File(homeDir, ".openclaw").apply { mkdirs() }
    val openclawTmp: File     get() = File(openclawHome, "tmp").apply { mkdirs() }

    /** Path absoluto del binario proot. Vive en nativeLibraryDir como ELF estático. */
    val proot: String         get() = "$nativeDir/libproot.so"

    // ── Estado ───────────────────────────────────────────────────────────────

    /** El rootfs Alpine está extraído y tiene un `bin/sh` ejecutable. */
    fun isAlpineInstalled(): Boolean =
        File(rootfs, "bin/sh").exists() &&
        File(rootfs, "etc").exists() &&
        File(rootfs, "root").exists()

    /** OpenClaw está instalado como módulo global de npm dentro del rootfs. */
    fun isOpenClawInstalled(): Boolean =
        OPENCLAW_PATHS_INSIDE_ALPINE.any { File(rootfs, it).exists() }

    /** El binario proot está presente. Si falta, la APK fue construida mal. */
    fun isProotPresent(): Boolean = File(proot).exists() && File(proot).canExecute()

    /** Hay que correr el flujo completo (descargar Alpine + instalar openclaw). */
    fun needsSetup(): Boolean = !isAlpineInstalled() || !isOpenClawInstalled()

    // ── Descarga y extracción de Alpine ──────────────────────────────────────

    /**
     * Descarga el minirootfs Alpine de dl-cdn.alpinelinux.org y lo extrae en
     * `rootfs`. Usa Apache Commons Compress (Java) en vez de `/system/bin/tar`
     * para ser compatible con todos los dispositivos Android.
     *
     * Devuelve `true` si todo salió bien. Errores y progreso se reportan vía
     * los callbacks; también se replican en [OpenClawLogger].
     */
    fun downloadAndExtractAlpine(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        return try {
            // ── Limpiar rootfs antiguo completamente ────────────────────────
            // Si hay una instalación previa de Alpine (de la versión antigua
            // que no manejaba symlinks), los archivos viejos se mezclarían con
            // la nueva extracción. Eliminamos todo para partir de cero.
            if (rootfs.exists()) {
                onProgress("Limpiando instalación anterior de Alpine...")
                rootfs.deleteRecursivelySafe()
            }
            rootfs.mkdirs()

            val tarFile = File(prootTmpDir, "alpine-rootfs.tar.gz")
            tarFile.parentFile?.mkdirs()
            if (tarFile.exists()) tarFile.delete()

            onProgress("Descargando Alpine Linux ARM64 $ALPINE_VERSION…")

            // ── Descarga con fallback HTTPS → HTTP ────────────────────────────
            // Algunos dispositivos tienen CAs desactualizados o configuraciones
            // de red que bloquean la validación SSL (proxies corporativos,
            // custom ROMs, etc.). Primero intentamos HTTPS; si falla por SSL,
            // reintentamos con HTTP.
            val urlsToTry = listOf(ALPINE_URL, ALPINE_HTTP_URL)
            var downloaded = false

            for (attemptUrl in urlsToTry) {
                if (downloaded) break
                log("Download attempt: $attemptUrl")

                try {
                    val conn = (URL(attemptUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 30_000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "OpenClaw-Android/1.0")
                    }
                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        continue
                    }
                    val total = conn.contentLength.toLong().coerceAtLeast(0L)
                    var copied = 0L
                    var lastPct = -1
                    tarFile.outputStream().use { out ->
                        conn.inputStream.use { input: InputStream ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                copied += n
                                if (total > 0) {
                                    val pct = ((copied * 100) / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress("Descargando Alpine… $pct%")
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    downloaded = true
                    if (attemptUrl != ALPINE_URL) {
                        log("Downloaded via HTTP fallback (HTTPS SSL error on this device)")
                        onProgress("Descarga completada (vía HTTP)")
                    }
                } catch (e: javax.net.ssl.SSLException) {
                    log("HTTPS failed: ${e.message}, trying HTTP fallback...")
                    // Intentar con HTTP en la siguiente iteración
                }
            }

            if (!downloaded) {
                onError("No se pudo descargar Alpine (HTTPS y HTTP fallaron)")
                return false
            }

            if (tarFile.length() == 0L) {
                tarFile.delete()
                onError("Descarga vacía de Alpine")
                return false
            }

            if (!extractAlpineArchive(tarFile, onProgress, onError)) {
                return false
            }
            tarFile.delete()

            // ── Directorios que proot necesita encontrar en el rootfs ────────
            File(rootfs, "root").mkdirs()      // --cwd=/root
            File(rootfs, "tmp").mkdirs()       // /tmp dentro del rootfs
            File(rootfs, ".l2s").mkdirs()      // directorio para --link2symlink

            // DNS dentro del rootfs (Alpine no trae resolv.conf por defecto)
            File(rootfs, "etc").mkdirs()
            val resolv = File(rootfs, "etc/resolv.conf")
            if (!resolv.exists() || resolv.readText().isBlank()) {
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }

            // ── Aplicar +x a todos los binarios (Commons Compress no preserva) ──
            onProgress("Aplicando permisos de ejecución...")
            listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")
                .map { File(rootfs, it) }
                .filter { it.isDirectory }
                .flatMap { it.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile }
                .forEach { it.setExecutable(true, false) }

            // Verificación final
            val sh = File(rootfs, "bin/sh")
            if (!sh.exists() || !sh.canExecute()) {
                onError("bin/sh no ejecutable — extracción fallida")
                return false
            }
            onProgress("Alpine verificado ✓")
            true
        } catch (e: Exception) {
            log("downloadAndExtractAlpine failed: ${e.message}")
            onError("Error descargando Alpine: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    /**
     * Extrae el rootfs preservando enlaces del tar. Alpine depende de symlinks
     * críticos como /bin/sh -> busybox y /lib/ld-musl-aarch64.so.1 -> libc.
     */
    fun extractAlpineArchive(
        tarFile: File,
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        val pendingLinks = mutableListOf<PendingLink>()

        onProgress("Extrayendo Alpine (~50 MB)…")
        return try {
            FileInputStream(tarFile).use { fis ->
                BufferedInputStream(fis, 64 * 1024).use { bis ->
                    GzipCompressorInputStream(bis).use { gzis ->
                        TarArchiveInputStream(gzis).use { tais ->
                            var entry = tais.nextEntry
                            var extractedCount = 0
                            while (entry != null) {
                                val outputFile = safeRootfsFile(entry.name)
                                if (outputFile == null) {
                                    log("Skipping unsafe tar entry: ${entry.name}")
                                    entry = tais.nextEntry
                                    continue
                                }

                                when {
                                    entry.isDirectory -> outputFile.mkdirs()
                                    entry.isSymbolicLink -> {
                                        createArchiveLink(
                                            outputFile = outputFile,
                                            linkName = entry.linkName,
                                            hardLink = false,
                                            pendingLinks = pendingLinks
                                        )
                                    }
                                    entry.isLink -> {
                                        createArchiveLink(
                                            outputFile = outputFile,
                                            linkName = entry.linkName,
                                            hardLink = true,
                                            pendingLinks = pendingLinks
                                        )
                                    }
                                    else -> {
                                        outputFile.parentFile?.mkdirs()
                                        outputFile.outputStream().use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            var n = tais.read(buf, 0, buf.size)
                                            while (n != -1) {
                                                out.write(buf, 0, n)
                                                n = tais.read(buf, 0, buf.size)
                                            }
                                        }
                                        applyTarFilePermissions(outputFile, entry.mode)
                                    }
                                }

                                extractedCount++
                                if (extractedCount % 500 == 0) {
                                    onProgress("Extrayendo Alpine… (archivo $extractedCount)")
                                }
                                entry = tais.nextEntry
                            }
                        }
                    }
                }
            }

            resolvePendingLinks(pendingLinks)
            true
        } catch (e: Exception) {
            log("tar extraction via commons-compress failed: ${e.message}")
            onError("Error extrayendo Alpine: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    // ── Instalación de Node.js + openclaw dentro de Alpine ───────────────────

    /**
     * Dentro del proot, realiza la instalación completa de OpenClaw:
     *   1. Detecta arquitectura ARM64
     *   2. Verifica/instala Node.js y npm (skip si ya existen)
     *   3. Verifica/instala npm (skip si ya existe)
     *   4. Instala dependencias del sistema (python3, make, g++, libc6-compat, libstdc++)
     *   5. Instala pnpm globalmente via npm
     *   6. Configura PNPM_HOME en .bashrc y .profile para persistencia
     *   7. Verifica versiones de node, npm, pnpm
     *   8. Instala openclaw@beta via pnpm
     *   9. Ejecuta openclaw onboard (con stdin vacío para evitar prompts)
     *  10. Verificación final (openclaw --version)
     *
     * Retorna `true` si todo OK, `false` si falla.
     * Si falla, captura la última línea que comienza con "FALLO:" y la usa como
     * mensaje de error en vez del genérico "install falló código 1".
     */
    suspend fun installOpenClaw(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ): Boolean {
        // ── Pre-verificaciones antes de ejecutar proot ────────────────────────
        val prootFile = File(proot)
        if (!prootFile.exists()) {
            onError("libproot.so no encontrado en $proot")
            return false
        }
        if (!prootFile.canExecute()) {
            AndroidLog.w(TAG, "libproot.so no es ejecutable — corrigiendo...")
            prootFile.setExecutable(true, false)
        }

        // Verificar que Alpine tiene apk
        val hasApk = File(rootfs, "sbin/apk").exists() ||
                     File(rootfs, "usr/bin/apk").exists() ||
                     File(rootfs, "bin/apk").exists()
        if (!hasApk) {
            onError("Alpine incompleto — falta binario apk. Reinstalar Alpine.")
            return false
        }

        // Asegurar resolv.conf (DNS) dentro del rootfs
        val resolv = File(rootfs, "etc/resolv.conf")
        if (!resolv.exists() || resolv.readText().isBlank()) {
            resolv.parentFile?.mkdirs()
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 208.67.222.222\nnameserver 9.9.9.9\n")
            AndroidLog.i(TAG, "resolv.conf re-escrito antes de instalar")
        }

        // Asegurar /etc/apk/repositories — el minirootfs de Alpine ship con este
        // archivo apuntando al build mirror local o vacío. Sin repos accesibles,
        // `apk add nodejs npm` falla con "unable to select packages". Lo
        // sobrescribimos con los CDN públicos (main + community) para garantizar
        // que apk pueda resolver paquetes en cualquier dispositivo.
        val apkVersionBranch = "v" + ALPINE_VERSION.substringBeforeLast('.')   // 3.22.0 → v3.22
        val repos = File(rootfs, "etc/apk/repositories")
        repos.parentFile?.mkdirs()
        repos.writeText(
            "https://dl-cdn.alpinelinux.org/alpine/$apkVersionBranch/main\n" +
            "https://dl-cdn.alpinelinux.org/alpine/$apkVersionBranch/community\n"
        )
        AndroidLog.i(TAG, "/etc/apk/repositories escrito (branch=$apkVersionBranch)")

        // Crear /var/cache/apk y /tmp dentro del rootfs (apk los necesita
        // para sus operaciones internas, incluso con --no-cache crea archivos
        // temporales).
        File(rootfs, "var/cache/apk").mkdirs()
        File(rootfs, "tmp").mkdirs()

        // ── Sanity check: verificar que proot puede ejecutar comandos ──
        onProgress("Verificando proot...")
        var sanityOutput = StringBuilder()
        val sanityCode = runInProot(
            command = listOf("/bin/sh", "-c", "echo proot_sanity_ok"),
            onOutput = { line ->
                sanityOutput.appendLine(line)
                AndroidLog.v(TAG, "[sanity] $line")
            }
        )

        if (sanityCode != 0) {
            val prootError = sanityOutput.toString().trim().ifBlank { "(sin output — proot no produjo mensaje de error)" }
            val msg = "proot falló (exit=$sanityCode): $prootError"
            AndroidLog.e(TAG, msg)
            onError(msg)
            return false
        }

        val outputOk = sanityOutput.contains("proot_sanity_ok")
        if (!outputOk) {
            AndroidLog.w(TAG, "sanity check: output no contiene proot_sanity_ok, pero exit=0: ${sanityOutput}")
        }
        AndroidLog.i(TAG, "Sanity check OK — proot puede ejecutar /bin/sh")
        onProgress("Verificación de proot OK ✓")

        // ── Script de instalación completo (12 fases, resumibles) ───────────
        // Cada fase reporta PHASE:<key>:<status>[:<detail>] para que la UI
        // pueda renderizar un checklist en tiempo real. Estados:
        //   start    — fase iniciada
        //   skip     — fase saltada (ya completada en intento anterior)
        //   step     — sub-paso (ej. paquete N de M)
        //   ok       — fase completada
        //   error    — fase falló (script termina)
        //
        // Cada fase escribe un marker file en /root/.openclaw-install/<key>.done
        // para que reintentos posteriores puedan saltarla.
        // El marker se borra con `wipeAlpine`, así que no persiste entre rootfs.
        //
        // No usamos `set -e` porque queremos controlar el flow y reportar
        // fase por fase.
        val script = """
            set +e
            MARKER_DIR=/root/.openclaw-install
            mkdir -p "${'$'}MARKER_DIR"

            phase_start() { echo "PHASE:${'$'}1:start:${'$'}2"; }
            phase_step()  { echo "PHASE:${'$'}1:step:${'$'}2"; }
            phase_ok()    { touch "${'$'}MARKER_DIR/${'$'}1.done"; echo "PHASE:${'$'}1:ok:${'$'}2"; }
            phase_skip()  { echo "PHASE:${'$'}1:skip:${'$'}2"; }
            phase_err()   { echo "PHASE:${'$'}1:error:${'$'}2"; echo "FALLO:${'$'}1 ${'$'}2"; }
            phase_done()  { [ -f "${'$'}MARKER_DIR/${'$'}1.done" ]; }

            # ────────────────────────────────────────────────────────────────
            # Fase 1: arch — detectar arquitectura
            # ────────────────────────────────────────────────────────────────
            if phase_done arch; then
                phase_skip arch "Arquitectura ya verificada"
            else
                phase_start arch "Detectando arquitectura"
                arch=${'$'}(uname -m)
                case "${'$'}arch" in
                    aarch64|arm64)
                        phase_ok arch "ARM64 confirmado (${'$'}arch)" ;;
                    *)
                        phase_ok arch "Arquitectura: ${'$'}arch (no estándar pero continuando)" ;;
                esac
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 2: apk_repos — verificar repositorios
            # ────────────────────────────────────────────────────────────────
            if phase_done apk_repos; then
                phase_skip apk_repos "Repositorios ya configurados"
            else
                phase_start apk_repos "Verificando repositorios apk"
                if [ ! -s /etc/apk/repositories ]; then
                    phase_err apk_repos "/etc/apk/repositories vacío o ausente"
                    exit 1
                fi
                phase_ok apk_repos "Repositorios listos"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 3: apk_update — refresco de índice (con retry)
            # ────────────────────────────────────────────────────────────────
            if phase_done apk_update; then
                phase_skip apk_update "Índice apk ya actualizado"
            else
                phase_start apk_update "Refrescando índice de paquetes"
                apk_log=/tmp/apk-update.log
                rm -f "${'$'}apk_log"
                update_ok=0
                for attempt in 1 2 3; do
                    if apk update > "${'$'}apk_log" 2>&1; then
                        update_ok=1
                        break
                    fi
                    phase_step apk_update "Reintento ${'$'}attempt/3"
                    sleep 2
                done
                if [ "${'$'}update_ok" -ne 1 ]; then
                    tail -20 "${'$'}apk_log"
                    apk_err=${'$'}(tail -1 "${'$'}apk_log" | tr -d '\n' | cut -c1-200)
                    phase_err apk_update "apk update falló: ${'$'}apk_err"
                    exit 1
                fi
                phase_ok apk_update "Índice apk actualizado"
            fi

            # Helper: instalar un paquete apk individual, saltando si ya está
            # presente. Args: 1=phase_key, 2=package_name, [3]=etiqueta
            install_pkg() {
                local key="${'$'}1"
                local pkg="${'$'}2"
                local label="${'$'}{3:-${'$'}2}"
                if apk info -e "${'$'}pkg" >/dev/null 2>&1; then
                    phase_step "${'$'}key" "✓ ${'$'}label (ya instalado)"
                    return 0
                fi
                phase_step "${'$'}key" "↓ Instalando ${'$'}label..."
                local log=/tmp/apk-${'$'}{pkg}.log
                if ! apk add --no-progress "${'$'}pkg" > "${'$'}log" 2>&1; then
                    # Retry una vez sin caché por si fue glitch transitorio
                    if ! apk add --no-progress --no-cache "${'$'}pkg" > "${'$'}log" 2>&1; then
                        tail -10 "${'$'}log"
                        return 1
                    fi
                fi
                phase_step "${'$'}key" "✓ ${'$'}label instalado"
                return 0
            }

            # ────────────────────────────────────────────────────────────────
            # Fase 4: nodejs — instalar Node.js
            # ────────────────────────────────────────────────────────────────
            if phase_done nodejs && command -v node >/dev/null 2>&1; then
                phase_skip nodejs "Node.js ya instalado (${'$'}(node --version 2>/dev/null))"
            else
                phase_start nodejs "Instalando Node.js"
                if ! install_pkg nodejs nodejs "Node.js"; then
                    phase_err nodejs "apk add nodejs falló"
                    exit 1
                fi
                node_ver=${'$'}(node --version 2>/dev/null || echo "?")
                phase_ok nodejs "Node.js ${'$'}node_ver instalado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 5: npm — instalar npm
            # ────────────────────────────────────────────────────────────────
            if phase_done npm && command -v npm >/dev/null 2>&1; then
                phase_skip npm "npm ya instalado (${'$'}(npm --version 2>/dev/null))"
            else
                phase_start npm "Instalando npm"
                if ! install_pkg npm npm "npm"; then
                    phase_err npm "apk add npm falló"
                    exit 1
                fi
                npm_ver=${'$'}(npm --version 2>/dev/null || echo "?")
                phase_ok npm "npm ${'$'}npm_ver instalado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 6: sys_deps — dependencias del sistema (instalación individual)
            # ────────────────────────────────────────────────────────────────
            if phase_done sys_deps; then
                phase_skip sys_deps "Dependencias del sistema ya instaladas"
            else
                phase_start sys_deps "Instalando dependencias del sistema"
                # Cada paquete se instala por separado. Si uno falla, el script
                # continúa con los demás y reporta cuáles fallaron al final.
                dep_failures=""
                for pkg in python3 make g++ curl git libc6-compat libstdc++ ca-certificates bash; do
                    if ! install_pkg sys_deps "${'$'}pkg" "${'$'}pkg"; then
                        dep_failures="${'$'}dep_failures ${'$'}pkg"
                    fi
                done
                if [ -n "${'$'}dep_failures" ]; then
                    phase_err sys_deps "Paquetes fallidos:${'$'}dep_failures"
                    exit 1
                fi
                phase_ok sys_deps "Dependencias del sistema instaladas"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 7: pnpm — instalar pnpm via npm global
            # ────────────────────────────────────────────────────────────────
            if phase_done pnpm && command -v pnpm >/dev/null 2>&1; then
                phase_skip pnpm "pnpm ya instalado (${'$'}(pnpm --version 2>/dev/null))"
            else
                phase_start pnpm "Instalando pnpm vía npm"
                if ! npm install -g pnpm 2>&1; then
                    phase_err pnpm "npm install -g pnpm falló"
                    exit 1
                fi
                pnpm_ver=${'$'}(pnpm --version 2>/dev/null || echo "?")
                phase_ok pnpm "pnpm ${'$'}pnpm_ver instalado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 8: pnpm_env — configurar PNPM_HOME persistente
            # ────────────────────────────────────────────────────────────────
            if phase_done pnpm_env; then
                phase_skip pnpm_env "PNPM_HOME ya configurado"
            else
                phase_start pnpm_env "Configurando PNPM_HOME"
                mkdir -p /root/.local/share/pnpm
                export PNPM_HOME="/root/.local/share/pnpm"
                export PATH="${'$'}PNPM_HOME:${'$'}PATH"
                if ! grep -q "PNPM_HOME" /root/.bashrc 2>/dev/null; then
                    cat >> /root/.bashrc << 'ENVEOF'
export PNPM_HOME="/root/.local/share/pnpm"
export PATH="${'$'}PNPM_HOME:${'$'}PATH"
ENVEOF
                fi
                if ! grep -q "PNPM_HOME" /root/.profile 2>/dev/null; then
                    cat >> /root/.profile << 'ENVEOF'
export PNPM_HOME="/root/.local/share/pnpm"
export PATH="${'$'}PNPM_HOME:${'$'}PATH"
ENVEOF
                fi
                phase_ok pnpm_env "PNPM_HOME configurado en .bashrc y .profile"
            fi

            # Reexportar PNPM_HOME en este shell para fases posteriores
            export PNPM_HOME="/root/.local/share/pnpm"
            export PATH="${'$'}PNPM_HOME:${'$'}PATH"

            # ────────────────────────────────────────────────────────────────
            # Fase 9: versions — verificar versiones
            # ────────────────────────────────────────────────────────────────
            phase_start versions "Verificando versiones instaladas"
            node_v=${'$'}(node --version 2>/dev/null || echo "?")
            npm_v=${'$'}(npm --version 2>/dev/null || echo "?")
            pnpm_v=${'$'}(pnpm --version 2>/dev/null || echo "?")
            phase_step versions "node ${'$'}node_v · npm ${'$'}npm_v · pnpm ${'$'}pnpm_v"
            phase_ok versions "Versiones: node ${'$'}node_v / npm ${'$'}npm_v / pnpm ${'$'}pnpm_v"

            # ────────────────────────────────────────────────────────────────
            # Fase 10: openclaw — instalar OpenClaw vía pnpm
            # ────────────────────────────────────────────────────────────────
            # No usamos marker para esta fase: isOpenClawInstalled() en Kotlin
            # ya detecta el archivo openclaw.mjs, así que esta sub-detección
            # es delegada al wrapper.
            if [ -f /usr/local/lib/node_modules/openclaw/openclaw.mjs ] \
               || [ -f /usr/lib/node_modules/openclaw/openclaw.mjs ]; then
                phase_skip openclaw "OpenClaw ya instalado"
            else
                phase_start openclaw "Instalando OpenClaw (beta) vía pnpm"
                if ! pnpm add -g openclaw@beta 2>&1; then
                    phase_err openclaw "pnpm add -g openclaw@beta falló"
                    exit 1
                fi
                phase_ok openclaw "OpenClaw beta instalado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 11: onboard — ejecutar openclaw onboard
            # ────────────────────────────────────────────────────────────────
            if phase_done onboard; then
                phase_skip onboard "openclaw onboard ya ejecutado"
            else
                phase_start onboard "Configurando OpenClaw (onboard)"
                if ! echo "" | openclaw onboard 2>&1; then
                    phase_err onboard "openclaw onboard falló"
                    exit 1
                fi
                phase_ok onboard "openclaw onboard completado"
            fi

            # ────────────────────────────────────────────────────────────────
            # Fase 12: verify — verificación final
            # ────────────────────────────────────────────────────────────────
            phase_start verify "Verificación final"
            if ! openclaw --version 2>&1; then
                phase_err verify "openclaw --version falló"
                exit 1
            fi
            phase_ok verify "OpenClaw operativo"

            echo "DONE"
        """.trimIndent()

        // Capturar la última fase fallida como mensaje de error específico.
        // El nuevo formato es PHASE:<key>:error:<msg>; mantenemos compat con FALLO:
        var lastErrorLine = "Error sin diagnóstico de paso"

        val code = runInProot(
            command = listOf("/bin/sh", "-c", script),
            onOutput = { line ->
                onProgress(line)
                log(line)
                when {
                    line.startsWith("PHASE:") && line.contains(":error:") -> {
                        // PHASE:<key>:error:<msg> → "<KEY>: <msg>"
                        val parts = line.removePrefix("PHASE:").split(":", limit = 3)
                        if (parts.size == 3) {
                            lastErrorLine = "${parts[0].uppercase()}: ${parts[2]}"
                        } else {
                            lastErrorLine = line
                        }
                    }
                    line.startsWith("FALLO:") -> {
                        lastErrorLine = line
                    }
                }
            }
        )

        return if (code == 0) {
            true
        } else {
            onError("$lastErrorLine [exit=$code]")
            false
        }
    }

    /** `pnpm update -g openclaw@beta` dentro del proot. Retorna true si OK. */
    suspend fun updateOpenClaw(onProgress: (String) -> Unit): Boolean {
        val code = runInProot(
            command = listOf("/bin/sh", "-c", "pnpm update -g openclaw@beta && openclaw --version"),
            onOutput = onProgress
        )
        return code == 0
    }

    /** Ejecuta `openclaw onboard` en el proot. Retorna true si OK. */
    suspend fun runOnboard(
        onProgress: (String) -> Unit
    ): Boolean {
        val code = runInProot(
            command = listOf("/bin/sh", "-c", "openclaw onboard"),
            onOutput = onProgress
        )
        return code == 0
    }

    /**
     * Arranca el gateway de OpenClaw como `Process` síncrono.
     * El llamador es responsable de leer stdout y manejar el ciclo de vida
     */
    fun startGatewayProcess(extraEnv: Map<String, String> = emptyMap()): Process {
        val pb = buildProotProcess(
            listOf("/bin/sh", "-lc", "openclaw gateway")
        )
        if (extraEnv.isNotEmpty()) {
            pb.environment().putAll(extraEnv)
        }
        return pb.start()
    }

    // ── Núcleo: runInProot ───────────────────────────────────────────────────

    /**
     * Ejecuta un comando dentro del Alpine de forma bloqueante (suspend)
     * y retorna el código de salida. Toda la salida stdout/stderr se
     * pasa a [onOutput] línea por línea.
     */
    suspend fun runInProot(
        command: List<String>,
        onOutput: (String) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        try {
            val proc = buildProotProcess(command).start()
            proc.inputStream.bufferedReader().forEachLine { line ->
                AndroidLog.d(TAG, line)
                onOutput(line)
            }
            proc.waitFor()
        } catch (e: Exception) {
            log("proot error: ${e.message}")
            AndroidLog.e(TAG, "proot error: ${e.message}", e)
            onOutput("[error] ${e.message}")
            -1
        }
    }

    /**
     * Construye el ProcessBuilder de proot listo para `.start()`.
     * Usa --link2symlink y -0 para compatibilidad con Samsung Knox / Android 12+,
     * binds canónicos, y configura el environment mínimo necesario para
     * que Node/openclaw funcionen.
     */
    fun buildProotProcess(command: List<String>): ProcessBuilder {
        require(command.isNotEmpty()) { "command must not be empty" }

        // Asegurar que /root exista (Alpine minirootfs no lo incluye)
        File(rootfs, "root").mkdirs()
        File(rootfs, ".l2s").mkdirs()  // requerido por --link2symlink

        val args = mutableListOf<String>().apply {
            add(proot)
            add("--link2symlink")                          // fix symlinks Alpine en filesDir Android
            add("--change-id=0:0")                         // fake root compatible Samsung/Android 12+
            add("--rootfs=${rootfs.absolutePath}")
            add("--bind=/proc")
            add("--bind=/dev")
            add("--bind=/sys")
            add("--bind=/dev/urandom")
            add("--bind=${filesDir.absolutePath}:/data")
            add("--bind=${prootTmpDir.absolutePath}:/tmp")
            add("--cwd=/root")
            addAll(command)
        }

        return ProcessBuilder(args).apply {
            redirectErrorStream(true)
            environment().apply {
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")
                put("PROOT_TMP_DIR",    prootTmpDir.absolutePath)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_LOADER",     "$nativeDir/libproot_loader.so")
                put("HOME",             "/root")
                put("TMPDIR",           "/data/home/.openclaw/tmp")
                put("PNPM_HOME",        "/root/.local/share/pnpm")
                put("PATH",             "/root/.local/share/pnpm:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("TERM",             "xterm-256color")
                put("COLORTERM",        "truecolor")
                put("LANG",             "en_US.UTF-8")
                put("LC_ALL",           "en_US.UTF-8")
                put("OPENCLAW_HOME",    "/data/home/.openclaw")
                put("SSL_CERT_FILE",    "/etc/ssl/certs/ca-certificates.crt")
                put("npm_config_cache", "/tmp/npm-cache")
            }
        }
    }

    // ── Terminal PTY: comando y env para TerminalSession ─────────────────────

    /**
     * Argumentos para construir un `TerminalSession`. El primer elemento debe
     * ser argv[0] (el nombre del proceso) para que TerminalSession lo use
     * correctamente como ejecutable.
     */
    fun buildShellCommand(): Array<String> = arrayOf(
        proot,                                           // argv[0]
        "--link2symlink",
        "--change-id=0:0",
        "--rootfs=${rootfs.absolutePath}",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=/sys",
        "--bind=/dev/urandom",
        "--bind=${filesDir.absolutePath}:/data",
        "--bind=${prootTmpDir.absolutePath}:/tmp",
        "--cwd=/data/home/.openclaw",
        "/bin/sh",
        "-i"
    )

    /** Environment array (formato KEY=VALUE) para el terminal interactivo. */
    fun buildShellEnv(): Array<String> = arrayOf(
        "PROOT_TMP_DIR=${prootTmpDir.absolutePath}",
        "PROOT_NO_SECCOMP=1",
        "PROOT_LOADER=$nativeDir/libproot_loader.so",
        "HOME=/root",
        "TMPDIR=/data/home/.openclaw/tmp",
        "PNPM_HOME=/root/.local/share/pnpm",
        "PATH=/root/.local/share/pnpm:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "LC_ALL=en_US.UTF-8",
        "OPENCLAW_HOME=/data/home/.openclaw",
        "SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
        "PS1=~ \\$ "
    )

    // ── Uninstall ────────────────────────────────────────────────────────────

    /**
     * Detecta qué fases de la instalación están completadas inspeccionando
     * el rootfs y los marker files. Se usa al cargar la UI para reanudar
     * sin tener que re-ejecutar el script entero.
     *
     * Las claves coinciden con las que emite el script de [installOpenClaw].
     */
    fun getCompletedPhases(): Set<String> {
        val phases = mutableSetOf<String>()

        // Alpine extraído → todas las pre-fases base están cumplidas
        if (!isAlpineInstalled()) return phases

        // Marker dir (creado durante installOpenClaw)
        val markerDir = File(rootfs, "root/.openclaw-install")
        if (markerDir.isDirectory) {
            markerDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".done") }
                ?.forEach { phases += it.name.removeSuffix(".done") }
        }

        // Detección de fallback por presencia de binarios — útil si el marker
        // se perdió pero el binario está. Mantiene la UI honesta.
        if (File(rootfs, "usr/bin/node").exists()) phases += "nodejs"
        if (File(rootfs, "usr/bin/npm").exists() || File(rootfs, "usr/bin/npm.cmd").exists()) {
            phases += "npm"
        }
        if (File(rootfs, "usr/lib/node_modules/pnpm").exists() ||
            File(rootfs, "root/.local/share/pnpm/pnpm").exists()) {
            phases += "pnpm"
        }
        if (OPENCLAW_PATHS_INSIDE_ALPINE.any { File(rootfs, it).exists() }) {
            phases += "openclaw"
        }
        return phases
    }

    /** Borra todo el rootfs Alpine y los temporales de proot. */
    fun wipeAlpine() {
        rootfs.deleteRecursivelySafe()
        prootTmpDir.deleteRecursivelySafe()
        prootTmpDir.mkdirs()
    }

    private data class PendingLink(
        val outputFile: File,
        val linkName: String,
        val hardLink: Boolean
    )

    private fun safeRootfsFile(entryName: String): File? {
        val safeName = entryName.replace('\\', '/').removePrefix("/")
        if (safeName.isBlank()) return null

        val root = rootfs.toPath().toAbsolutePath().normalize()
        val output = root.resolve(safeName).normalize()
        return if (output.startsWith(root)) output.toFile() else null
    }

    private fun createArchiveLink(
        outputFile: File,
        linkName: String,
        hardLink: Boolean,
        pendingLinks: MutableList<PendingLink>
    ) {
        outputFile.parentFile?.mkdirs()
        Files.deleteIfExists(outputFile.toPath())

        if (hardLink) {
            val targetFile = resolveArchiveLinkTarget(outputFile, linkName)
            if (targetFile == null || !targetFile.exists()) {
                pendingLinks += PendingLink(outputFile, linkName, hardLink = true)
                return
            }

            try {
                Files.createLink(outputFile.toPath(), targetFile.toPath())
            } catch (e: Exception) {
                if (!copyResolvedLinkTarget(outputFile, linkName)) {
                    pendingLinks += PendingLink(outputFile, linkName, hardLink = true)
                }
            }
            return
        }

        val symlinkTarget = symlinkTargetForHost(outputFile, linkName)
        if (symlinkTarget == null) {
            log("Skipping unsafe symlink ${outputFile.name} -> $linkName")
            return
        }

        try {
            Files.createSymbolicLink(outputFile.toPath(), Paths.get(symlinkTarget))
        } catch (e: Exception) {
            if (!copyResolvedLinkTarget(outputFile, linkName)) {
                pendingLinks += PendingLink(outputFile, linkName, hardLink = false)
            }
        }
    }

    private fun resolvePendingLinks(pendingLinks: List<PendingLink>) {
        pendingLinks.forEach { link ->
            if (link.outputFile.exists()) return@forEach

            val resolved = if (link.hardLink) {
                copyResolvedLinkTarget(link.outputFile, link.linkName)
            } else {
                val symlinkTarget = symlinkTargetForHost(link.outputFile, link.linkName)
                if (symlinkTarget != null) {
                    try {
                        link.outputFile.parentFile?.mkdirs()
                        Files.createSymbolicLink(link.outputFile.toPath(), Paths.get(symlinkTarget))
                        true
                    } catch (e: Exception) {
                        copyResolvedLinkTarget(link.outputFile, link.linkName)
                    }
                } else {
                    false
                }
            }

            if (!resolved) {
                log("Unresolved tar link: ${link.outputFile.absolutePath} -> ${link.linkName}")
            }
        }
    }

    private fun symlinkTargetForHost(outputFile: File, linkName: String): String? {
        val targetFile = resolveArchiveLinkTarget(outputFile, linkName) ?: return null
        val parent = outputFile.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        return parent.relativize(targetFile.toPath().toAbsolutePath().normalize()).toString()
    }

    private fun resolveArchiveLinkTarget(outputFile: File, linkName: String): File? {
        val root = rootfs.toPath().toAbsolutePath().normalize()
        val parent = outputFile.parentFile?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        val target = if (linkName.startsWith("/")) {
            root.resolve(linkName.removePrefix("/"))
        } else {
            parent.resolve(linkName)
        }.normalize()

        return if (target.startsWith(root)) target.toFile() else null
    }

    private fun copyResolvedLinkTarget(outputFile: File, linkName: String): Boolean {
        val targetFile = resolveArchiveLinkTarget(outputFile, linkName) ?: return false
        if (!targetFile.exists()) return false

        outputFile.parentFile?.mkdirs()
        return try {
            if (targetFile.isDirectory) {
                outputFile.mkdirs()
            } else {
                Files.copy(
                    targetFile.toPath(),
                    outputFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
                )
                outputFile.setReadable(true, false)
                if (targetFile.canExecute()) {
                    outputFile.setExecutable(true, false)
                }
            }
            true
        } catch (e: Exception) {
            log("Could not materialize tar link ${outputFile.absolutePath} -> $linkName: ${e.message}")
            false
        }
    }

    private fun applyTarFilePermissions(outputFile: File, mode: Int) {
        val ownerExec  = (mode and 0b001_000_000) != 0
        val groupExec  = (mode and 0b000_001_000) != 0
        val otherExec  = (mode and 0b000_000_001) != 0
        val anyExec = ownerExec || groupExec || otherExec
        if (anyExec) {
            outputFile.setExecutable(true, false)
        }
        outputFile.setReadable(true, false)
    }

    // ── Preparación del entorno antes de ejecutar Proot ───────────────────────

    /**
     * Crea todos los directorios críticos en el host Android ANTES de que
     * Proot se ejecute por primera vez.
     *
     * Rutas creadas:
     *   - cacheDir/proot-tmp/           PROOT_TMP_DIR (bind-mount a /tmp dentro del rootfs)
     *   - filesDir/home/                Directorio home en el host
     *   - filesDir/home/.openclaw/      OPENCLAW_HOME (bind-mount a /data/home/.openclaw)
     *   - filesDir/home/.openclaw/tmp/  OPENCLAW_TMP (TMPDIR para Node/NPM)
     *   - filesDir/alpine-rootfs/       Rootfs Alpine
     *   - filesDir/alpine-rootfs/root/  Directorio de trabajo seguro dentro del rootfs (--cwd=/root)
     *   - filesDir/alpine-rootfs/.l2s/  Directorio para --link2symlink
     *
     * Es seguro llamarlo múltiples veces (mkdirs es idempotente).
     */
    fun ensureDirectories() {
        // Acceder a cada propiedad fuerza la creación del directorio
        prootTmpDir
        homeDir
        openclawHome
        openclawTmp
        rootfs
        File(rootfs, "root").mkdirs()
        File(rootfs, ".l2s").mkdirs()
    }

    // ── Utilidades ───────────────────────────────────────────────────────────


    /**
     * Aplica permisos de ejecución a todos los binarios dentro del rootfs Alpine.
     * Safety net: si el bucle de extracción no pudo aplicar permisos por algún motivo
     * (entry.mode = 0, tar corrupto, etc.), este método garantiza que los binarios
     * críticos tengan +x.
     *
     * También maneja el caso de symlinks rotos o copias de archivos linkados:
     * si /bin/sh apunta a busybox pero el symlink no pudo crearse (Android
     * restringe symlinks en ciertos FS), buscamos busybox como archivo regular
     * y hacemos una copia con permisos.
     */
    private fun applyAlpineExecutablePermissions() {
        val binDirs = listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin")

        for (dirName in binDirs) {
            val dir = File(rootfs, dirName)
            if (!dir.exists()) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && !file.canExecute()) {
                    file.setExecutable(true, false)
                }
            }
        }

        // Binarios críticos que deben ser ejecutables
        val criticals = listOf(
            "bin/sh", "bin/busybox", "sbin/apk",
            "usr/bin/node", "usr/bin/npm", "usr/local/bin/openclaw"
        )
        criticals.forEach { rel ->
            val f = File(rootfs, rel)
            if (f.exists() && !f.canExecute()) {
                // Si es symlink roto (target no existe o no es ejecutable),
                // materializarlo como copia del target real
                if (Files.isSymbolicLink(f.toPath())) {
                    val target = Files.readSymbolicLink(f.toPath())
                    val targetFile = File(f.parentFile, target.toString())
                    if (!targetFile.exists() || !targetFile.canExecute()) {
                        log("Reparando symlink ${f.absolutePath} -> $target (target no ejecutable)")
                        Files.delete(f.toPath())
                        // Buscar el target real en el rootfs y copiarlo
                        val resolvedTarget = resolveArchiveLinkTarget(f, target.toString())
                        if (resolvedTarget != null && resolvedTarget.exists() && resolvedTarget.canExecute()) {
                            resolvedTarget.copyTo(f, overwrite = true)
                            log("Symlink materializado como copia de ${resolvedTarget.absolutePath}")
                        } else {
                            log("No se pudo resolver target $target para symlink ${f.name}")
                        }
                    }
                }
                f.setExecutable(true, false)
            }
        }
    }

    private fun log(msg: String) {
        OpenClawLogger.init(context)
        OpenClawLogger.log(TAG, msg)
    }
}
