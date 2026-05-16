# MIGRACIÓN COMPLETA: proot + Alpine Linux en OpenClaw Android
# Procedimiento + Prompt para IA
# =====================================================================

## ════════════════════════════════════════════════════════════════════
## PARTE 1 — ENTENDIMIENTO ANTES DE EMPEZAR
## ════════════════════════════════════════════════════════════════════

## ¿Qué es proot?
# proot intercepta syscalls con ptrace y redirige rutas del filesystem
# al rootfs de Alpine. NO necesita root. NO es una VM. Rendimiento
# casi nativo (~3-8% overhead). Binario estático de ~500 KB.

## ¿Qué cambia en tu app?
# ANTES: libnode.so (120MB APK) + libldlinux.so + libglibc*.so + payload 97MB
# DESPUÉS: libproot.so (500KB APK) + alpine-rootfs (~10MB descarga) +
#          npm install openclaw (~40MB primera vez, normal)

## Estructura final en filesDir:
# files/
# ├── alpine-rootfs/          ← Alpine Linux completo (~50MB descomprimido)
# │   ├── bin/, sbin/, usr/   ← sistema Alpine normal
# │   ├── etc/resolv.conf     ← DNS configurado por nosotros
# │   └── data/ → bind mount → filesDir (acceso bidireccional)
# ├── home/
# │   └── .openclaw/          ← OPENCLAW_HOME (dentro del rootfs: /data/home/.openclaw)
# └── tmp/                    ← PROOT_TMP_DIR (CRÍTICO — Android no tiene /tmp)

## ════════════════════════════════════════════════════════════════════
## PARTE 2 — BINARIOS NECESARIOS (fuentes verificadas)
## ════════════════════════════════════════════════════════════════════

## proot estático ARM64 — DOS fuentes confiables:
# 1. Termux (recomendada — más actualizada):
#    https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot
#    → renombrar a libproot.so → colocar en jniLibs/arm64-v8a/
#
# 2. SourceForge (backup):
#    https://sourceforge.net/projects/proot.mirror/files/v5.3.0/proot-v5.3.0-aarch64-static/download

## Alpine minirootfs ARM64 (aarch64):
#    https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz
#    SHA256 verificar en: https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz.sha256

## ════════════════════════════════════════════════════════════════════
## PARTE 3 — CAMBIOS EN EL PROYECTO ANDROID
## ════════════════════════════════════════════════════════════════════

## 3.1 — build.gradle (app level)
# ELIMINAR de nativeLibs:
#   libnode.so, libldlinux.so, libglibc*.so (ya no se necesitan)
# AGREGAR:
#   libproot.so en src/main/jniLibs/arm64-v8a/

## 3.2 — AndroidManifest.xml
# AGREGAR permiso de red (para descargar Alpine y paquetes apk):
# <uses-permission android:name="android.permission.INTERNET"/>
# (probablemente ya lo tienes)

## ════════════════════════════════════════════════════════════════════
## PARTE 4 — CÓDIGO KOTLIN COMPLETO
## ════════════════════════════════════════════════════════════════════

## ── OpenClawProot.kt ─────────────────────────────────────────────
# Clase principal que encapsula todo lo relacionado con proot.
# Ubicación: app/src/main/java/com/openclaw/android/proot/OpenClawProot.kt

