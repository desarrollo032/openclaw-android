package com.openclaw.android

import android.content.Context
import java.io.File

/**
 * SetupManager — orquesta la instalación completa de OpenClaw usando proot.
 *
 * Flujo:
 *   1. Descargar proot binario (~1.5MB)
 *   2. Descargar Ubuntu rootfs (~80MB)
 *   3. Dentro de proot: apt update + apt install nodejs npm
 *   4. Dentro de proot: npm install -g openclaw@latest
 *   5. Crear wrapper script en app-home para lanzar el gateway
 *   6. Escribir marcador de instalación
 *
 * Por qué esto funciona donde el enfoque anterior fallaba:
 *   - proot es un proceso nativo de la app (no hijo de bash)
 *   - Corre desde OpenClawService (foreground service) → Android no lo mata
 *   - Node.js corre dentro de Ubuntu (glibc nativo) → sin hacks de glibc/Bionic
 *   - npm install corre dentro de proot → sin Phantom Process Killer
 */
class SetupManager(private val context: Context) {

    private val TAG = "SetupManager"

    // Marcador de instalación completa
    private val markerFile = File(context.filesDir, ".proot-installed")
    private val openclawMarker = File(context.filesDir, "home/.openclaw-android/installed.json")

    interface ProgressListener {
        fun onProgress(percent: Int, message: String)
        fun onSuccess()
        fun onError(message: String, cause: Throwable? = null)
    }

    fun isInstalled(): Boolean {
        return markerFile.exists() &&
            ProotManager.isProotReady(context) &&
            ProotManager.isRootfsReady(context) &&
            isOpenClawInstalledInRootfs()
    }

    fun isOpenClawInstalledInRootfs(): Boolean {
        val paths = ProotManager.getPaths(context)
        // OpenClaw instalado via npm global dentro del rootfs Ubuntu
        return paths.rootfsDir.resolve("usr/local/lib/node_modules/openclaw/openclaw.mjs").exists() ||
            paths.rootfsDir.resolve("usr/local/bin/openclaw").exists()
    }

