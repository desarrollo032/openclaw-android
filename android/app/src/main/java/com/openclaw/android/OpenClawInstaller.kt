package com.openclaw.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.openclaw.android.proot.OpenClawProot
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "OpenClawInstaller"

/**
 * OpenClawInstaller — modelo proot + Alpine Linux.
 *
 * Reemplaza el antiguo payload glibc (libnode.so + libldlinux.so + payload-v2.tar.xz)
 * por un flujo en dos pasos:
 *   1. Descargar Alpine minirootfs ARM64 desde dl-cdn.alpinelinux.org
 *   2. Dentro del proot: `apk add nodejs npm` + `npm install -g openclaw`
 *
 * La API pública se conserva (isPayloadReady, getPayloadDir, etc.) para que
 * AndroidBridge y OpenClawDashboardActivity sigan compilando, pero la
 * semántica interna apunta al rootfs Alpine.
 *
 * Mapeo de paths:
 *   getPayloadDir(ctx)  →  filesDir/alpine-rootfs/usr
 *      ⇒ "$payloadDir/lib/node_modules/openclaw"  vale para Alpine
 *        porque `npm install -g` deposita en /usr/lib/node_modules
 *   getConfigDir(ctx)   →  filesDir/home/.openclaw
 */
object OpenClawInstaller {

    // ── Paths públicos ────────────────────────────────────────────────────────

    /**
     * Compatibilidad: antes era un dir gestionado por nosotros con `lib/node_modules`.
     * Ahora apunta a `/usr` del rootfs Alpine, donde npm pone los globales.
     * Garantía: `getPayloadDir(ctx)/lib/node_modules/openclaw` existe cuando
     * `isPayloadReady` devuelve true.
     */
    fun getPayloadDir(context: Context): File =
        File(File(context.filesDir, "alpine-rootfs"), "usr")

    fun getConfigDir(context: Context): File =
        File(File(context.filesDir, "home"), ".openclaw")

    // ── Estado ───────────────────────────────────────────────────────────────

    /**
     * Devuelve true si tanto Alpine como `openclaw` están instalados y el
     * binario `proot` está accesible.
     */
    fun isPayloadReady(context: Context): Boolean {
        val proot = OpenClawProot(context)
        if (!proot.isProotPresent()) return false
        if (!proot.isAlpineInstalled()) return false
        if (!proot.isOpenClawInstalled()) return false
        // Verificación adicional: node debe estar instalado (apk add nodejs)
        val nodeBin = File(proot.rootfs, "usr/bin/node")
        if (!nodeBin.exists()) return false
        return true
    }

    fun isAlpineInstalled(context: Context): Boolean =
        OpenClawProot(context).isAlpineInstalled()

    fun isOpenClawInstalled(context: Context): Boolean =
        OpenClawProot(context).isOpenClawInstalled()

    /** En el nuevo modelo no hay payload bundle en assets/. Siempre false. */
    fun hasBundledAssets(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    fun isConfigRestored(context: Context): Boolean {
        val prefs = context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(OpenClawConstants.KEY_ONBOARD_COMPLETE, false)) return true
        if (prefs.getBoolean(OpenClawConstants.KEY_CONFIG_RESTORED, false)) return true
        val jsonExists = File(getConfigDir(context), "openclaw.json").exists()
        if (jsonExists) {
            prefs.edit().putBoolean(OpenClawConstants.KEY_CONFIG_RESTORED, true).apply()
            return true
        }
        return false
    }

    /** Compatibilidad: con proot+Alpine no hay tarball local a verificar. */
    fun verifyPayloadIntegrity(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    // ── Onboarding flag ──────────────────────────────────────────────────────

    fun isOnboardComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(OpenClawConstants.KEY_ONBOARD_COMPLETE, false)
    }