```kotlin
package com.openclaw.android.proot

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.URL

class OpenClawProot(private val context: Context) {

    companion object {
        const val TAG = "OpenClawProot"

        // URLs de descarga
        const val ALPINE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/" +
            "alpine-minirootfs-3.22.0-aarch64.tar.gz"

        // Variables de entorno CRÍTICAS para proot en Android
        // Sin PROOT_TMP_DIR → proot intenta usar /tmp → no existe en Android → crash
        // Sin PROOT_NO_SECCOMP=1 → falla en algunos kernels Android con seccomp
    }

    // Rutas base
    val filesDir      = context.filesDir
    val nativeDir     = context.applicationInfo.nativeLibraryDir
    val rootfs        = File(filesDir, "alpine-rootfs")      // Alpine aquí
    val prootTmpDir   = File(filesDir, "proot-tmp").also { it.mkdirs() }
    val homeDir       = File(filesDir, "home").also { it.mkdirs() }
    val openclawHome  = File(homeDir, ".openclaw").also { it.mkdirs() }
    val openclawTmp   = File(openclawHome, "tmp").also { it.mkdirs() }
    val proot         = "$nativeDir/libproot.so"  // ELF estático ARM64

    // ── PASO 1: Verificar estado ─────────────────────────────────
    fun isAlpineInstalled(): Boolean =
        File(rootfs, "bin/sh").exists()

    fun isOpenClawInstalled(): Boolean =
        File(rootfs, "usr/local/lib/node_modules/openclaw/openclaw.mjs").exists() ||
        File(rootfs, "usr/lib/node_modules/openclaw/openclaw.mjs").exists()

    fun needsSetup(): Boolean = !isAlpineInstalled()

    // ── PASO 2: Descargar y extraer Alpine ───────────────────────
    fun downloadAndExtractAlpine(
        onProgress: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            onProgress("Descargando Alpine Linux ARM64...")
            rootfs.mkdirs()

            // Descargar a tmp primero
            val tmpFile = File(prootTmpDir, "alpine-rootfs.tar.gz")
            URL(ALPINE_URL).openStream().use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            onProgress("Extrayendo Alpine (~50 MB)...")

            // Extraer con tar — Alpine contiene sus propios bin/, etc/
            // tar NO preserva symlinks sin --no-same-owner en algunos casos
            // usamos ProcessBuilder con /system/bin/tar (nativo de Android)
            val pb = ProcessBuilder(
                "/system/bin/tar",
                "-xzf", tmpFile.absolutePath,
                "-C", rootfs.absolutePath
            ).apply {
                redirectErrorStream(true)
            }
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { onProgress(it) }
            val exit = proc.waitFor()

            tmpFile.delete()  // limpiar

            if (exit != 0) {
                onError("tar falló con código $exit")
                return
            }

            // Configurar DNS dentro del rootfs
            File(rootfs, "etc/resolv.conf").writeText(
                "nameserver 1.1.1.1\nnameserver 8.8.8.8\n"
            )

            onProgress("Alpine instalado correctamente ✓")
        } catch (e: Exception) {
            onError("Error descargando Alpine: ${e.message}")
        }
    }

    // ── PASO 3: Instalar Node.js + npm + openclaw dentro de Alpine ─
    fun installOpenClaw(
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        val script = """
            set -e
            echo "[1/4] Actualizando índice de paquetes..."
            apk update --no-cache

            echo "[2/4] Instalando Node.js y npm..."
            apk add --no-cache nodejs npm ca-certificates

            echo "[3/4] Instalando openclaw globalmente..."
            npm install -g openclaw --prefer-offline

            echo "[4/4] Verificando instalación..."
            node --version
            npm --version
            openclaw --version

            echo "DONE"
        """.trimIndent()

        runInProot(
            command = listOf("/bin/sh", "-c", script),
            onOutput = { line ->
                onProgress(line)
                Log.d(TAG, line)
            },
            onExit = { code ->
                if (code == 0) onDone()
                else onError("Instalación falló con código $code")
            }
        )
    }

    // ── PASO 4: Actualizar openclaw ──────────────────────────────
    fun updateOpenClaw(
        onProgress: (String) -> Unit,
        onDone: () -> Unit
    ) {
        runInProot(
            command = listOf("/bin/sh", "-c", "npm update -g openclaw && openclaw --version"),
            onOutput = onProgress,
            onExit = { if (it == 0) onDone() }
        )
    }

    // ── PASO 5: Ejecutar openclaw onboard ───────────────────────
    fun runOnboard(
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        runInProot(
            command = listOf("/bin/sh", "-c", "openclaw onboard"),
            onOutput = onProgress,
            onExit = { code ->
                if (code == 0) onDone()
                else onError("onboard falló con código $code")
            }
        )
    }

    // ── PASO 6: Ejecutar gateway ─────────────────────────────────
    fun startGateway(onOutput: (String) -> Unit): Process {
        return buildProotProcess(
            command = listOf("/bin/sh", "-c", "openclaw gateway")
        ).start().also { proc ->
            Thread {
                proc.inputStream.bufferedReader().forEachLine { onOutput(it) }
            }.start()
        }
    }

    // ── NÚCLEO: runInProot ───────────────────────────────────────
    // Ejecuta cualquier comando dentro del Alpine proot en background thread
    fun runInProot(
        command: List<String>,
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit
    ) {
        Thread {
            try {
                val proc = buildProotProcess(command).start()
                proc.inputStream.bufferedReader().forEachLine { onOutput(it) }
                onExit(proc.waitFor())
            } catch (e: Exception) {
                Log.e(TAG, "proot error: ${e.message}")
                onExit(-1)
            }
        }.start()
    }

    // ── Construir ProcessBuilder de proot ────────────────────────
    fun buildProotProcess(command: List<String>): ProcessBuilder {
        val dataBindPath = filesDir.absolutePath  // host filesDir → /data en Alpine
        val openclawHomeProot = "/data/home/.openclaw"  // ruta dentro de proot
        val openclawTmpProot  = "/data/home/.openclaw/tmp"

        return ProcessBuilder(
            // ── proot como ejecutor principal ──────────────────
            proot,

            // ── Rootfs Alpine ───────────────────────────────────
            "--rootfs=${rootfs.absolutePath}",

            // ── Bind mounts necesarios ──────────────────────────
            "--bind=/proc",               // info de procesos
            "--bind=/dev",                // dispositivos
            "--bind=/sys",                // kernel info
            "--bind=/dev/urandom",        // entropía (necesaria para TLS/crypto)
            "--bind=$dataBindPath:/data", // acceso a filesDir desde Alpine

            // ── Bind /tmp → prootTmpDir (Android no tiene /tmp) ─
            "--bind=${prootTmpDir.absolutePath}:/tmp",

            // ── Simular root dentro del proot ───────────────────
            "--change-id=0:0",

            // ── Directorio inicial ───────────────────────────────
            "--cwd=/root",

            // ── Comando a ejecutar ───────────────────────────────
            *command.toTypedArray()

        ).apply {
            redirectErrorStream(true)

            environment().apply {
                // CRÍTICOS para proot en Android
                put("PROOT_TMP_DIR",    prootTmpDir.absolutePath)
                put("PROOT_NO_SECCOMP", "1")  // fix para kernels Android con seccomp

                // Entorno Linux normal dentro del proot
                put("HOME",             "/root")
                put("TMPDIR",           "/tmp")  // dentro del proot = prootTmpDir en host
                put("PATH",             "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("TERM",             "xterm-256color")
                put("COLORTERM",        "truecolor")
                put("LANG",             "en_US.UTF-8")

                // OpenClaw dentro del proot
                put("OPENCLAW_HOME",    openclawHomeProot)
                put("TMPDIR",           openclawTmpProot)  // fix EACCES link()
                put("SSL_CERT_FILE",    "/etc/ssl/certs/ca-certificates.crt")

                // npm
                put("npm_config_cache", "/tmp/npm-cache")

                // Nunca
                remove("LD_PRELOAD")
                remove("LD_LIBRARY_PATH")  // Alpine usa sus propias libs, no las de Android
            }
        }
    }

    // ── Terminal PTY — lanzar shell Alpine ───────────────────────
    // Usar con tu terminal emulator (Termux terminal-view.aar)
    fun buildShellCommand(): Array<String> = arrayOf(
        proot,
        "--rootfs=${rootfs.absolutePath}",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=/sys",
        "--bind=/dev/urandom",
        "--bind=${filesDir.absolutePath}:/data",
        "--bind=${prootTmpDir.absolutePath}:/tmp",
        "--change-id=0:0",
        "--cwd=/data/home/.openclaw",
        "/bin/sh"   // Alpine sh (busybox) o /bin/bash si instalas bash
    )

    fun buildShellEnv(): Array<String> = arrayOf(
        "PROOT_TMP_DIR=${prootTmpDir.absolutePath}",
        "PROOT_NO_SECCOMP=1",
        "HOME=/root",
        "TMPDIR=/tmp",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "COLORTERM=truecolor",
        "LANG=en_US.UTF-8",
        "OPENCLAW_HOME=/data/home/.openclaw",
        "PS1=~ \\$ "
    )
}
```

