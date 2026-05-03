package com.openclaw.android

import java.io.File

/**
 * InstallValidator — verifies that an installation is complete and functional.
 *
 * Called AFTER all extraction steps finish, BEFORE writing the .installed marker.
 * This prevents the app from thinking the install is done when critical files are missing.
 *
 * Design:
 *   - Pure function: takes paths, returns result. No side effects.
 *   - Each check returns a human-readable error message on failure.
 *   - The caller (InstallerManager) decides whether to abort or continue.
 */
object InstallValidator {

    private const val TAG = "InstallValidator"

    /**
     * Result of a validation run.
     * @param passed  True if ALL critical checks passed.
     * @param errors  List of human-readable error strings (empty if passed).
     * @param warnings  Non-fatal issues that were detected.
     */
    data class ValidationResult(
        val passed: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
    )

    /**
     * Validate a payload installation.
     *
     * Checks (in order of criticality):
     *   1. PREFIX/bin/ exists and is non-empty
     *   2. PREFIX/lib/ exists
     *   3. PREFIX/glibc/lib/ exists
     *   4. PREFIX/glibc/lib/ld-linux-aarch64.so.1 exists (dynamic linker)
     *   5. PREFIX/etc/ exists
     *   6. bash or sh wrapper exists in bin/
     *
     * @param prefix  The PREFIX directory (e.g. filesDir/usr)
     * @return ValidationResult with detailed findings
     */
    fun validatePayload(prefix: File): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ── Critical: binary directory ──────────────────────────────────────
        val binDir = File(prefix, "bin")
        if (!binDir.isDirectory) {
            errors.add("bin/ directory missing")
        } else {
            val binFiles = binDir.listFiles()
            if (binFiles.isNullOrEmpty()) {
                errors.add("bin/ directory is empty")
            }
        }

        // ── Critical: library directory ─────────────────────────────────────
        val libDir = File(prefix, "lib")
        if (!libDir.isDirectory) {
            errors.add("lib/ directory missing")
        }

        // ── Critical: glibc runtime ─────────────────────────────────────────
        val glibcLib = File(prefix, "glibc/lib")
        if (!glibcLib.isDirectory) {
            errors.add("glibc/lib/ directory missing")
        }

        val ldSo = File(prefix, "glibc/lib/ld-linux-aarch64.so.1")
        if (!ldSo.exists()) {
            errors.add("ld-linux-aarch64.so.1 missing (glibc dynamic linker)")
        }

        // ── Important: etc directory ────────────────────────────────────────
        val etcDir = File(prefix, "etc")
        if (!etcDir.isDirectory) {
            warnings.add("etc/ directory missing (may affect DNS/SSL)")
        }

        // ── Critical: shell availability ────────────────────────────────────
        // Accept either a shell in the payload OR the Android system shell.
        // The system shell (/system/bin/sh) is always available on Android 7+
        // and is a valid fallback when the payload doesn't bundle bash/sh.
        val bashExists = File(binDir, "bash").exists()
        val shExists = File(binDir, "sh").exists()
        val systemShExists = File("/system/bin/sh").exists()
        if (!bashExists && !shExists && !systemShExists) {
            errors.add("No shell (bash/sh) found in bin/ — payload is incomplete or corrupt")
        }

        // ── Important: SSL certs ────────────────────────────────────────────
        val certPem = File(prefix, "etc/tls/cert.pem")
        val certDir = File(prefix, "etc/tls/certs")
        if (!certPem.exists() && !certDir.isDirectory) {
            warnings.add("SSL certificates not found — HTTPS may fail")
        }

        val passed = errors.isEmpty()

        if (passed) {
            AppLogger.i(TAG, "Validation PASSED (${warnings.size} warnings)")
        } else {
            AppLogger.e(TAG, "Validation FAILED: $errors")
        }
        warnings.forEach { AppLogger.w(TAG, "Validation warning: $it") }

        return ValidationResult(passed, errors, warnings)
    }

    /**
     * Quick check: is the payload extraction structurally complete?
     * Lighter than full validatePayload — for status checks.
     *
     * Accepts either a traditional Termux-style prefix (has bin/bash or bin/sh)
     * OR an OpenClaw payload prefix (has bin/node or the glibc-wrapped node wrapper).
     * Both layouts are valid — the key requirement is the glibc dynamic linker.
     *
     * Also accepts the Android system shell (/system/bin/sh) as a valid shell,
     * since it is always present on Android 7+ and can run post-setup scripts.
     */
    fun isStructurallyComplete(prefix: File): Boolean {
        val hasGlibcLinker = File(prefix, "glibc/lib/ld-linux-aarch64.so.1").exists()
        val hasBinDir = File(prefix, "bin").isDirectory
        val hasLibDir = File(prefix, "lib").isDirectory

        // Traditional shell layout (Termux bootstrap)
        val hasPayloadShell = File(prefix, "bin/bash").exists() || File(prefix, "bin/sh").exists()

        // Android system shell — always available on Android 7+, valid fallback
        val hasSystemShell = File("/system/bin/sh").exists()

        val hasShell = hasPayloadShell || hasSystemShell

        // OpenClaw payload layout (glibc-wrapped node, no bash required)
        val hasNode = File(prefix, "bin/node").exists() ||
            File(prefix, "bin/openclaw").exists() ||
            File(prefix, "lib/node_modules/openclaw/openclaw.mjs").exists()

        return hasBinDir && hasLibDir && hasGlibcLinker && (hasShell || hasNode)
    }
}