    /**
     * Punto de entrada principal. Ejecutar en Dispatchers.IO.
     */
    fun install(listener: ProgressListener) {
        try {
            AppLogger.i(TAG, "Starting proot-based installation")

            // ── Paso 1: Descargar proot ──────────────────────────────────────
            if (!ProotManager.isProotReady(context)) {
                listener.onProgress(1, "Descargando proot (binario nativo)...")
                val ok = ProotManager.downloadProot(context) { pct, msg ->
                    listener.onProgress(pct, msg)
                }
                if (!ok) {
                    listener.onError("No se pudo descargar proot. Verifica tu conexión a internet.")
                    return
                }
            } else {
                listener.onProgress(8, "proot ya disponible")
            }

            // ── Paso 2: Descargar y extraer rootfs Ubuntu ────────────────────
            if (!ProotManager.isRootfsReady(context)) {
                listener.onProgress(9, "Descargando Ubuntu rootfs (~80MB)...")
                val ok = ProotManager.downloadAndExtractRootfs(context) { pct, msg ->
                    listener.onProgress(pct, msg)
                }
                if (!ok) {
                    listener.onError("No se pudo descargar el rootfs Ubuntu. Verifica tu conexión.")
                    return
                }
            } else {
                listener.onProgress(80, "Rootfs Ubuntu ya disponible")
            }

            // ── Paso 3: Configurar Ubuntu dentro de proot ────────────────────
            listener.onProgress(81, "Configurando Ubuntu (primera vez)...")
            val setupOk = runUbuntuSetup(listener)
            if (!setupOk) {
                listener.onError("Error configurando Ubuntu. Revisa los logs.")
                return
            }

            // ── Paso 4: Instalar Node.js dentro de proot ─────────────────────
            listener.onProgress(83, "Instalando Node.js 22 en Ubuntu...")
            val nodeOk = installNodeInProot(listener)
            if (!nodeOk) {
                listener.onError("Error instalando Node.js. Verifica tu conexión.")
                return
            }

            // ── Paso 5: Instalar OpenClaw via npm ────────────────────────────
            listener.onProgress(88, "Instalando OpenClaw (npm install -g openclaw)...")
            val openclawOk = installOpenClawInProot(listener)
            if (!openclawOk) {
                listener.onError("Error instalando OpenClaw via npm.")
                return
            }

            // ── Paso 6: Crear scripts de lanzamiento ─────────────────────────
            listener.onProgress(96, "Creando scripts de lanzamiento...")
            createLaunchScripts()

            // ── Paso 7: Escribir marcadores ──────────────────────────────────
            writeMarkers()

            listener.onProgress(100, "¡Instalación completada!")
            listener.onSuccess()

        } catch (e: Exception) {
            AppLogger.e(TAG, "SetupManager.install failed: ${e.message}", e)
            listener.onError("Error inesperado: ${e.message}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pasos internos
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Configuración inicial de Ubuntu: actualizar apt, instalar dependencias base.
     * Corre dentro de proot — sin Phantom Process Killer.
     */
    private fun runUbuntuSetup(listener: ProgressListener): Boolean {
        val setupScript = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            echo "[setup] Actualizando listas de paquetes..."
            apt-get update -qq 2>&1 || apt-get update 2>&1 || true

            echo "[setup] Instalando dependencias base..."
            apt-get install -y --no-install-recommends \
                ca-certificates \
                curl \
                wget \
                gnupg \
                lsb-release \
                2>&1 || true

            echo "[setup] Ubuntu configurado OK"
        """.trimIndent()

        var lastLine = ""
        val exitCode = ProotManager.runInProot(context, setupScript) { line ->
            lastLine = line
            AppLogger.d(TAG, "[ubuntu-setup] $line")
            if (line.contains("[setup]")) {
                listener.onProgress(82, line.removePrefix("[setup] "))
            }
        }

        return exitCode == 0 || lastLine.contains("OK")
    }

    /**
     * Instala Node.js 22 LTS dentro del rootfs Ubuntu via NodeSource.
     * Usa el script oficial de NodeSource — el más confiable.
     */
    private fun installNodeInProot(listener: ProgressListener): Boolean {
        val paths = ProotManager.getPaths(context)

        // Verificar si Node.js ya está instalado en el rootfs
        if (paths.rootfsDir.resolve("usr/local/bin/node").exists() ||
            paths.rootfsDir.resolve("usr/bin/node").exists()
        ) {
            AppLogger.i(TAG, "Node.js already installed in rootfs")
            listener.onProgress(87, "Node.js ya instalado")
            return true
        }

        // Estrategia 1: NodeSource (más actualizado, Node 22 LTS)
        val nodeSourceScript = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            echo "[node] Configurando repositorio NodeSource (Node.js 22 LTS)..."
            curl -fsSL https://deb.nodesource.com/setup_22.x | bash - 2>&1

            echo "[node] Instalando Node.js 22..."
            apt-get install -y nodejs 2>&1

            echo "[node] Verificando..."
            node --version 2>&1
            npm --version 2>&1
            echo "[node] Node.js instalado OK"
        """.trimIndent()

        var nodeInstalled = false
        val exitCode = ProotManager.runInProot(context, nodeSourceScript) { line ->
            AppLogger.d(TAG, "[node-install] $line")
            if (line.contains("[node]")) {
                listener.onProgress(85, line.removePrefix("[node] "))
            }
            if (line.contains("v22.") || line.contains("v20.") || line.contains("OK")) {
                nodeInstalled = true
            }
        }

        if (exitCode == 0 || nodeInstalled) return true

        // Estrategia 2: Descargar binario oficial de nodejs.org directamente
        AppLogger.w(TAG, "NodeSource failed, trying direct binary download...")
        listener.onProgress(84, "Descargando Node.js binario oficial...")

        val directScript = """
            set -e
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            NODE_VERSION="v22.13.1"
            NODE_DIR="/usr/local"

            echo "[node] Descargando Node.js ${'$'}NODE_VERSION para arm64..."
            curl -fsSL "https://nodejs.org/dist/${'$'}NODE_VERSION/node-${'$'}NODE_VERSION-linux-arm64.tar.gz" \
                -o /tmp/node.tar.gz 2>&1

            echo "[node] Extrayendo..."
            tar -xzf /tmp/node.tar.gz -C "${'$'}NODE_DIR" --strip-components=1 2>&1
            rm -f /tmp/node.tar.gz

            echo "[node] Verificando..."
            node --version 2>&1
            npm --version 2>&1
            echo "[node] Node.js instalado OK"
        """.trimIndent()

        var nodeOk2 = false
        val exitCode2 = ProotManager.runInProot(context, directScript) { line ->
            AppLogger.d(TAG, "[node-direct] $line")
            if (line.contains("[node]")) {
                listener.onProgress(86, line.removePrefix("[node] "))
            }
            if (line.contains("v22.") || line.contains("OK")) nodeOk2 = true
        }

        return exitCode2 == 0 || nodeOk2
    }

    /**
     * Instala OpenClaw via npm dentro del rootfs Ubuntu.
     * npm corre dentro de proot → sin Phantom Process Killer.
     */
    private fun installOpenClawInProot(listener: ProgressListener): Boolean {
        val installScript = """
            set -e
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export NODE_PATH=/usr/local/lib/node_modules
            export npm_config_cache=/tmp/npm-cache

            echo "[openclaw] Verificando npm..."
            npm --version 2>&1

            echo "[openclaw] Instalando OpenClaw (puede tardar 2-3 min)..."
            npm install -g openclaw@latest --ignore-scripts --no-fund --no-audit 2>&1

            echo "[openclaw] Verificando instalación..."
            if [ -f /usr/local/lib/node_modules/openclaw/openclaw.mjs ]; then
                echo "[openclaw] openclaw.mjs encontrado OK"
            elif [ -f /usr/local/bin/openclaw ]; then
                echo "[openclaw] openclaw binary encontrado OK"
            else
                echo "[openclaw] ERROR: openclaw no encontrado después de npm install"
                exit 1
            fi

            echo "[openclaw] Instalación completada"
        """.trimIndent()

        var openclawOk = false
        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts && !openclawOk) {
            attempt++
            listener.onProgress(88 + attempt, "Instalando OpenClaw (intento $attempt/$maxAttempts)...")

            val exitCode = ProotManager.runInProot(context, installScript) { line ->
                AppLogger.d(TAG, "[openclaw-install] $line")
                if (line.contains("[openclaw]")) {
                    listener.onProgress(89 + attempt, line.removePrefix("[openclaw] "))
                }
                if (line.contains("OK") || line.contains("completada")) {
                    openclawOk = true
                }
            }

            if (exitCode == 0 || openclawOk) {
                openclawOk = true
                break
            }

            if (attempt < maxAttempts) {
                AppLogger.w(TAG, "npm install attempt $attempt failed, retrying in 5s...")
                listener.onProgress(89, "Reintentando en 5 segundos...")
                Thread.sleep(5_000)
                // Limpiar caché npm antes de reintentar
                ProotManager.runInProot(context, "rm -rf /tmp/npm-cache 2>/dev/null; true") {}
            }
        }

        return openclawOk || isOpenClawInstalledInRootfs()
    }

    /**
     * Crea el script de lanzamiento del gateway en el home de la app.
     * Este script es llamado por JsBridge.launchGateway() y TerminalManager.
     */
    private fun createLaunchScripts() {
        val homeDir = File(context.filesDir, "home")
        homeDir.mkdirs()

        val ocaDir = File(homeDir, ".openclaw-android")
        ocaDir.mkdirs()

        val paths = ProotManager.getPaths(context)
        val prootBin = paths.prootBin.absolutePath
        val rootfsDir = paths.rootfsDir.absolutePath
        val appHomeDir = homeDir.absolutePath
        val cacheDir = context.cacheDir.absolutePath

        // Script principal de lanzamiento del gateway
        val gatewayScript = File(homeDir, "openclaw-start.sh")
        gatewayScript.writeText(
            """
            #!/system/bin/sh
            # OpenClaw gateway launcher — proot edition
            # Generado por SetupManager

            PROOT="$prootBin"
            ROOTFS="$rootfsDir"
            APP_HOME="$appHomeDir"
            TMPDIR="$cacheDir"

            export PROOT_NO_SECCOMP=1
            export PROOT_TMP_DIR="${'$'}TMPDIR"
            export HOME="${'$'}APP_HOME"
            export TMPDIR="${'$'}TMPDIR"

            exec "${'$'}PROOT" \
                --rootfs="${'$'}ROOTFS" \
                -0 \
                -w=/root \
                --kill-on-exit \
                --link2symlink \
                --sysvipc \
                --bind=/proc \
                --bind=/dev \
                --bind=/sys \
                --bind=/dev/urandom:/dev/random \
                --bind="${'$'}APP_HOME":/mnt/app-home \
                /bin/sh -c '
                    export HOME=/root
                    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                    export NODE_PATH=/usr/local/lib/node_modules
                    export OA_GLIBC=0
                    export CONTAINER=1
                    export CLAWDHUB_WORKDIR=/root/.openclaw/workspace
                    mkdir -p /root/.openclaw/workspace
                    exec node /usr/local/lib/node_modules/openclaw/openclaw.mjs gateway --host 0.0.0.0
                '
            """.trimIndent(),
        )
        gatewayScript.setExecutable(true, false)

        // Script de shell interactivo (para el terminal de la app)
        val shellScript = File(homeDir, "openclaw-shell.sh")
        shellScript.writeText(
            """
            #!/system/bin/sh
            # Shell interactivo dentro del entorno Ubuntu/OpenClaw

            PROOT="$prootBin"
            ROOTFS="$rootfsDir"
            APP_HOME="$appHomeDir"
            TMPDIR="$cacheDir"

            export PROOT_NO_SECCOMP=1
            export PROOT_TMP_DIR="${'$'}TMPDIR"
            export HOME="${'$'}APP_HOME"

            exec "${'$'}PROOT" \
                --rootfs="${'$'}ROOTFS" \
                -0 \
                -w=/root \
                --kill-on-exit \
                --link2symlink \
                --sysvipc \
                --bind=/proc \
                --bind=/dev \
                --bind=/sys \
                --bind=/dev/urandom:/dev/random \
                --bind="${'$'}APP_HOME":/mnt/app-home \
                /bin/bash --login
            """.trimIndent(),
        )
        shellScript.setExecutable(true, false)

        AppLogger.i(TAG, "Launch scripts created: ${gatewayScript.absolutePath}")
    }

    private fun writeMarkers() {
        // Marcador interno de la app
        markerFile.writeText("proot-installed\n")

        // Marcador compatible con el sistema existente
        val ocaDir = File(context.filesDir, "home/.openclaw-android")
        ocaDir.mkdirs()
        openclawMarker.writeText(
            """
            {
              "installed": true,
              "source": "proot-setup-manager",
              "version": "proot",
              "installedAt": "${System.currentTimeMillis()}"
            }
            """.trimIndent(),
        )

        // Marcador .installed para InstallerManager.isInstalled()
        File(context.filesDir, ".installed").writeText("proot\n")

        AppLogger.i(TAG, "Installation markers written")
    }

    /**
     * Elimina toda la instalación para empezar de cero.
     * Útil para recuperación de errores.
     */
    fun reset() {
        markerFile.delete()
        openclawMarker.delete()
        File(context.filesDir, ".installed").delete()
        ProotManager.getPaths(context).rootfsDir.deleteRecursively()
        AppLogger.i(TAG, "Installation reset complete")
    }

    fun getStatus(): Map<String, Any> {
        val paths = ProotManager.getPaths(context)
        return mapOf(
            "installed" to isInstalled(),
            "prootReady" to ProotManager.isProotReady(context),
            "rootfsReady" to ProotManager.isRootfsReady(context),
            "openclawReady" to isOpenClawInstalledInRootfs(),
            "prootPath" to paths.prootBin.absolutePath,
            "rootfsPath" to paths.rootfsDir.absolutePath,
            "rootfsSizeMB" to (paths.rootfsDir.walkTopDown().sumOf { it.length() } / 1024 / 1024),
        )
    }
}