## ── OpenClawInstallerActivity.kt (lógica de flujo) ──────────────
```kotlin
// En tu Activity o ViewModel:

val proot = OpenClawProot(context)

fun runSetup() {
    if (!proot.isAlpineInstalled()) {
        // Mostrar progress dialog
        proot.downloadAndExtractAlpine(
            onProgress = { updateUI(it) },
            onError    = { showError(it) }
        )
        // Cuando termine:
        proot.installOpenClaw(
            onProgress = { updateUI(it) },
            onDone     = { runOnboard() },
            onError    = { showError(it) }
        )
    } else if (!proot.isOpenClawInstalled()) {
        proot.installOpenClaw(
            onProgress = { updateUI(it) },
            onDone     = { runOnboard() },
            onError    = { showError(it) }
        )
    } else {
        startGateway()
    }
}

fun runOnboard() {
    proot.runOnboard(
        onProgress = { updateUI(it) },
        onDone     = { startGateway() },
        onError    = { showError(it) }
    )
}

fun startGateway() {
    gatewayProcess = proot.startGateway { log(it) }
    // WebView apunta a http://localhost:18789
}
```

## ── Terminal PTY — conectar proot al terminal embebido ──────────
```kotlin
// En OpenClawTerminalManager.kt
// Donde antes pasabas libldlinux.so + libnode.so, ahora pasas proot

val prootHelper = OpenClawProot(context)

// El terminal arranca proot con Alpine sh como proceso
val terminalSession = TerminalSession(
    prootHelper.proot,                    // ejecutable principal = libproot.so
    "/data/home/.openclaw",               // directorio inicial dentro del proot
    prootHelper.buildShellCommand(),       // argumentos (rootfs, binds, sh)
    prootHelper.buildShellEnv(),           // environment
    TerminalSession.TERMINAL_SIZE_DEFAULT,
    client
)
```

