package com.honeybadger.terminal.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.util.Pair;
import android.view.WindowManager;

import com.honeybadger.terminal.R;
import com.termux.shared.file.FileUtils;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR;
import static com.termux.shared.termux.TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH;

/**
 * Install the Termux bootstrap packages if necessary by following the below steps:
 * <p/>
 * (1) If $PREFIX already exist, assume that it is correct and be done. Note that this relies on that we do not create a
 * broken $PREFIX directory below.
 * <p/>
 * (2) A progress dialog is shown with "Installing..." message and a spinner.
 * <p/>
 * (3) A staging directory, $STAGING_PREFIX, is cleared if left over from broken installation below.
 * <p/>
 * (4) The zip file is loaded from a shared library.
 * <p/>
 * (5) The zip, containing entries relative to the $PREFIX, is is downloaded and extracted by a zip input stream
 * continuously encountering zip file entries:
 * <p/>
 * (5.1) If the zip entry encountered is SYMLINKS.txt, go through it and remember all symlinks to setup.
 * <p/>
 * (5.2) For every other zip entry, extract it into $STAGING_PREFIX and set execute permissions if necessary.
 */
@SuppressLint("NewApi")
final class TermuxInstaller {

    private static final String LOG_TAG = "TermuxInstaller";

    /** Performs bootstrap setup if necessary. */
    static void setupBootstrapIfNeeded(final Activity activity, final Runnable whenDone) {
        String bootstrapErrorMessage;
        Error filesDirectoryAccessibleError;

        // This will also call Context.getFilesDir(), which should ensure that termux files directory
        // is created if it does not already exist
        filesDirectoryAccessibleError = TermuxFileUtils.isTermuxFilesDirectoryAccessible(activity, true, true);
        boolean isFilesDirectoryAccessible = filesDirectoryAccessibleError == null;

        // Termux can only be run as the primary user (device owner) since only that
        // account has the expected file system paths. Verify that:
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !PackageUtils.isCurrentUserThePrimaryUser(activity)) {
            bootstrapErrorMessage = activity.getString(R.string.bootstrap_error_not_primary_user_message,
                MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            Logger.logError(LOG_TAG, "isFilesDirectoryAccessible: " + isFilesDirectoryAccessible);
            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage);
            return;
        }

        if (!isFilesDirectoryAccessible) {
            bootstrapErrorMessage = Error.getMinimalErrorString(filesDirectoryAccessibleError);
            //noinspection SdCardPath
            if (PackageUtils.isAppInstalledOnExternalStorage(activity) &&
                !TermuxConstants.TERMUX_FILES_DIR_PATH.equals(activity.getFilesDir().getAbsolutePath().replaceAll("^/data/user/0/", "/data/data/"))) {
                bootstrapErrorMessage += "\n\n" + activity.getString(R.string.bootstrap_error_installed_on_portable_sd,
                    MarkdownUtils.getMarkdownCodeForString(TERMUX_PREFIX_DIR_PATH, false));
            }

            Logger.logError(LOG_TAG, bootstrapErrorMessage);
            sendBootstrapCrashReportNotification(activity, bootstrapErrorMessage);
            MessageDialogUtils.showMessage(activity,
                activity.getString(R.string.bootstrap_error_title),
                bootstrapErrorMessage, null);
            return;
        }

