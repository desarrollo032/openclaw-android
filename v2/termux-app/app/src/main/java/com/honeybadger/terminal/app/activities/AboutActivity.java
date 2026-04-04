package com.honeybadger.terminal.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.honeybadger.terminal.BuildConfig;
import com.honeybadger.terminal.R;

/**
 * About screen showing app version, Termux credits, and license information.
 * <p>
 * Honey Badger is built on Termux, an Android terminal emulator and Linux
 * environment app. We display prominent credit and license information as
 * required by the GPLv3 license.
 */
public final class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.action_about);
        }

        // Version info
        TextView versionText = findViewById(R.id.about_version);
        versionText.setText(getString(R.string.about_version_format, BuildConfig.VERSION_NAME));

        // Make links clickable
        TextView termuxCredit = findViewById(R.id.about_termux_credit);
        termuxCredit.setMovementMethod(LinkMovementMethod.getInstance());

        TextView projectLink = findViewById(R.id.about_project_link);
        projectLink.setMovementMethod(LinkMovementMethod.getInstance());

        // Termux GitHub button
        findViewById(R.id.about_btn_termux_github).setOnClickListener(v -> {
            openUrl("https://github.com/termux/termux-app");
        });

        // Project GitHub button
        findViewById(R.id.about_btn_project_github).setOnClickListener(v -> {
            openUrl("https://github.com/AidanPark/openclaw-android");
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }
}
