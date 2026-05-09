package com.openclaw.android

import android.content.Context
import android.util.Log
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

private const val TAG = "OpenClawTermMgr"

/**
 * OpenClawTerminalManager
 *
 * Responsabilidades:
 *  - Construir el entorno (environment array) idéntico al del ProcessBuilder del gateway.
 *  - Verificar que libbusybox.so exista en nativeLibraryDir antes de crear sesión.
 *  - Validar que libbusybox.so responde a --list y tiene applet "sh".
 *  - Fallback a /system/bin/sh si BusyBox no es válido.
 *  - Crear la TerminalSession con el shell adecuado.
 *  - Gestionar el ciclo de vida de la sesión (start / finish / recreate).
 *
 * REGLAS CRÍTICAS aplicadas:
 *  - NUNCA hardcoded /data/data/ ni /data/user/0/ → se usa context.getDir() / filesDir
 *  - NUNCA setExecutable() sobre libbusybox.so → ya viene instalado en nativeLibraryDir
 *  - LD_PRELOAD siempre removido del entorno
 *  - Working dir: context.getDir("payload", MODE_PRIVATE)
 */
class OpenClawTerminalManager(private val context: Context) {

    // ── Paths ─────────────────────────────────────────────────────────────

    private val nativeDir: File
        get() = File(context.applicationInfo.nativeLibraryDir)

    private val payloadDir: File
        get() = OpenClawInstaller.getPayloadDir(context)

    private val configDir: File
        get() = OpenClawInstaller.getConfigDir(context)

    // ── BusyBox helpers ───────────────────────────────────────────────────

    /** El ELF estático BusyBox instalado por Android en nativeLibraryDir */
    val busyboxFile: File
        get() = File(nativeDir, "libbusybox.so")

    /**
     * Verifica que libbusybox.so existe y es legible.
     * No usa setExecutable() → Android lo instala con permisos correctos.
     */
    fun isBusyboxAvailable(): Boolean {
        val f = busyboxFile
        val ok = f.exists() && f.canRead()
        if (!ok) Log.e(TAG, "libbusybox.so not found at ${f.absolutePath}")
        return ok
    }

    /**
     * Verifica que libbusybox.so es un BusyBox válido con applet "sh".
     * Ejecuta `libbusybox.so --list` y comprueba que la salida contiene "sh".
     *
     * @return true si BusyBox tiene applet "sh" disponible
     */
    fun isBusyboxValid(): Boolean {
        if (!isBusyboxAvailable()) return false
        return try {
            val process = ProcessBuilder(busyboxFile.absolutePath, "--list")
                .redirectErrorStream(true)
                .start()
            val result = process.inputStream.bufferedReader().readText()
            process.waitFor()
            val hasShApplet = result.lines().any { it.trim() == "sh" }
            Log.d(TAG, "BusyBox --list: hasShApplet=$hasShApplet (${result.lines().size} applets)")
            hasShApplet
        } catch (e: Exception) {
            Log.e(TAG, "BusyBox validation failed: ${e.message}")
            false
        }
    }

    /**
     * Determina el path del shell a usar:
     * - Si BusyBox es válido (tiene applet "sh"): usa libbusybox.so
     * - Si no: fallback a /system/bin/sh
     */
    fun getShellPath(): String {
        return if (isBusyboxValid()) {
            busyboxFile.absolutePath
        } else {
            Log.w(TAG, "BusyBox invalid or unavailable → falling back to /system/bin/sh")
            "/system/bin/sh"
        }
    }

    // ── Environment ───────────────────────────────────────────────────────

    /**
     * Construye el array de variables de entorno para la sesión de terminal.
     * Debe ser idéntico al del ProcessBuilder en OpenClawGatewayService,
     * con TERM=xterm-256color y sin LD_PRELOAD.
     */
    fun buildEnvironment(): Array<String> {
        val native    = nativeDir.absolutePath
        val payload   = payloadDir.absolutePath
        val glibcLib  = "$payload/glibc/lib"
        val tmpDir    = context.cacheDir.absolutePath
        val ocHome    = File(context.filesDir, ".openclaw").absolutePath

        // PATH: payload/bin primero, luego nativeDir (donde están los .so ejecutables),
        // luego /system/bin como fallback mínimo de Android.
        val path = "$payload/bin:$native:/system/bin"

        // LD_LIBRARY_PATH: nativeDir (libnode, libldlinux, libbusybox) y glibc/lib
        val ldLibPath = "$native:$glibcLib"

        return arrayOf(
            "TERM=xterm-256color",
            "HOME=$payload",
            "PATH=$path",
            "LD_LIBRARY_PATH=$ldLibPath",
            "TMPDIR=$tmpDir",
            "OPENCLAW_HOME=$ocHome",
            "NODE_PATH=$payload/lib/node_modules",
            "SSL_CERT_FILE=$payload/etc/tls/cert.pem",
            "LANG=en_US.UTF-8",
            "COLORTERM=truecolor",
            // Compatibilidad con entorno del gateway
            "OA_GLIBC=1",
            "CONTAINER=1"
            // LD_PRELOAD: deliberadamente OMITIDO (eliminado del entorno)
        )
    }

    // ── Session factory ───────────────────────────────────────────────────