        // If prefix directory exists, even if its a symlink to a valid directory and symlink is not broken/dangling
        if (FileUtils.directoryFileExists(TERMUX_PREFIX_DIR_PATH, true)) {
            if (TermuxFileUtils.isTermuxPrefixDirectoryEmpty()) {
                Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" exists but is empty or only contains specific unimportant files.");
            } else {
                whenDone.run();
                return;
            }
        } else if (FileUtils.fileExists(TERMUX_PREFIX_DIR_PATH, false)) {
            Logger.logInfo(LOG_TAG, "The termux prefix directory \"" + TERMUX_PREFIX_DIR_PATH + "\" does not exist but another file exists at its destination.");
        }

        final ProgressDialog progress = ProgressDialog.show(activity, null, activity.getString(R.string.bootstrap_installer_body), true, false);
        new Thread() {
            @Override
            public void run() {
                try {
                    Logger.logInfo(LOG_TAG, "Installing " + TermuxConstants.TERMUX_APP_NAME + " bootstrap packages.");

                    Error error;

                    // Delete prefix staging directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix staging directory", TERMUX_STAGING_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Delete prefix directory or any file at its destination
                    error = FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix staging directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixStagingDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    // Create prefix directory if it does not already exist and set required permissions
                    error = TermuxFileUtils.isTermuxPrefixDirectoryAccessible(true, true);
                    if (error != null) {
                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Extracting bootstrap zip to prefix staging directory \"" + TERMUX_STAGING_PREFIX_DIR_PATH + "\".");

                    final byte[] buffer = new byte[8096];
                    final List<Pair<String, String>> symlinks = new ArrayList<>(50);

                    final byte[] zipBytes = loadZipBytes();
                    try (ZipInputStream zipInput = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
                        ZipEntry zipEntry;
                        while ((zipEntry = zipInput.getNextEntry()) != null) {
                            if (zipEntry.getName().equals("SYMLINKS.txt")) {
                                BufferedReader symlinksReader = new BufferedReader(new InputStreamReader(zipInput));
                                String line;
                                while ((line = symlinksReader.readLine()) != null) {
                                    String[] parts = line.split("←");
                                    if (parts.length != 2)
                                        throw new RuntimeException("Malformed symlink line: " + line);
                                    // Honey Badger: Replace com.termux in symlink targets
                                    // with our package name so symlinks point to correct paths
                                    String oldPath = parts[0].replace("com.termux",
                                        TermuxConstants.TERMUX_PACKAGE_NAME);
                                    String newPath = TERMUX_STAGING_PREFIX_DIR_PATH + "/" + parts[1];
                                    symlinks.add(Pair.create(oldPath, newPath));

                                    error = ensureDirectoryExists(new File(newPath).getParentFile());
                                    if (error != null) {
                                        showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                        return;
                                    }
                                }
                            } else {
                                String zipEntryName = zipEntry.getName();
                                File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, zipEntryName);
                                boolean isDirectory = zipEntry.isDirectory();

                                error = ensureDirectoryExists(isDirectory ? targetFile : targetFile.getParentFile());
                                if (error != null) {
                                    showBootstrapErrorDialog(activity, whenDone, Error.getErrorMarkdownString(error));
                                    return;
                                }

                                if (!isDirectory) {
                                    try (FileOutputStream outStream = new FileOutputStream(targetFile)) {
                                        int readBytes;
                                        while ((readBytes = zipInput.read(buffer)) != -1)
                                            outStream.write(buffer, 0, readBytes);
                                    }
                                    if (zipEntryName.startsWith("bin/") || zipEntryName.startsWith("libexec") ||
                                        zipEntryName.startsWith("lib/apt/apt-helper") || zipEntryName.startsWith("lib/apt/methods")) {
                                        //noinspection OctalInteger
                                        Os.chmod(targetFile.getAbsolutePath(), 0700);
                                    }
                                }
                            }
                        }
                    }

                    if (symlinks.isEmpty())
                        throw new RuntimeException("No SYMLINKS.txt encountered");
                    for (Pair<String, String> symlink : symlinks) {
                        Os.symlink(symlink.first, symlink.second);
                    }

                    // Honey Badger: Fix com.termux paths in bootstrap files.
                    // The bootstrap ZIP is from official Termux and contains hardcoded
                    // /data/data/com.termux/ paths in shebangs, config files, etc.
                    // libtermux-exec.so handles this at runtime via LD_PRELOAD for
                    // execve() calls, but the kernel's shebang resolution for the
                    // very first process (login shell) happens before LD_PRELOAD
                    // takes effect. We must patch these paths in the staging directory.
                    fixBootstrapPaths(TERMUX_STAGING_PREFIX_DIR);

                    Logger.logInfo(LOG_TAG, "Moving termux prefix staging to prefix directory.");

                    if (!TERMUX_STAGING_PREFIX_DIR.renameTo(TERMUX_PREFIX_DIR)) {
                        throw new RuntimeException("Moving termux prefix staging to prefix directory failed");
                    }

                    // Note: /data/data/com.termux/ symlink cannot be created due to SELinux.
                    // Path rewriting for packages is handled by the tar wrapper instead.

                    Logger.logInfo(LOG_TAG, "Bootstrap packages installed successfully.");

                    // Copy bpatch binary patcher tool from assets to $PREFIX/bin/
                    // Used by first-run.sh to patch hardcoded com.termux paths in
                    // newly installed binaries (pacman, libalpm, libcurl, etc.)
                    try {
                        File bpatchDest = new File(TERMUX_PREFIX_DIR, "bin/bpatch");
                        try (InputStream in = activity.getAssets().open("bpatch");
                             FileOutputStream out = new FileOutputStream(bpatchDest)) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                        }
                        bpatchDest.setExecutable(true);
                        Logger.logInfo(LOG_TAG, "bpatch tool installed to " + bpatchDest.getAbsolutePath());
                    } catch (Exception e) {
                        Logger.logWarn(LOG_TAG, "Failed to install bpatch: " + e.getMessage());
                    }

                    // Recreate env file since termux prefix was wiped earlier
                    TermuxShellEnvironment.writeEnvironmentToFile(activity);

                    activity.runOnUiThread(whenDone);

                } catch (final Exception e) {
                    showBootstrapErrorDialog(activity, whenDone, Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)));

                } finally {
                    activity.runOnUiThread(() -> {
                        try {
                            progress.dismiss();
                        } catch (RuntimeException e) {
                            // Activity already dismissed - ignore.
                        }
                    });
                }
            }
        }.start();
    }

    public static void showBootstrapErrorDialog(Activity activity, Runnable whenDone, String message) {
        Logger.logErrorExtended(LOG_TAG, "Bootstrap Error:\n" + message);

        // Send a notification with the exception so that the user knows why bootstrap setup failed
        sendBootstrapCrashReportNotification(activity, message);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity).setTitle(R.string.bootstrap_error_title).setMessage(R.string.bootstrap_error_body)
                    .setNegativeButton(R.string.bootstrap_error_abort, (dialog, which) -> {
                        dialog.dismiss();
                        activity.finish();
                    })
                    .setPositiveButton(R.string.bootstrap_error_try_again, (dialog, which) -> {
                        dialog.dismiss();
                        FileUtils.deleteFile("termux prefix directory", TERMUX_PREFIX_DIR_PATH, true);
                        TermuxInstaller.setupBootstrapIfNeeded(activity, whenDone);
                    }).show();
            } catch (WindowManager.BadTokenException e1) {
                // Activity already dismissed - ignore.
            }
        });
    }

    private static void sendBootstrapCrashReportNotification(Activity activity, String message) {
        final String title = TermuxConstants.TERMUX_APP_NAME + " Bootstrap Error";

        // Add info of all install Termux plugin apps as well since their target sdk or installation
        // on external/portable sd card can affect Termux app files directory access or exec.
        TermuxCrashUtils.sendCrashReportNotification(activity, LOG_TAG,
            title, null, "## " + title + "\n\n" + message + "\n\n" +
                TermuxUtils.getTermuxDebugMarkdownString(activity),
            true, false, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES, true);
    }

    static void setupStorageSymlinks(final Context context) {
        final String LOG_TAG = "termux-storage";
        final String title = TermuxConstants.TERMUX_APP_NAME + " Setup Storage Error";

        Logger.logInfo(LOG_TAG, "Setting up storage symlinks.");

        new Thread() {
            public void run() {
                try {
                    Error error;
                    File storageDir = TermuxConstants.TERMUX_STORAGE_HOME_DIR;

                    error = FileUtils.clearDirectory("~/storage", storageDir.getAbsolutePath());
                    if (error != null) {
                        Logger.logErrorAndShowToast(context, LOG_TAG, error.getMessage());
                        Logger.logErrorExtended(LOG_TAG, "Setup Storage Error\n" + error.toString());
                        TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                            "## " + title + "\n\n" + Error.getErrorMarkdownString(error),
                            true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                        return;
                    }

                    Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/shared, ~/storage/downloads, ~/storage/dcim, ~/storage/pictures, ~/storage/music and ~/storage/movies for directories in \"" + Environment.getExternalStorageDirectory().getAbsolutePath() + "\".");

                    // Get primary storage root "/storage/emulated/0" symlink
                    File sharedDir = Environment.getExternalStorageDirectory();
                    Os.symlink(sharedDir.getAbsolutePath(), new File(storageDir, "shared").getAbsolutePath());

                    File documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                    Os.symlink(documentsDir.getAbsolutePath(), new File(storageDir, "documents").getAbsolutePath());

                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    Os.symlink(downloadsDir.getAbsolutePath(), new File(storageDir, "downloads").getAbsolutePath());

                    File dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                    Os.symlink(dcimDir.getAbsolutePath(), new File(storageDir, "dcim").getAbsolutePath());

                    File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    Os.symlink(picturesDir.getAbsolutePath(), new File(storageDir, "pictures").getAbsolutePath());

                    File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                    Os.symlink(musicDir.getAbsolutePath(), new File(storageDir, "music").getAbsolutePath());

                    File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
                    Os.symlink(moviesDir.getAbsolutePath(), new File(storageDir, "movies").getAbsolutePath());

                    File podcastsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS);
                    Os.symlink(podcastsDir.getAbsolutePath(), new File(storageDir, "podcasts").getAbsolutePath());

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        File audiobooksDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS);
                        Os.symlink(audiobooksDir.getAbsolutePath(), new File(storageDir, "audiobooks").getAbsolutePath());
                    }

                    // Dir 0 should ideally be for primary storage
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/app/ContextImpl.java;l=818
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=219
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/os/Environment.java;l=181
                    // https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/StorageManagerService.java;l=3796
                    // https://cs.android.com/android/platform/superproject/+/android-7.0.0_r36:frameworks/base/services/core/java/com/android/server/MountService.java;l=3053

                    // Create "Android/data/com.termux" symlinks
                    File[] dirs = context.getExternalFilesDirs(null);
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "external-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    // Create "Android/media/com.termux" symlinks
                    dirs = context.getExternalMediaDirs();
                    if (dirs != null && dirs.length > 0) {
                        for (int i = 0; i < dirs.length; i++) {
                            File dir = dirs[i];
                            if (dir == null) continue;
                            String symlinkName = "media-" + i;
                            Logger.logInfo(LOG_TAG, "Setting up storage symlinks at ~/storage/" + symlinkName + " for \"" + dir.getAbsolutePath() + "\".");
                            Os.symlink(dir.getAbsolutePath(), new File(storageDir, symlinkName).getAbsolutePath());
                        }
                    }

                    Logger.logInfo(LOG_TAG, "Storage symlinks created successfully.");
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(context, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Setup Storage Error: Error setting up link", e);
                    TermuxCrashUtils.sendCrashReportNotification(context, LOG_TAG, title, null,
                        "## " + title + "\n\n" + Logger.getStackTracesMarkdownString(null, Logger.getStackTracesStringArray(e)),
                        true, false, TermuxUtils.AppInfoMode.TERMUX_PACKAGE, true);
                }
            }
        }.start();
    }

    private static Error ensureDirectoryExists(File directory) {
        return FileUtils.createDirectoryFile(directory.getAbsolutePath());
    }

    /**
     * Fix hardcoded /data/data/com.termux/ paths in bootstrap files.
     *
     * The Termux bootstrap ZIP contains scripts with shebangs like
     * #!/data/data/com.termux/files/usr/bin/sh. Since our package name is
     * com.honeybadger.terminal, the kernel can't resolve these shebangs.
     *
     * libtermux-exec.so (LD_PRELOAD) handles this at runtime for child processes,
     * but the very first process (login shell) is exec'd by the kernel directly,
     * before LD_PRELOAD takes effect. So we must patch the paths.
     *
     * Additionally, dpkg has hardcoded open() calls for its config directory
     * that libtermux-exec can't intercept. We create a dpkg wrapper and
     * configure apt.conf with explicit paths.
     */
    private static void fixBootstrapPaths(File stagingDir) {
        String oldPath = "/data/data/com.termux/";
        String newPath = "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/";

        if (oldPath.equals(newPath)) return; // No patching needed if package name is com.termux

        Logger.logInfo(LOG_TAG, "Patching com.termux paths → " + TermuxConstants.TERMUX_PACKAGE_NAME);

        // Patch bin/ scripts (shebangs)
        fixFilesInDir(new File(stagingDir, "bin"), oldPath, newPath, false);

        // Patch libexec/ scripts
        fixFilesInDir(new File(stagingDir, "libexec"), oldPath, newPath, true);

        // Patch etc/ config files
        fixFilesInDir(new File(stagingDir, "etc"), oldPath, newPath, true);

        // Patch var/lib/dpkg/status
        fixSingleFile(new File(stagingDir, "var/lib/dpkg/status"), oldPath, newPath);

        // Patch var/lib/dpkg/info/*.list files
        File dpkgInfoDir = new File(stagingDir, "var/lib/dpkg/info");
        if (dpkgInfoDir.isDirectory()) {
            File[] files = dpkgInfoDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && (f.getName().endsWith(".list") || f.getName().endsWith(".conffiles"))) {
                        fixSingleFile(f, oldPath, newPath);
                    }
                }
            }
        }

        // Patch lib/apt/ scripts
        fixFilesInDir(new File(stagingDir, "lib/apt"), oldPath, newPath, true);

        // Configure apt and dpkg for our package name
        configureApt(stagingDir);
        createDpkgWrapper(stagingDir);
    }

    /**
     * Configure apt with explicit paths for our package name.
     * Creates apt.conf, ensures required directories exist, and patches sources.list.
     * Pattern from v1 BootstrapManager.configureApt().
     */
    private static void configureApt(File stagingDir) {
        String prefix = TERMUX_PREFIX_DIR_PATH;

        // Ensure directories exist
        new File(stagingDir, "etc/apt/apt.conf.d").mkdirs();
        new File(stagingDir, "etc/apt/preferences.d").mkdirs();
        new File(stagingDir, "etc/dpkg/dpkg.cfg.d").mkdirs();
        new File(stagingDir, "var/cache/apt").mkdirs();
        new File(stagingDir, "var/log/apt").mkdirs();

        // Write apt.conf with correct paths
        File aptConf = new File(stagingDir, "etc/apt/apt.conf");
        try {
            aptConf.getParentFile().mkdirs();
            String conf =
                "Dir \"/\";\n" +
                "Dir::State \"" + prefix + "/var/lib/apt/\";\n" +
                "Dir::State::status \"" + prefix + "/var/lib/dpkg/status\";\n" +
                "Dir::Cache \"" + prefix + "/var/cache/apt/\";\n" +
                "Dir::Log \"" + prefix + "/var/log/apt/\";\n" +
                "Dir::Etc \"" + prefix + "/etc/apt/\";\n" +
                "Dir::Etc::SourceList \"" + prefix + "/etc/apt/sources.list\";\n" +
                "Dir::Etc::SourceParts \"\";\n" +
                "Dir::Bin::dpkg \"" + prefix + "/bin/dpkg\";\n" +
                "Dir::Bin::Methods \"" + prefix + "/lib/apt/methods/\";\n" +
                "Dir::Bin::apt-key \"" + prefix + "/bin/apt-key\";\n" +
                "Dpkg::Options:: \"--force-all\";\n" +
                "Acquire::AllowInsecureRepositories \"true\";\n" +
                "APT::Get::AllowUnauthenticated \"true\";\n";
            java.nio.file.Files.write(aptConf.toPath(), conf.getBytes());
            Logger.logInfo(LOG_TAG, "apt.conf configured with correct paths");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to write apt.conf: " + e.getMessage());
        }

        // Patch sources.list: HTTPS→HTTP downgrade (some devices have TLS issues with apt)
        File sourcesList = new File(stagingDir, "etc/apt/sources.list");
        if (sourcesList.exists()) {
            try {
                String content = new String(java.nio.file.Files.readAllBytes(sourcesList.toPath()));
                content = content.replace("https://", "http://");
                java.nio.file.Files.write(sourcesList.toPath(), content.getBytes());
                Logger.logInfo(LOG_TAG, "sources.list downgraded to HTTP");
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "Failed to patch sources.list: " + e.getMessage());
            }
        }
    }

    /**
     * Create dpkg wrapper to handle confdir path issues.
     * The bootstrap dpkg has /data/data/com.termux/.../etc/dpkg/ hardcoded in
     * its opendir() calls. libtermux-exec only intercepts execve(), not open().
     *
     * Solution: The wrapper creates the old com.termux directory hierarchy as
     * symlinks pointing to our actual directories. This way dpkg's hardcoded
     * opendir() succeeds and finds the right config files.
     *
     * Additionally sets PATH so dpkg can find sh, tar, etc.
     * Pattern from v1 BootstrapManager.setupTermuxExec().
     */
    private static void createDpkgWrapper(File stagingDir) {
        File dpkgBin = new File(stagingDir, "bin/dpkg");
        File dpkgReal = new File(stagingDir, "bin/dpkg.real");
        if (!dpkgBin.exists()) return;

        try {
            // Rename original dpkg to dpkg.real
            if (!dpkgReal.exists()) {
                java.nio.file.Files.move(dpkgBin.toPath(), dpkgReal.toPath());
            }

            String realPath = TERMUX_PREFIX_DIR_PATH + "/bin/dpkg.real";
            String ourPrefix = TERMUX_PREFIX_DIR_PATH;
            // The old prefix that dpkg has hardcoded
            String oldPrefix = "/data/data/com.termux/files/usr";

            // The dpkg binary has hardcoded confdir at /data/data/com.termux/files/usr/etc/dpkg
            // (40 chars). We can't create that path (SELinux blocks it) and can't change the
            // compiled-in confdir with command-line flags.
            //
            // Solution: Binary-patch dpkg.real to change the confdir path to point to
            // a symlink within our data directory. We create:
            //   /data/data/com.honeybadger.terminal/d -> files/usr/etc/dpkg
            // Then patch the binary path from /data/data/com.termux/files/usr/etc/dpkg (40 chars)
            // to a null-terminated path of equal or shorter length.
            //
            // Binary-patch ALL dpkg-family binaries to replace hardcoded
            // /data/data/com.termux paths with shorter symlink paths.
            // Each binary (dpkg.real, dpkg-deb, dpkg-query, etc.) has the same
            // set of hardcoded paths compiled in.
            String appDataDir = "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME;

            // Create short symlinks within our data dir as patch targets:
            //   /d → files/usr/etc/dpkg   (confdir)
            //   /a → files/usr/var/lib/dpkg (admindir)
            //   /p → files/usr             (prefix)
            //   /h → files/home            (homedir)
            //   /t → files/usr/tmp         (tmpdir)
            //   /l → files/usr/lib         (libdir)
            String[][] symlinks = {
                {"d", "files/usr/etc/dpkg"},
                {"a", "files/usr/var/lib/dpkg"},
                {"p", "files/usr"},
                {"h", "files/home"},
                {"t", "files/usr/tmp"},
                {"l", "files/usr/lib"},
            };
            for (String[] sl : symlinks) {
                File link = new File(appDataDir, sl[0]);
                if (!link.exists()) {
                    try {
                        android.system.Os.symlink(sl[1], link.getAbsolutePath());
                    } catch (Exception e) {
                        Logger.logWarn(LOG_TAG, "Failed to create " + sl[0] + " symlink: " + e.getMessage());
                    }
                }
            }

            // Patch targets: specific paths → short symlinks
            // Order matters: longer/more-specific paths first, then shorter ones
            String[][] patchMap = {
                {"/data/data/com.termux/files/usr/var/lib/dpkg", appDataDir + "/a"},
                {"/data/data/com.termux/files/usr/etc/dpkg", appDataDir + "/d"},
                {"/data/data/com.termux/files/usr/tmp/", appDataDir + "/t/"},
                {"/data/data/com.termux/files/usr/lib", appDataDir + "/l"},
                {"/data/data/com.termux/files/usr", appDataDir + "/p"},
                {"/data/data/com.termux/files/home", appDataDir + "/h"},
            };

            // List of ALL dpkg-family ELF binaries to patch
            String[] dpkgBinaries = {
                "bin/dpkg.real", "bin/dpkg-deb", "bin/dpkg-divert",
                "bin/dpkg-query", "bin/dpkg-split", "bin/dpkg-trigger"
            };

            for (String binName : dpkgBinaries) {
                File bin = new File(stagingDir, binName);
                if (!bin.exists()) continue;
                for (String[] patch : patchMap) {
                    binaryPatchFile(bin, patch[0], patch[1]);
                }
            }

            Logger.logInfo(LOG_TAG, "dpkg family binaries binary-patched for " + TermuxConstants.TERMUX_PACKAGE_NAME);

            // Deb packages from the Termux repo contain absolute paths like
            // /data/data/com.termux/files/usr/bin/pinentry. Since we can't access
            // /data/data/com.termux/ (SELinux blocks it), and we can't create a
            // symlink there, we intercept dpkg's tar extraction by creating a
            // tar wrapper that rewrites com.termux paths to our package name.
            // dpkg internally calls tar to extract package contents.
            String wrapper =
                "#!/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/sh\n" +
                "# dpkg wrapper: sets PATH, LD_LIBRARY_PATH and calls dpkg.real.\n" +
                "export PATH=\"" + ourPrefix + "/bin:" + ourPrefix + "/bin/applets:$PATH\"\n" +
                "export LD_LIBRARY_PATH=\"" + ourPrefix + "/lib\"\n" +
                "exec \"" + realPath + "\" \"$@\"\n";

            // Create tar wrapper that rewrites com.termux paths during extraction.
            // When dpkg extracts packages, the tar stream contains paths starting
            // with /data/data/com.termux/. The wrapper adds --transform to rewrite
            // these paths to our package name during extraction.
            File tarBin = new File(stagingDir, "bin/tar");
            File tarReal = new File(stagingDir, "bin/tar.real");
            if (tarBin.exists() && !tarReal.exists()) {
                java.nio.file.Files.move(tarBin.toPath(), tarReal.toPath());
                String tarRealPath = ourPrefix + "/bin/tar.real";
                // The wrapper always adds the --transform flag. This is safe because
                // the transform is a no-op when paths don't contain com.termux.
                String tarWrapper =
                    "#!/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files/usr/bin/sh\n" +
                    "# tar wrapper: rewrites com.termux → " + TermuxConstants.TERMUX_PACKAGE_NAME + " in paths.\n" +
                    "exec \"" + tarRealPath + "\" " +
                    "\"--transform=s,com.termux," + TermuxConstants.TERMUX_PACKAGE_NAME + ",g\" " +
                    "\"$@\"\n";
                java.nio.file.Files.write(tarBin.toPath(), tarWrapper.getBytes());
                tarBin.setExecutable(true);
                Logger.logInfo(LOG_TAG, "tar wrapper created for path rewriting");
            }

            java.nio.file.Files.write(dpkgBin.toPath(), wrapper.getBytes());
            dpkgBin.setExecutable(true);
            Logger.logInfo(LOG_TAG, "dpkg wrapper created");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to create dpkg wrapper: " + e.getMessage());
        }
    }

    /** Fix paths in all non-binary files in a directory. */
    private static void fixFilesInDir(File dir, String oldPath, String newPath, boolean recursive) {
        if (!dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && recursive) {
                fixFilesInDir(f, oldPath, newPath, true);
            } else if (f.isFile() && !isElfFile(f)) {
                fixSingleFile(f, oldPath, newPath);
            }
        }
    }

    /** Fix paths in a single text file. */
    private static void fixSingleFile(File file, String oldPath, String newPath) {
        if (!file.exists() || !file.isFile()) return;
        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            String text = new String(content);
            if (text.contains(oldPath)) {
                java.nio.file.Files.write(file.toPath(), text.replace(oldPath, newPath).getBytes());
            }
        } catch (Exception e) {
            // Skip files that can't be read (binary files, permission issues)
        }
    }

    /**
     * Binary-patch a file: replace oldStr with newStr (null-padded to same length).
     * Used to patch hardcoded paths in ELF binaries without changing file size.
     */
    private static void binaryPatchFile(File file, String oldStr, String newStr) {
        if (!file.exists()) return;
        try {
            byte[] content = java.nio.file.Files.readAllBytes(file.toPath());
            byte[] oldBytes = oldStr.getBytes("UTF-8");
            byte[] newPadded = new byte[oldBytes.length];
            byte[] newBytes = newStr.getBytes("UTF-8");
            // Copy new string and pad with null bytes
            System.arraycopy(newBytes, 0, newPadded, 0, Math.min(newBytes.length, newPadded.length));
            // Remaining bytes are already 0 (null padding)

            boolean modified = false;
            for (int i = 0; i <= content.length - oldBytes.length; i++) {
                boolean match = true;
                for (int j = 0; j < oldBytes.length; j++) {
                    if (content[i + j] != oldBytes[j]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    System.arraycopy(newPadded, 0, content, i, newPadded.length);
                    modified = true;
                    Logger.logInfo(LOG_TAG, "Binary patched at offset " + i + " in " + file.getName());
                }
            }
            if (modified) {
                java.nio.file.Files.write(file.toPath(), content);
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to binary-patch " + file.getName() + ": " + e.getMessage());
        }
    }

    /** Check if a file is an ELF binary (to avoid corrupting binaries during path patching). */
    private static boolean isElfFile(File file) {
        try {
            byte[] header = new byte[4];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                if (fis.read(header) == 4) {
                    return header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }

    public static byte[] loadZipBytes() {
        // Only load the shared library when necessary to save memory usage.
        System.loadLibrary("termux-bootstrap");
        return getZip();
    }

    public static native byte[] getZip();

    // ── Honey Badger: first-run.sh support ──

    /** Directory under $HOME for Honey Badger marker and config files. */
    private static final String HB_DIR_NAME = ".honeybadger";

    /** Marker file that indicates first-run setup has completed. */
    private static final String GLIBC_READY_MARKER = ".glibc-ready";

    /** GitHub URL for the latest first-run.sh (used when available). */
    private static final String FIRST_RUN_GITHUB_URL =
        "https://raw.githubusercontent.com/AidanPark/openclaw-android/main/v2/honeybadger/first-run.sh";

    /**
     * Check whether first-run setup is needed.
     * Returns true if bootstrap is installed but the glibc-ready marker does not exist.
     */
    static boolean needsFirstRun() {
        File marker = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, HB_DIR_NAME + "/" + GLIBC_READY_MARKER);
        File prefixBinSh = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/sh");
        return prefixBinSh.exists() && !marker.exists();
    }

    /**
     * Get the path to the first-run.sh script in the home directory.
     * This is where the script is copied to before execution.
     */
    static String getFirstRunScriptPath() {
        return TermuxConstants.TERMUX_HOME_DIR_PATH + "/" + HB_DIR_NAME + "/first-run.sh";
    }

    /**
     * Copy first-run.sh to $HOME/.honeybadger/first-run.sh.
     * Tries to download from GitHub first; falls back to APK bundled asset.
     * Pattern follows v1 BootstrapManager.copyPostSetupScript().
     */
    static void copyFirstRunScript(Context context) {
        File hbDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, HB_DIR_NAME);
        hbDir.mkdirs();
        File target = new File(hbDir, "first-run.sh");

        // Try GitHub download first
        try {
            URL url = new URL(FIRST_RUN_GITHUB_URL);
            try (InputStream input = url.openStream();
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = input.read(buf)) != -1) {
                    output.write(buf, 0, n);
                }
            }
            target.setExecutable(true);
            Logger.logInfo(LOG_TAG, "first-run.sh downloaded from GitHub");
            return;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to download first-run.sh, using bundled fallback: " + e.getMessage());
        }

        // Fallback: copy from APK assets
        try {
            try (InputStream input = context.getAssets().open("first-run.sh");
                 FileOutputStream output = new FileOutputStream(target)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = input.read(buf)) != -1) {
                    output.write(buf, 0, n);
                }
            }
            target.setExecutable(true);
            Logger.logInfo(LOG_TAG, "first-run.sh copied from bundled assets");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to copy bundled first-run.sh: " + e.getMessage());
        }
    }

    /**
     * Install a .bash_profile that triggers first-run.sh on login shell startup.
     * The login shell (bash -l) reads ~/.bash_profile on startup.
     * This is a one-shot trigger: once the marker exists, the profile skips first-run.sh.
     * After first-run.sh completes, the profile self-removes the trigger block.
     */
    static void installFirstRunProfile() {
        File homeDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        homeDir.mkdirs();
        File profile = new File(homeDir, ".bash_profile");

        String scriptPath = getFirstRunScriptPath();
        String markerPath = TermuxConstants.TERMUX_HOME_DIR_PATH + "/" + HB_DIR_NAME + "/" + GLIBC_READY_MARKER;

        // Write a .bash_profile that runs first-run.sh if marker doesn't exist
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("# Honey Badger first-run trigger (auto-generated)\n");
            sb.append("if [ ! -f \"").append(markerPath).append("\" ] && [ -f \"").append(scriptPath).append("\" ]; then\n");
            sb.append("    bash \"").append(scriptPath).append("\"\n");
            sb.append("fi\n");

            // If a user .bash_profile already exists, append to it
            if (profile.exists()) {
                String existing = new String(java.nio.file.Files.readAllBytes(profile.toPath()));
                if (!existing.contains("Honey Badger first-run trigger")) {
                    java.nio.file.Files.write(profile.toPath(),
                        (existing + "\n" + sb.toString()).getBytes());
                }
                // else: already has the trigger, don't duplicate
            } else {
                java.nio.file.Files.write(profile.toPath(), sb.toString().getBytes());
            }

            Logger.logInfo(LOG_TAG, "Installed first-run trigger in .bash_profile");
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to install first-run profile: " + e.getMessage());
        }
    }

}
