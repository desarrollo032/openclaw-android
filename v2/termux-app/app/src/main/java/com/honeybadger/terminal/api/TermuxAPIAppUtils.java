package com.honeybadger.terminal.api;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;

/**
 * Utility class for Termux:API log configuration.
 * Extracted from TermuxAPIApplication since API is now integrated into the main app.
 */
public class TermuxAPIAppUtils {

    public static void setLogConfig(Context context, boolean commitToFile) {
        Logger.setDefaultLogTag(TermuxConstants.TERMUX_API_APP_NAME.replaceAll("[: ]", ""));

        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAPIAppSharedPreferences preferences = TermuxAPIAppSharedPreferences.build(context);
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel(true), commitToFile);
    }

}