## ════════════════════════════════════════════════════════════════════
## PARTE 5 — ERRORES CONOCIDOS Y SUS FIXES
## ════════════════════════════════════════════════════════════════════

## ERROR 1: proot error: can't chmod '/tmp/proot-XXXXX'
# CAUSA: Android no tiene /tmp en el filesystem del host
# FIX: PROOT_TMP_DIR apuntando a un dir dentro de filesDir (ya incluido arriba)

## ERROR 2: ptrace(TRACEME): Operation not permitted
# CAUSA: seccomp del kernel Android bloquea ptrace en ciertos contextos
# FIX: PROOT_NO_SECCOMP=1 (ya incluido arriba)

## ERROR 3: /bin/sh: not found (al extraer Alpine)
# CAUSA: tar no preservó symlinks correctamente
# FIX: usar /system/bin/tar de Android (no tar de Alpine que aún no existe)
#      El ProcessBuilder de downloadAndExtractAlpine ya usa /system/bin/tar

## ERROR 4: EACCES: permission denied, link (usage cache)
# CAUSA: TMPDIR en filesystem distinto al destino
# FIX: TMPDIR=/data/home/.openclaw/tmp — mismo filesystem (ya incluido arriba)

## ERROR 5: Telegram channel "plugin path escapes plugin root"
# CAUSA: OPENCLAW_HOME con doble .openclaw
# FIX: Con Alpine el path es limpio: /data/home/.openclaw — sin duplicados

## ERROR 6: npm ENOENT ca-certificates
# CAUSA: Alpine no tiene cert bundle por defecto
# FIX: apk add ca-certificates (ya incluido en el script de installOpenClaw)

## ERROR 7: Phantom Process Killer (Android 12+)
# CAUSA: Android mata procesos background con >32 child processes
# FIX OPCIÓN A: Solicitar al usuario deshabilitar en Developer Options
#               (adb shell device_config put activity_manager
#                max_phantom_processes 2147483647)
# FIX OPCIÓN B: Usar ForegroundService con ONGOING notification para
#               mantener el proceso del gateway vivo

## ════════════════════════════════════════════════════════════════════
## PARTE 6 — GitHub Actions: build de libproot.so para la APK
## ════════════════════════════════════════════════════════════════════

# Si quieres compilar proot tú mismo en vez de usar el precompilado:

```yaml
# .github/workflows/build-proot.yml
name: Build libproot.so ARM64

on:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Enable ARM64 emulation
        run: docker run --rm --privileged multiarch/qemu-user-static --reset -p yes

      - name: Build proot static ARM64
        run: |
          docker run --rm --platform linux/arm64 \
            -v ${{ github.workspace }}:/output \
            alpine:3.22 sh -c "
              apk add --no-cache \
                build-base clang git make \
                talloc-dev talloc-static \
                linux-headers musl-dev

              git clone --depth=1 https://github.com/termux/proot.git /src/proot
              cd /src/proot/src

              # Compilar estático
              LDFLAGS='-static -ltalloc' make loader.elf build.h
              LDFLAGS='-static -ltalloc' make proot

              cp proot /output/libproot.so
              echo 'Built: '
              ls -lh /output/libproot.so
              file /output/libproot.so
            "

      - name: Upload libproot.so
        uses: actions/upload-artifact@v4
        with:
          name: libproot-arm64
          path: libproot.so
```