    fun markOnboardComplete(context: Context) {
        context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OpenClawConstants.KEY_ONBOARD_COMPLETE, true)
            .apply()
    }

    // ── Uninstall ────────────────────────────────────────────────────────────

    fun uninstall(context: Context) {
        OpenClawProot(context).wipeAlpine()
        getConfigDir(context).deleteRecursivelySafe()
        context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        Log.i(TAG, "Environment uninstalled (Alpine rootfs + config wiped)")
    }

    // ── No-ops conservados por compatibilidad ────────────────────────────────
    // Antes del proot, había que copiar libs nativas y desplegar wrappers shell.
    // Con proot+Alpine, el binario proot vive en nativeLibraryDir (lo coloca el APK)
    // y `openclaw`/`node`/`npm` viven dentro de Alpine en `/usr/bin`. No hacemos nada.

    fun ensureRuntimeWrappers(@Suppress("UNUSED_PARAMETER") context: Context) { /* no-op */ }
    fun deployNativeLibs(@Suppress("UNUSED_PARAMETER") context: Context,
                         @Suppress("UNUSED_PARAMETER") base: File) { /* no-op */ }
    fun deployScripts(@Suppress("UNUSED_PARAMETER") context: Context,
                      @Suppress("UNUSED_PARAMETER") base: File) { /* no-op */ }
    fun ensureLegacyWrapperPermissions(@Suppress("UNUSED_PARAMETER") context: Context) { /* no-op */ }

    /**
     * Compatibilidad con el código del Dashboard: antes corregía permisos del
     * payload glibc tras la extracción. Con Alpine los permisos los pone el tar
     * de Android al desempaquetar. Mantenemos la firma `suspend` para no romper
     * llamadores existentes.
     */
    suspend fun fixPermissions(@Suppress("UNUSED_PARAMETER") base: File) {
        withContext(Dispatchers.IO) { /* no-op */ }
    }

    fun setupFilesLayout(context: Context) {
        val proot = OpenClawProot(context)
        proot.openclawHome.mkdirs()
        proot.openclawTmp.mkdirs()
        proot.prootTmpDir.mkdirs()
    }

    // ── Instalación (entry point principal) ──────────────────────────────────

    /**
     * Reescribe la API original: `installDetailed(ctx, onProgress, onComplete, onError)`.
     * Ya no acepta payload/migration locales (no aplican en el modelo proot).
     */
    suspend fun installDetailed(
        context: Context,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ): Unit = installInternal(context, onProgress, onComplete, onError)

    /** Mantiene la firma legacy de AndroidBridge.installFromUri (los archivos se ignoran). */
    suspend fun installDetailedFromFiles(
        context: Context,
        @Suppress("UNUSED_PARAMETER") payloadFile: File?,
        @Suppress("UNUSED_PARAMETER") migrationFile: File?,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ): Unit = installInternal(context, onProgress, onComplete, onError)

    private suspend fun installInternal(
        context: Context,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val proot = OpenClawProot(context)

        try {
            // Pre-flight: proot binary debe existir en nativeLibraryDir
            if (!proot.isProotPresent()) {
                throw IllegalStateException(
                    "libproot.so no está en nativeLibraryDir. " +
                    "Reconstruye la APK con la tarea Gradle :app:fetchProot."
                )
            }

            setupFilesLayout(context)

            // Paso 1: Alpine rootfs
            if (!proot.isAlpineInstalled()) {
                if (!isNetworkAvailable(context)) {
                    throw Exception(
                        "Se necesita conexión a Internet para descargar Alpine Linux."
                    )
                }
                onProgress(stepJson(1, "Descargando Alpine Linux", 0))
                val ok = proot.downloadAndExtractAlpine(
                    onProgress = { line ->
                        onProgress(stepJson(1, line, -1))
                    },
                    onError = { msg ->
                        throw Exception(msg)
                    }
                )
                if (!ok) throw Exception("La descarga de Alpine falló")
            } else {
                onProgress(stepJson(1, "Alpine ya instalado", 100))
            }

            // Paso 2: openclaw via npm dentro del proot
            if (!proot.isOpenClawInstalled()) {
                if (!isNetworkAvailable(context)) {
                    throw Exception(
                        "Se necesita conexión a Internet para instalar openclaw via npm."
                    )
                }
                onProgress(stepJson(2, "Instalando openclaw (apk + npm)", 0))
                val latch = CountDownLatch(1)
                var failure: String? = null
                proot.installOpenClaw(
                    onProgress = { line -> onProgress(stepJson(2, line, -1)) },
                    onDone = { latch.countDown() },
                    onError = { msg -> failure = msg; latch.countDown() }
                )
                if (!latch.await(15, TimeUnit.MINUTES)) {
                    throw Exception("Timeout instalando openclaw (>15 min)")
                }
                failure?.let { throw Exception(it) }
            } else {
                onProgress(stepJson(2, "openclaw ya instalado", 100))
            }

            context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(OpenClawConstants.KEY_PAYLOAD_INSTALLED, true)
                .apply()

            onComplete()
        } catch (e: Exception) {
            Log.e(TAG, "installInternal failed", e)
            onError(e.message ?: "Error desconocido")
        }
    }

    /**
     * Variante simple usada por código legacy. Devuelve `true` si la
     * instalación completó.
     */
    suspend fun installPayload(
        context: Context,
        onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        val latch = CountDownLatch(1)
        installDetailed(
            context = context,
            onProgress = { line -> onProgress(line, -1) },
            onComplete = { ok = true; latch.countDown() },
            onError = { msg -> Log.e(TAG, "installPayload: $msg"); latch.countDown() }
        )
        latch.await(15, TimeUnit.MINUTES)
        ok
    }

    // ── Network helpers ──────────────────────────────────────────────────────

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.w(TAG, "isNetworkAvailable check failed", e)
            false
        }
    }

    // ── Downloads legacy (conservados para AndroidBridge.pickPayloadFile flow) ──
    // En el nuevo modelo no hay payload remoto; estas devuelven null.
    @Suppress("UNUSED_PARAMETER")
    suspend fun downloadPayloadFromGithub(
        context: Context,
        onProgress: (read: Long, total: Long) -> Unit
    ): File? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun downloadMigrationFromGithub(
        context: Context,
        onProgress: (read: Long, total: Long) -> Unit
    ): File? = null

    // ── JSON progress builder ────────────────────────────────────────────────

    private fun stepJson(step: Int, message: String, percent: Int): String {
        val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"step":$step,"totalSteps":2,"percent":$percent,"stepName":"$escaped","currentFile":""}"""
    }
}
