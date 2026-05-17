package com.openclaw.android.proot

import android.util.Log as AndroidLog
import java.io.File

/**
 * InstallPhaseTracker — Gestión de fases de instalación completadas.
 *
 * Cada fase de installOpenClaw escribe un marker file en
 * `rootfs/root/.openclaw-install/<key>.done`. Este tracker lee y escribe
 * esos markers para soportar instalación resumible.
 *
 * Claves de fase:
 *   arch, apk_repos, apk_update, sys_deps, nodejs, npm,
 *   pnpm, pnpm_env, versions, openclaw, onboard, verify
 */
class InstallPhaseTracker(private val rootfs: File) {

    companion object {
        const val TAG = "PhaseTracker"
        private const val MARKER_DIR_PATH = "root/.openclaw-install"

        /**
         * Fases que se pueden saltar de forma segura desde la UI sin romper
         * el resto del flujo de instalación.
         */
        val SKIPPABLE_PHASES = setOf("pnpm", "pnpm_env", "versions", "sys_deps")

        /**
         * Fases criticas que el usuario PUEDE saltar de forma manual cuando
         * todo falla, pero hacerlo implica que OpenClaw quedara incompleto y
         * habra que instalar manualmente desde el dashboard o el terminal.
         */
        val CRITICAL_SKIPPABLE_PHASES = setOf("nodejs", "npm", "openclaw", "onboard", "verify")
    }

    private val markerDir: File get() = File(rootfs, MARKER_DIR_PATH)

    /**
     * Detecta qué fases de la instalación están completadas inspeccionando
     * el rootfs y los marker files. Se usa al cargar la UI para reanudar
     * sin tener que re-ejecutar el script entero.
     */
    fun getCompletedPhases(): Set<String> {
        val phases = mutableSetOf<String>()

        if (!isRootfsPresent()) return phases

        // Leer marker files
        if (markerDir.isDirectory) {
            markerDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".done") }
                ?.forEach { phases += it.name.removeSuffix(".done") }
        }

        // Detección de fallback por presencia de binarios
        if (File(rootfs, "usr/local/bin/node").exists() ||
            File(rootfs, "usr/local/node/bin/node").exists() ||
            File(rootfs, "usr/bin/node").exists()) {
            phases += "nodejs"
        }
        if (File(rootfs, "usr/local/bin/npm").exists() ||
            File(rootfs, "usr/bin/npm").exists() ||
            File(rootfs, "usr/bin/npm.cmd").exists()) {
            phases += "npm"
        }
        if (File(rootfs, "usr/lib/node_modules/pnpm").exists() ||
            File(rootfs, "root/.local/share/pnpm/pnpm").exists()) {
            phases += "pnpm"
        }

        val openclawPaths = listOf(
            "usr/local/lib/node_modules/openclaw/openclaw.mjs",
            "usr/lib/node_modules/openclaw/openclaw.mjs"
        )
        if (openclawPaths.any { File(rootfs, it).exists() }) {
            phases += "openclaw"
        }

        return phases
    }

    /**
     * Marca una fase como completada manualmente (skip). Crea el marker file
     * en el rootfs para que el script de instalación la salte al reintentar.
     */
    fun markPhaseSkipped(phaseKey: String) {
        markerDir.mkdirs()
        val marker = File(markerDir, "$phaseKey.done")
        if (!marker.exists()) {
            marker.writeText("skipped_by_user\n")
            AndroidLog.i(TAG, "Phase '$phaseKey' marked as skipped by user")
        }
    }

    /**
     * Verifica si una fase se puede saltar de forma segura.
     */
    fun isSkippable(phaseKey: String): Boolean = phaseKey in SKIPPABLE_PHASES

    fun isCriticalSkippable(phaseKey: String): Boolean = phaseKey in CRITICAL_SKIPPABLE_PHASES

    private fun isRootfsPresent(): Boolean =
        File(rootfs, "bin/sh").exists() &&
        File(rootfs, "etc").exists()
}