## ════════════════════════════════════════════════════════════════════
## PARTE 7 — PROMPT COMPLETO PARA IA (copiar y pegar tal cual)
## ════════════════════════════════════════════════════════════════════

---

# PROMPT — Migración OpenClaw Android: glibc propio → proot + Alpine Linux

## CONTEXTO DEL PROYECTO

App Android nativa `com.openclaw.android`, Kotlin, API 31-33 (aarch64).
Actualmente ejecuta Node.js via `libldlinux.so` → `libnode.so` con glibc propio.
Se migra a **proot + Alpine Linux** para ejecutar openclaw en un entorno Linux real.

**Por qué migramos:**
- El enfoque actual tiene bugs de paths, EACCES en link(), y Telegram no carga
- proot + Alpine resuelve todo con un Linux real sin root
- `curl -fsSL https://openclaw.ai/install.sh | bash` funciona dentro de Alpine
- Mantenimiento casi cero — `npm update -g openclaw` para actualizar

**Stack nuevo:**
- `libproot.so` en `nativeLibraryDir` — proot estático ARM64, ~500 KB
  Fuente: https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot
- Alpine Linux 3.22 ARM64 minirootfs — descargado al primer arranque (~10 MB)
  URL: https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.0-aarch64.tar.gz
- Node.js v22 — instalado via `apk add nodejs npm` dentro de Alpine
- openclaw — instalado via `npm install -g openclaw` dentro de Alpine

**Rutas en el host Android:**
- `context.filesDir`                          → `/data/user/0/com.openclaw.android/files`
- `context.filesDir/alpine-rootfs/`           → Alpine Linux completo
- `context.filesDir/home/.openclaw/`          → OPENCLAW_HOME
- `context.filesDir/home/.openclaw/tmp/`      → TMPDIR de openclaw (fix EACCES)
- `context.filesDir/proot-tmp/`               → PROOT_TMP_DIR (Android no tiene /tmp)
- `context.applicationInfo.nativeLibraryDir`  → contiene libproot.so

**Rutas vistas desde DENTRO del proot Alpine:**
- `/`                     → alpine-rootfs/ (todo Alpine aquí)
- `/data/`                → bind mount de filesDir (acceso a archivos de la app)
- `/data/home/.openclaw/` → OPENCLAW_HOME visto desde Alpine
- `/tmp/`                 → bind mount de filesDir/proot-tmp/

**Variables de entorno CRÍTICAS (sin estas proot falla en Android):**
- `PROOT_TMP_DIR` → `filesDir/proot-tmp/`  (Android no tiene /tmp en host)
- `PROOT_NO_SECCOMP=1`                      (fix ptrace en kernels Android)

## TAREA PRINCIPAL

Reescribir `OpenClawGatewayService.kt`, `OpenClawInstaller.kt`, y
`OpenClawTerminalManager.kt` para usar proot en vez del enfoque glibc.
Crear `OpenClawProot.kt` como clase helper central.

### TAREA 1 — OpenClawProot.kt (clase nueva)

Crear en `com.openclaw.android.proot.OpenClawProot`:

**Campos:**
```kotlin
val proot        = "$nativeDir/libproot.so"
val rootfs       = File(filesDir, "alpine-rootfs")
val prootTmpDir  = File(filesDir, "proot-tmp")
val homeDir      = File(filesDir, "home")
val openclawHome = File(homeDir, ".openclaw")
val openclawTmp  = File(openclawHome, "tmp")
```

**Método `buildProotProcess(command: List<String>): ProcessBuilder`:**
El ProcessBuilder debe construirse con este orden exacto de argumentos:
```
proot
--rootfs=<rootfs.absolutePath>
--bind=/proc
--bind=/dev
--bind=/sys
--bind=/dev/urandom
--bind=<filesDir>:/data
--bind=<prootTmpDir>:/tmp
--change-id=0:0
--cwd=/root
<comando...>
```

Environment del ProcessBuilder:
```
PROOT_TMP_DIR   = filesDir/proot-tmp/      ← CRÍTICO
PROOT_NO_SECCOMP= 1                         ← CRÍTICO
HOME            = /root
TMPDIR          = /tmp                      (dentro del proot)
PATH            = /usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
TERM            = xterm-256color
LANG            = en_US.UTF-8
OPENCLAW_HOME   = /data/home/.openclaw     (ruta dentro del proot)
SSL_CERT_FILE   = /etc/ssl/certs/ca-certificates.crt
npm_config_cache= /tmp/npm-cache
# NUNCA: LD_PRELOAD, LD_LIBRARY_PATH
```

