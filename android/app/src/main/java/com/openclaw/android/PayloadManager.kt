package com.openclaw.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PayloadManager — thin compatibility facade over [InstallerManager].
 *
 * Exists solely to maintain backward compatibility with [JsBridge] and other
 * callers that use the PayloadManager.InstallListener interface.
 *
 * All real work is delegated to [InstallerManager] and [PayloadExtractor].
 * This class adds ZERO logic — it only translates the listener interface.
 *
 * @see InstallerManager for the real orchestration logic.
 * @see PayloadExtractor for the streaming extraction engine.
 * @see InstallValidator for post-install validation.
 */
class PayloadManager(private val context: Context) {

    private val installer = InstallerManager(context)

    /**
     * Legacy listener interface.
     * Used by JsBridge and any code that hasn't migrated to InstallerManager.ProgressListener.
     */
    interface InstallListener {
        fun onProgress(step: String, percent: Int)
        fun onSuccess()
        fun onError(message: String)
    }

    /** True if the payload is installed (marker + dirs). */
    fun isInstalled(): Boolean = installer.isInstalled()

    /** True if APK bundles payload assets. */
    fun hasPayloadAsset(): Boolean = installer.hasPayloadAsset()

    /** True if the environment is fully functional. */
    fun isReady(): Boolean = installer.isReady()

    /** True if OpenClaw core is installed. */
    fun isOpenClawInstalled(): Boolean = installer.isOpenClawInstalled()

    /** Returns status string for JsBridge. */
    fun getStatus(): String = installer.getStatus()

    /**
     * Install the payload using the legacy listener interface.
     * Delegates entirely to [InstallerManager.install].
     */
    suspend fun install(listener: InstallListener) = withContext(Dispatchers.IO) {
        installer.install("auto", null, object : InstallerManager.ProgressListener {
            override fun onProgress(percent: Int, message: String) {
                listener.onProgress(message, percent)
            }

            override fun onSuccess() {
                listener.onSuccess()
            }

            override fun onError(message: String, cause: Throwable?) {
                listener.onError(message)
            }
        })
    }

    /** Sync www assets from APK. */
    fun syncWwwFromAssets() = installer.syncWwwFromAssets()

    /** Apply script updates on APK version change. */
    fun applyScriptUpdate() = installer.applyScriptUpdate()
}