    /**
     * Crea una TerminalSession con el shell apropiado en modo interactivo.
     *
     * Lógica de selección del shell:
     *   1. Si libbusybox.so existe y tiene applet "sh":
     *      → argv = [libbusybox.so, "sh", "-i"]
     *      (BusyBox usa argv[1] como nombre del applet a ejecutar)
     *
     *   2. Si BusyBox no está disponible o no tiene applet "sh":
     *      → /system/bin/sh -i
     *      (fallback a shell del sistema Android)
     *
     * Working dir: context.getDir("payload", MODE_PRIVATE)
     * Si el payloadDir no existe todavía, se usa cacheDir como fallback seguro.
     *
     * @param client Callback de TerminalSessionClient (implementado por la Activity)
     * @return TerminalSession listo para adjuntar a TerminalView, o null si falla.
     */
    fun createSession(client: TerminalSessionClient): TerminalSession? {
        val shell = getShellPath()
        val workingDir = if (payloadDir.exists()) payloadDir.absolutePath
                         else context.cacheDir.absolutePath
        val env = buildEnvironment()

        // Construir argv correctamente según el shell elegido
        val args: Array<String> = if (shell == busyboxFile.absolutePath) {
            // BusyBox: argv[0]=ejecutable, argv[1]="sh" (nombre del applet),
            // argv[2]="-i" (modo interactivo)
            arrayOf(shell, "sh", "-i")
        } else {
            // /system/bin/sh: argv estándar
            arrayOf("sh", "-i")
        }

        val isBusybox = shell == busyboxFile.absolutePath
        Log.d(TAG, "Creating terminal session:")
        Log.d(TAG, "  shell      = $shell (busybox valid: $isBusybox)")
        Log.d(TAG, "  workingDir = $workingDir")
        Log.d(TAG, "  argv       = ${args.joinToString(" ")}")
        Log.d(TAG, "  env[PATH]  = ${env.firstOrNull { it.startsWith("PATH=") }}")

        return try {
            TerminalSession(
                shell,           // ejecutable
                workingDir,      // working directory
                args,            // argv
                env,             // environment
                4000,            // history size
                client           // callbacks
            )
        } catch (e: Exception) {
            Log.e(TAG, "TerminalSession creation failed", e)
            null
        }
    }

    // ── BusyBox symlinks ──────────────────────────────────────────────────

    /**
     * Lista de applets de BusyBox que se exponen como symlinks en payloadDir/bin/.
     * Los symlinks apuntan a libbusybox.so en nativeLibraryDir.
     *
     * Nota: Los symlinks se crean en un directorio escribible (payloadDir/bin),
     * NO en nativeLibraryDir (que es read-only en Android 12+).
     */
    val busyboxApplets = listOf(
        "sh", "ls", "cat", "grep", "find", "tar",
        "chmod", "mkdir", "cp", "mv", "rm", "echo",
        "env", "which", "pwd", "touch", "ln", "head",
        "tail", "wc", "sort", "uniq", "cut", "sed",
        "awk", "xargs", "printf", "test", "true", "false"
    )

    /**
     * Crea symlinks de BusyBox en payloadDir/bin/ para los applets definidos en [busyboxApplets].
     *
     * Cada symlink apunta a la ruta absoluta de libbusybox.so en nativeLibraryDir.
     * Esto permite llamar a `sh`, `ls`, etc. desde el PATH sin resolver applets manualmente.
     *
     * @return Número de symlinks creados exitosamente.
     */
    fun createBusyboxSymlinks(): Int {
        val binDir = File(payloadDir, "bin")
        binDir.mkdirs()

        val target = busyboxFile
        if (!target.exists()) {
            Log.e(TAG, "createBusyboxSymlinks: libbusybox.so not found, skipping")
            return 0
        }

        var created = 0
        for (applet in busyboxApplets) {
            val link = File(binDir, applet)
            try {
                if (link.exists() || link.isFile) {
                    // Omitir si ya existe y apunta al objetivo correcto
                    val canonical = link.canonicalPath
                    if (canonical == target.canonicalPath) {
                        created++
                        continue
                    }
                    link.delete()
                }
                // Usar Os.symlink para crear el symlink sin setExecutable()
                android.system.Os.symlink(target.absolutePath, link.absolutePath)
                Log.d(TAG, "Symlink: $applet → ${target.absolutePath}")
                created++
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create symlink for $applet: ${e.message}")
            }
        }

        Log.i(TAG, "BusyBox symlinks: $created/${busyboxApplets.size} created in ${binDir.absolutePath}")
        return created
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    /**
     * Imprime en logcat el estado del entorno del terminal.
     * Útil para depuración durante el desarrollo.
     */
    fun logDiagnostics() {
        Log.d(TAG, "=== OpenClawTerminalManager Diagnostics ===")
        Log.d(TAG, "nativeLibraryDir : ${nativeDir.absolutePath}")
        Log.d(TAG, "payloadDir       : ${payloadDir.absolutePath} (exists=${payloadDir.exists()})")
        Log.d(TAG, "configDir        : ${configDir.absolutePath} (exists=${configDir.exists()})")
        Log.d(TAG, "libbusybox.so    : ${busyboxFile.absolutePath} (exists=${busyboxFile.exists()}, readable=${busyboxFile.canRead()})")
        Log.d(TAG, "busybox valid    : ${isBusyboxValid()}")
        Log.d(TAG, "shell path       : ${getShellPath()}")
        Log.d(TAG, "Environment:")
        buildEnvironment().forEach { Log.d(TAG, "  $it") }
        Log.d(TAG, "==========================================")
    }
}
