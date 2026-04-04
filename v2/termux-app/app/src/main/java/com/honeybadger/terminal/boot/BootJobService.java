package com.honeybadger.terminal.boot;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;
import android.util.Log;

import com.termux.shared.termux.TermuxConstants;

public class BootJobService extends JobService {

    public static final String SCRIPT_FILE_PATH = TermuxConstants.TERMUX_PACKAGE_NAME + ".boot.script_path";

    private static final String TAG = "termux";

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Executing job " + params.getJobId() + ".");

        PersistableBundle extras = params.getExtras();
        String filePath = extras.getString(SCRIPT_FILE_PATH);

        Uri scriptUri = new Uri.Builder().scheme(TermuxConstants.TERMUX_PACKAGE_NAME + ".file").path(filePath).build();
        Intent executeIntent = new Intent(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, scriptUri);
        executeIntent.setClassName(TermuxConstants.TERMUX_PACKAGE_NAME, TermuxConstants.TERMUX_APP.TERMUX_SERVICE_NAME);
        executeIntent.putExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, true);

        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // https://developer.android.com/about/versions/oreo/background.html
            context.startForegroundService(executeIntent);
        } else {
            context.startService(executeIntent);
        }

        return false; // offloaded to Termux; job is done
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.i(TAG, "Execution of job " + params.getJobId() + " has been cancelled.");
        return false; // do not reschedule
    }
}
