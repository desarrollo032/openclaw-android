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
 * ANTES: descargaba payload-v2.tar.xz, extraía glibc + libnode.so,
 *        creaba wrappers shell con LD_PRELOAD/LD_LIBRARY_PATH.
 *
 * AHORA: descarga Alpine minirootfs vía OpenClawProot, instala
 *        nodejs + npm + openclaw dentro del proot con apk.
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
     * Flujo completo de instalación:
     *   1. Descargar + extraer Alpine minirootfs (~10 MB)
     *   2. apk add nodejs npm ca-certificates
     *   3. npm install -g openclaw
     *
     * Reporta progreso vía [onProgress]. Llama [onComplete] al terminar,
     * [onError] si algo falla.
     */
    suspend fun runSetup(
        context: Context,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val proot = OpenClawProot(context)

        try {
            // ── Paso 1: Alpine ───────────────────────────────────────────────
            if (!proot.isAlpineInstalled()) {
                val ok = proot.downloadAndExtractAlpine(
                    onProgress = { msg -> onProgress("[Alpine] $msg") },
                    onError = { err -> onError("[Alpine] $err") }
                )
                if (!ok) {
                    onError("No se pudo instalar Alpine Linux")
                    return@withContext
                }
            } else {
                onProgress("[Alpine] Alpine ya instalado ✓")
            }

            // ── Paso 2: Node.js + npm + openclaw ────────────────────────────
            if (!proot.isOpenClawInstalled()) {
                proot.installOpenClaw(
                    onProgress = { msg -> onProgress(msg) },
                    onDone = {
                        onProgress("Instalación completada ✓")
                        persistInstallState(context)
                        onComplete()
                    },
                    onError = { err -> onError(err) }
                )
            } else {
                onProgress("OpenClaw ya instalado ✓")
                persistInstallState(context)
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
        proot.updateOpenClaw(
            onProgress = onProgress,
            onDone = onDone
        )
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

    private fun persistInstallState(context: Context) {
        context.getSharedPreferences(
            OpenClawConstants.PREFS_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(OpenClawConstants.KEY_PAYLOAD_INSTALLED, true)
            .apply()
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
