package com.openclaw.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import java.io.File

/**
 * TerminalManager — Orquesta la ejecución de scripts de instalación dentro de
 * una sesión de terminal existente, con el entorno correcto para que install.sh
 * funcione sin errores de rutas o de lock files.
 *
 * ## Por qué falla install.sh sin este manager
 *
 * El error "E: Could not open lock file /var/lib/dpkg/lock-frontend" ocurre porque:
 *
 * 1. apt/dpkg busca sus lock files en rutas ABSOLUTAS hardcodeadas para Termux:
 *    `/data/data/com.termux/files/usr/var/lib/dpkg/lock-frontend`
 *
 * 2. Cuando la app corre con ID `com.openclaw.android.debug`, el PREFIX real es:
 *    `/data/data/com.openclaw.android.debug/files/usr`
 *
 * 3. Sin las variables de entorno correctas (HOME, PREFIX, PATH), bash no sabe
 *    dónde están los binarios ni los directorios de trabajo, y apt falla al
 *    intentar abrir lock files en rutas que no existen o no tienen permisos.
 *
 * ## Solución
 *
 * Este manager inyecta las variables de entorno correctas ANTES de ejecutar
 * cualquier script, alineando HOME, PREFIX y PATH con la ruta real del sandbox
 * de la app. Esto hace que apt/dpkg encuentren sus directorios y lock files
 * en la ubicación correcta.
 *
 * ## Uso
 *
 * ```kotlin
 * val manager = TerminalManager(context, filesDir)
 *
 * // Ejecutar install.sh en una sesión existente
 * manager.runInstallScript(session, "files/install")
 *
 * // O ejecutar cualquier comando con el entorno correcto
 * manager.runCommandInSession(session, "pkg update && pkg install git")
 * ```
 */
