package com.openclaw.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.openclaw.android.proot.OpenClawProot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OpenClawInstaller"

/**
 * OpenClawInstaller — Instalación basada en proot + Alpine Linux.
 *
 * Descarga Alpine minirootfs vía OpenClawProot, instala
 * nodejs + npm + openclaw dentro del proot con apk.
 *
 * Flujo:
 *   1. [isAlpineSetupComplete] → verifica Alpine + openclaw instalados
 *   2. [runSetup] → descarga Alpine + instala nodejs/openclaw
 *   3. [isOnboardComplete] → verifica si ya se hizo onboard
 */
object OpenClawInstaller {

    /** Directorio donde vive OPENCLAW_HOME. */
    fun getConfigDir(context: Context): File = File(File(context.filesDir, "home"), ".openclaw")

    /** El setup (Alpine + openclaw) está completo. */
    fun isAlpineSetupComplete(context: Context): Boolean {
        val proot = OpenClawProot(context)
        return proot.isAlpineInstalled() && proot.isOpenClawInstalled()
    }

    /** Sólo Alpine instalado (sin openclaw aún). */
    fun isAlpineInstalledOnly(context: Context): Boolean {
        val proot = OpenClawProot(context)
        return proot.isAlpineInstalled() && !proot.isOpenClawInstalled()
    }

    /** El binario proot está presente en nativeLibraryDir. */
    fun isProotPresent(context: Context): Boolean {
        return OpenClawProot(context).isProotPresent()
    }

    // ── Setup orchestration ──────────────────────────────────────────────────

    /**
     * Flujo completo de instalación dentro de Proot + Alpine Linux:
     *
     *   0. Crear directorios críticos en host (ensureDirectories)
     *   1. Verificar libproot.so presente y ejecutable
     *   2. Verificar conexión a Internet y espacio disponible
     *   3. Descargar + extraer Alpine minirootfs ARM64 (~10 MB) con symlinks
     *   4. Aplicar permisos de ejecución a todos los binarios del rootfs
     *   5. Sanity check: ejecutar /bin/sh dentro de proot
     *   6. Instalar Node.js, npm, pnpm, openclaw@beta (script de 10 pasos)
     *   7. Ejecutar openclaw onboard
     *   8. Marcar instalación como completada en SharedPreferences
     *
     * Reporta progreso vía [onProgress]. Llama [onComplete] al terminar,
     * [onError] si algo falla (con la línea FALLO:PASOx específica).
     */
    suspend fun runSetup(
        context: Context,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val proot = OpenClawProot(context)

        // ── Paso 0: Crear todas las rutas críticas ANTES de ejecutar Proot ──
        // Esto evita errores como:
        //   - "can't chdir('/data/home/.openclaw/.'): No such file or directory"
        //   - "execve('/bin/sh'): Permission denied" (por rootfs incompleto)
        //   - "Function not implemented" al hacer chmod dentro de bind-mount
        onProgress("Preparando directorios del entorno...")
        proot.ensureDirectories()
        onProgress("Directorios listos ✓")

        // ── Verificaciones previas ──────────────────────────────────────────
        // libproot.so debe existir y ser ejecutable
        if (!proot.isProotPresent()) {
            val prootFile = File(proot.proot)
            if (prootFile.exists() && !prootFile.canExecute()) {
                prootFile.setExecutable(true, false)
                if (!prootFile.canExecute()) {
                    onError("libproot.so no es ejecutable — la APK está mal construida")
                    return@withContext
                }
            } else {
                onError("libproot.so no encontrado en ${proot.proot} — la APK está mal construida")
                return@withContext
            }
        }
        if (!isNetworkAvailable(context)) {
            onError("Sin conexión a Internet — se necesita red para descargar Alpine")
            return@withContext
        }
        val freeSpace = context.filesDir.freeSpace
        if (freeSpace < 200 * 1024 * 1024L) {
            val mbLibres = freeSpace / (1024 * 1024)
            onError("Espacio insuficiente: ${mbLibres} MB libres, se necesitan 200 MB")
            return@withContext
        }

        try {
            // ── Paso 1: Alpine ───────────────────────────────────────────────
            if (!proot.isAlpineInstalled()) {
                val ok = proot.downloadAndExtractAlpine(
                    onProgress = { msg -> onProgress("[Alpine] $msg") },
                    onError = { err -> onError("[Alpine] $err") }
                )
                if (!ok) return@withContext
            } else {
                onProgress("[Alpine] Alpine ya instalado ✓")
            }

            // ── Paso 2: Node.js + npm + openclaw + onboard ───────────────────
            if (!proot.isOpenClawInstalled()) {
                val ok = proot.installOpenClaw(
                    onProgress = { msg -> onProgress(msg) },
                    onError = { err -> onError(err) }
                )
                if (!ok) return@withContext

                // Marcar onboard como completado para el frontend React
                markOnboardComplete(context)

                onProgress("Instalación completada ✓")
                onComplete()
            } else {
                onProgress("OpenClaw ya instalado ✓")
                onComplete()
            }
        } catch (e: Exception) {
            onError("Error en setup: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Actualizar openclaw dentro del proot. */
    suspend fun updateOpenClaw(
        context: Context,
        onProgress: (String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val proot = OpenClawProot(context)
        if (!proot.isAlpineInstalled() || !proot.isOpenClawInstalled()) {
            onError("OpenClaw no está instalado. Ejecuta setup primero.")
            return@withContext
        }
        val ok = proot.updateOpenClaw(onProgress = onProgress)
        if (ok) onDone() else onError("Update falló")
    }

    // ── Uninstall ────────────────────────────────────────────────────────────

    /**
     * Elimina Alpine rootfs, openclaw home y resetea preferencias.
     * Mantiene archivos de la app (logs, www, etc.).
     */
    fun uninstall(context: Context) {
        val proot = OpenClawProot(context)
        proot.wipeAlpine()

        // Eliminar config de openclaw
        getConfigDir(context).deleteRecursivelySafe()

        // Limpiar preferencias
        context.getSharedPreferences(
            OpenClawConstants.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().apply()

        Log.i(TAG, "Alpine + OpenClaw eliminados correctamente")
    }

    // ── Preference helpers ────────────────────────────────────────────────────

    /** ¿El onboard (openclaw onboard) ya se completó? */
    fun isOnboardComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            OpenClawConstants.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        return prefs.getBoolean(OpenClawConstants.KEY_ONBOARD_COMPLETE, false) ||
               prefs.getBoolean(OpenClawConstants.KEY_CONFIG_RESTORED, false) ||
               File(getConfigDir(context), "openclaw.json").exists()
    }

    /** Marcar onboard como completado. */
    fun markOnboardComplete(context: Context) {
        context.getSharedPreferences(
            OpenClawConstants.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(OpenClawConstants.KEY_ONBOARD_COMPLETE, true)
            .apply()
    }

    /** ¿Hay configuración previa restaurada? */
    fun isConfigRestored(context: Context): Boolean {
        val prefs = context.getSharedPreferences(
            OpenClawConstants.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        return prefs.getBoolean(OpenClawConstants.KEY_CONFIG_RESTORED, false)
    }

    // ── Connectivity ──────────────────────────────────────────────────────────

    /** ¿El dispositivo tiene conexión a Internet? */
    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(TAG, "isNetworkAvailable falló", e)
            false
        }
    }
}