**Método `downloadAndExtractAlpine(onProgress, onError)`:**
- Descargar de ALPINE_URL a `prootTmpDir/alpine-rootfs.tar.gz`
- Extraer con `/system/bin/tar -xzf <archivo> -C <rootfs>`  ← usar tar de Android, no Alpine
- Escribir `rootfs/etc/resolv.conf` con `nameserver 1.1.1.1\nnameserver 8.8.8.8`
- Eliminar el .tar.gz descargado

**Método `installOpenClaw(onProgress, onDone, onError)`:**
Ejecutar dentro del proot con `runInProot`:
```sh
set -e
apk update --no-cache
apk add --no-cache nodejs npm ca-certificates
npm install -g openclaw
openclaw --version
```

**Método `buildShellCommand(): Array<String>`:**
Retorna el array completo de argumentos para el terminal PTY:
proot + todos los --bind + --change-id + --cwd=/data/home/.openclaw + /bin/sh

**Método `buildShellEnv(): Array<String>`:**
Retorna el environment para el terminal PTY en formato "KEY=VALUE".

### TAREA 2 — OpenClawInstaller.kt

Reemplazar toda la lógica de extracción de payload glibc con:
1. `proot.downloadAndExtractAlpine()` si `!proot.isAlpineInstalled()`
2. `proot.installOpenClaw()` si `!proot.isOpenClawInstalled()`
3. Crear dirs: `home/.openclaw/`, `home/.openclaw/tmp/`, `proot-tmp/`
4. Eliminar referencias a: libnode.so, libldlinux.so, libglibc, payload-v2, wrappers sh

### TAREA 3 — OpenClawGatewayService.kt

Reemplazar el ProcessBuilder actual (loader + libnode) con:
```kotlin
val proc = proot.buildProotProcess(
    listOf("/bin/sh", "-c", "openclaw gateway")
).start()
```
Eliminar: toda referencia a libldlinux.so, libnode.so, glibc, wrappers sh.

### TAREA 4 — OpenClawTerminalManager.kt

El terminal PTY ahora arranca proot con Alpine sh:
```kotlin
TerminalSession(
    proot.proot,                           // ejecutable
    "/data/home/.openclaw",                // working dir
    proot.buildShellCommand(),             // argumentos
    proot.buildShellEnv(),                 // environment
    ...
)
```

## RESTRICCIONES — NUNCA hacer

- NO usar LD_PRELOAD ni LD_LIBRARY_PATH en ningún environment de proot
- NO omitir PROOT_TMP_DIR — sin él proot falla en Android con error de /tmp
- NO omitir PROOT_NO_SECCOMP=1 — necesario para compatibilidad Android 12+
- NO usar tar de Alpine para extraer el propio Alpine (aún no existe)
- NO hardcodear /data/user/0/ — usar context.filesDir siempre
- NO crear doble .openclaw en la ruta (OPENCLAW_HOME = filesDir/home/.openclaw)
- NO incluir LD_LIBRARY_PATH — Alpine usa sus propias libs musl, no las de Android
- NO mover libproot.so fuera de nativeLibraryDir
- NO duplicar funciones — una función, un lugar

## RESULTADO ESPERADO EN TERMINAL

```
~ $ uname -a
Linux localhost 5.15.x-android aarch64 Linux    ← kernel de Android, rootfs Alpine

~ $ node --version
v22.x.x                                          ← Node de Alpine

~ $ openclaw --version
OpenClaw 2026.5.12                               ← sin errores

~ $ openclaw onboard
[configura telegram, sin "plugin path escapes"]  ← Telegram funciona

# WebUI en localhost:18789
Channels → Telegram ✅
Usage    → sin EACCES ✅
Chat     → assistant responde ✅
```

## ENTREGA

1. `OpenClawProot.kt` — clase completa nueva
2. `OpenClawInstaller.kt` — reescrito para Alpine, sin payload glibc
3. `OpenClawGatewayService.kt` — usando proot.buildProotProcess()
4. `OpenClawTerminalManager.kt` — terminal con buildShellCommand/Env()
5. Confirmación de qué eliminar de jniLibs/ (libnode.so, libldlinux.so, libglibc*.so)

Muestra el código completo de cada archivo — esta es una reescritura, no un patch.
