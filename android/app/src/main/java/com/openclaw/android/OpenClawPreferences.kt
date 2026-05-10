package com.openclaw.android

import android.content.Context
import android.content.SharedPreferences

object OpenClawPreferences {
    private const val PREFS_NAME = "openclaw_prefs"
    private const val KEY_BACKGROUND_EXECUTION_ENABLED = "background_execution_enabled"
    private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var isBackgroundExecutionEnabled: Boolean
        get() = getPrefs(App.context).getBoolean(KEY_BACKGROUND_EXECUTION_ENABLED, true)
        set(value) {
            getPrefs(App.context).edit().putBoolean(KEY_BACKGROUND_EXECUTION_ENABLED, value).apply()
        }

    var isNotificationPermissionRequested: Boolean
        get() = getPrefs(App.context).getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)
        set(value) {
            getPrefs(App.context).edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, value).apply()
        }
}