class TerminalManager(
    private val context: Context,
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "TerminalManager"

        /**
         * Delay en ms antes de escribir en la sesión.
         * El proceso bash necesita inicializarse antes de recibir input.
         * 500ms es suficiente para la mayoría de dispositivos; aumentar a 800ms
         * si el dispositivo es lento y los comandos se pierden.
         */
        private const val SHELL_INIT_DELAY_MS = 500L

        /**
         * Delay adicional entre el bloque de exports y el comando principal.
         * Evita que el comando se ejecute antes de que el entorno esté listo.
         */
        private const val ENV_APPLY_DELAY_MS = 200L
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Construye el bloque de variables de entorno para inyectar en la sesión.
     *
     * CORRECCIÓN CRÍTICA: Estas variables resuelven el error de install.sh.
     *
     * - HOME: Directorio home del usuario dentro del sandbox de la app.
     *   Sin esto, bash usa /root o un directorio inaccesible.
     *
     * - PREFIX: Ruta base del entorno Termux dentro del sandbox.
     *   apt/dpkg derivan TODAS sus rutas de esta variable.
     *   Con el PREFIX correcto, los lock files se crean en:
     *   `$PREFIX/var/lib/dpkg/lock-frontend` (accesible por la app)
     *   en lugar de `/data/data/com.termux/...` (inaccesible).
     *
     * - PATH: Orden de búsqueda de binarios. $PREFIX/bin debe ir primero
     *   para que bash, apt, pkg, etc. se resuelvan desde el sandbox.
     *
     * - TMPDIR: Directorio temporal. Sin esto, algunos scripts fallan al
     *   intentar crear archivos temporales en /tmp (no existe en Android).
     *
     * - TERMUX__PREFIX / TERMUX_PREFIX: Algunos binarios del bootstrap de
     *   Termux leen estas variables para encontrar sus archivos de configuración.
     *   Apuntarlas a nuestro PREFIX evita el error "bash.bashrc: Permission denied".
     */
    fun buildEnvBlock(): String {
        // Normalizar la ruta: /data/user/0/<pkg>/files → /data/data/<pkg>/files
        // Los binarios del bootstrap tienen /data/data/... hardcodeado en sus ELF strings.
        val normalizedFilesDir = normalizeFilesDir(filesDir)

        val prefix = normalizedFilesDir.resolve("usr").absolutePath
        val home = normalizedFilesDir.resolve("home").absolutePath
        val tmpDir = normalizedFilesDir.resolve("tmp").absolutePath
        val ocaBin = "$home/.openclaw-android/bin"
        val nodeDir = "$home/.openclaw-android/node"
        val glibcLib = "$prefix/glibc/lib"
        val certBundle = "$prefix/etc/tls/cert.pem"

        // Asegurar que los directorios existen antes de exportarlos
        File(home).mkdirs()
        File(tmpDir).mkdirs()

        return buildString {
            appendLine("# --- OpenClaw Environment Setup ---")
            appendLine("export HOME=\"$home\"")
            appendLine("export PREFIX=\"$prefix\"")
            appendLine("export TMPDIR=\"$tmpDir\"")
            appendLine("export TERMUX__PREFIX=\"$prefix\"")
            appendLine("export TERMUX_PREFIX=\"$prefix\"")
            appendLine("export TERMUX__ROOTFS=\"${File(prefix).parent}\"")
            appendLine("export PATH=\"$ocaBin:$nodeDir/bin:$prefix/bin:$prefix/bin/applets:/system/bin:/bin\"")
            appendLine("export LD_LIBRARY_PATH=\"$glibcLib:$prefix/lib\"")
            appendLine("export SSL_CERT_FILE=\"$certBundle\"")
            appendLine("export CURL_CA_BUNDLE=\"$certBundle\"")
            appendLine("export GIT_SSL_CAINFO=\"$certBundle\"")
            appendLine("export RESOLV_CONF=\"$prefix/etc/resolv.conf\"")
            appendLine("export GIT_CONFIG_NOSYSTEM=1")
            appendLine("export LANG=en_US.UTF-8")
            appendLine("export TERM=xterm-256color")
            appendLine("export ANDROID_DATA=/data")
            appendLine("export ANDROID_ROOT=/system")
            appendLine("export OA_GLIBC=1")
            appendLine("export CONTAINER=1")
            appendLine("export BASH_ENV=/dev/null")
            appendLine("export ENV=/dev/null")
            appendLine("unset LD_PRELOAD")
            appendLine("# --- End Environment Setup ---")
        }
    }

    /**
     * Ejecuta el script install.sh dentro de una sesión de terminal existente.
     *
     * El script se busca en `filesDir/<installSubdir>/install.sh`.
     * Antes de ejecutarlo, se inyectan todas las variables de entorno necesarias
     * para que apt/dpkg funcionen correctamente.
     *
     * @param session La sesión de terminal donde se ejecutará el script.
     * @param installSubdir Subdirectorio relativo a filesDir donde está install.sh.
     *                      Por defecto "install" → filesDir/install/install.sh
     * @param onReady Callback opcional que se invoca cuando el comando ha sido enviado.
     */
    fun runInstallScript(
        session: TerminalSession,
        installSubdir: String = "install",
        onReady: (() -> Unit)? = null,
    ) {
        val installDir = filesDir.resolve(installSubdir)
        val installScript = installDir.resolve("install.sh")

        if (!installScript.exists()) {
            AppLogger.e(TAG, "install.sh not found at: ${installScript.absolutePath}")
            session.write("echo '[ERROR] install.sh no encontrado en: ${installScript.absolutePath}'\n")
            return
        }

        // Asegurar que el script tiene permisos de ejecución
        installScript.setExecutable(true)

        AppLogger.i(TAG, "Running install.sh from: ${installScript.absolutePath}")

        // Paso 1: Inyectar variables de entorno (con delay para que bash esté listo)
        handler.postDelayed({
            val envBlock = buildEnvBlock()
            session.write(envBlock)

            // Paso 2: Ejecutar install.sh (con delay adicional para que los exports se apliquen)
            handler.postDelayed({
                session.write("cd \"${installDir.absolutePath}\" && bash install.sh\n")
                onReady?.invoke()
                AppLogger.i(TAG, "install.sh command sent to terminal session")
            }, ENV_APPLY_DELAY_MS)
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Ejecuta un comando arbitrario en la sesión con el entorno correcto.
     *
     * Útil para comandos de diagnóstico o scripts adicionales que necesiten
     * el mismo entorno que install.sh.
     *
     * @param session La sesión de terminal.
     * @param command El comando a ejecutar (se añade \n automáticamente).
     * @param injectEnv Si true (por defecto), inyecta las variables de entorno antes del comando.
     */
    fun runCommandInSession(
        session: TerminalSession,
        command: String,
        injectEnv: Boolean = true,
    ) {
        handler.postDelayed({
            if (injectEnv) {
                session.write(buildEnvBlock())
                handler.postDelayed({
                    session.write("$command\n")
                }, ENV_APPLY_DELAY_MS)
            } else {
                session.write("$command\n")
            }
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Verifica si el entorno está correctamente configurado ejecutando un
     * comando de diagnóstico en la sesión.
     *
     * Escribe en el terminal la información de diagnóstico para que el usuario
     * pueda ver si las rutas son correctas.
     */
    fun runDiagnostics(session: TerminalSession) {
        handler.postDelayed({
            session.write(buildEnvBlock())
            handler.postDelayed({
                session.write(
                    """
                    echo "=== OpenClaw Environment Diagnostics ==="
                    echo "HOME: ${'$'}HOME"
                    echo "PREFIX: ${'$'}PREFIX"
                    echo "PATH: ${'$'}PATH"
                    echo "TMPDIR: ${'$'}TMPDIR"
                    echo ""
                    echo "=== Directory Check ==="
                    ls "${'$'}PREFIX/bin/bash" 2>/dev/null && echo "bash: OK" || echo "bash: NOT FOUND"
                    ls "${'$'}PREFIX/bin/apt" 2>/dev/null && echo "apt: OK" || echo "apt: NOT FOUND"
                    ls "${'$'}PREFIX/var/lib/dpkg" 2>/dev/null && echo "dpkg dir: OK" || echo "dpkg dir: NOT FOUND"
                    echo ""
                    echo "=== Lock Files ==="
                    ls -la "${'$'}PREFIX/var/lib/dpkg/lock-frontend" 2>/dev/null || echo "lock-frontend: not created yet (OK)"
                    echo "=== End Diagnostics ==="
                    
                    """.trimIndent()
                )
            }, ENV_APPLY_DELAY_MS)
        }, SHELL_INIT_DELAY_MS)
    }

    /**
     * Normaliza /data/user/0/<pkg>/files → /data/data/<pkg>/files.
     *
     * Android 7+ con soporte multi-usuario expone context.filesDir como
     * /data/user/0/<pkg>/files (un alias bind-mount). Los binarios del bootstrap
     * de Termux tienen /data/data/... hardcodeado en sus strings ELF y en
     * llamadas open()/opendir() que NO son interceptadas por libtermux-exec.so.
     * Usar la ruta canónica evita errores "No such file or directory" en dpkg,
     * bash y apt cuando intentan abrir sus directorios de configuración.
     */
    private fun normalizeFilesDir(dir: File): File {
        val path = dir.absolutePath
        val normalized = path.replace(Regex("^/data/user/\\d+/"), "/data/data/")
        return if (normalized != path) File(normalized) else dir
    }
}
