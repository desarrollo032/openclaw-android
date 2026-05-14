package com.openclaw.android

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject

private const val TAG = "OpenClawInstaller"

private const val PAYLOAD_SHA256 = "REPLACE_WITH_ACTUAL_SHA256_BEFORE_RELEASE"
private const val NPM_VERSION = "11.14.1"

object OpenClawInstaller {

    fun getPayloadDir(context: Context): File =
            context.getDir("payload", Context.MODE_PRIVATE).apply { mkdirs() }
    fun getConfigDir(context: Context): File = File(File(context.filesDir, "home"), ".openclaw")

    fun isPayloadReady(context: Context): Boolean {
        val payloadDir = getPayloadDir(context)
        val openclawDir = File(payloadDir, "lib/node_modules/openclaw")
        val nodeLib = File(context.applicationInfo.nativeLibraryDir, "libnode.so")
        
        // Verificación más robusta: el directorio debe existir Y tener contenido
        val payloadExists = openclawDir.exists() && openclawDir.isDirectory()
        val nodeExists = nodeLib.exists() && nodeLib.isFile()
        val npmExists = File(payloadDir, "lib/node_modules/npm/bin/npm-cli.js").exists()

        if (payloadExists && nodeExists && npmExists) {
            ensureRuntimeWrappers(context)
        }
        return payloadExists && nodeExists && npmExists
    }

    fun uninstall(context: Context) {
        getPayloadDir(context).deleteRecursivelySafe()
        getConfigDir(context).deleteRecursivelySafe()
        context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        Log.i(TAG, "Environment uninstalled successfully")
    }

    fun hasBundledAssets(context: Context): Boolean {
        return try {
            context.assets.open(OpenClawConstants.PAYLOAD_ASSET).use { true }
        } catch (_: Exception) {
            false
        }
    }

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

    fun verifyPayloadIntegrity(context: Context): Boolean {
        if (PAYLOAD_SHA256 == "REPLACE_WITH_ACTUAL_SHA256_BEFORE_RELEASE") {
            Log.w(TAG, "SHA-256 verification SKIPPED (dev mode)")
            return true
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8 * 1024)
            context.assets.open(OpenClawConstants.PAYLOAD_ASSET).use { stream ->
                var read: Int
                while (stream.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val computed = digest.digest().joinToString("") { "%02x".format(it) }
            computed.equals(PAYLOAD_SHA256, ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "verifyPayloadIntegrity failed", e)
            false
        }
    }

    private fun ensureNpmPackageInstalled(context: Context, base: File): Boolean {
        val npmCli = File(base, "lib/node_modules/npm/bin/npm-cli.js")
        if (npmCli.exists()) return true

        Log.i(TAG, "npm missing in payload, attempting download from registry")
        return try {
            downloadAndInstallNpmPackage(base)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download/install npm", e)
            false
        }
    }

    private fun downloadAndInstallNpmPackage(base: File): Boolean {
        val tarballUrl = fetchNpmTarballUrl() ?: return false
        val tempDir = File(base, "tmp/npm-bootstrap").apply {
            deleteRecursivelySafe()
            mkdirs()
        }

        if (!downloadAndExtractTarball(tarballUrl, tempDir)) return false

        val packageDir = File(tempDir, "package")
        if (!packageDir.exists() || !packageDir.isDirectory) return false

        val npmTarget = File(base, "lib/node_modules/npm")
        npmTarget.deleteRecursivelySafe()
        packageDir.copyRecursively(npmTarget, true)

        val npmCli = File(npmTarget, "bin/npm-cli.js")
        val ok = npmCli.exists()
        if (!ok) {
            Log.e(TAG, "npm bootstrap failed: ${npmCli.absolutePath} not found")
        }
        tempDir.deleteRecursivelySafe()
        return ok
    }

    private fun fetchNpmTarballUrl(): String? {
        val registryEndpoints = listOf(
                "https://registry.npmjs.org/npm/$NPM_VERSION",
                "https://registry.npmmirror.com/npm/$NPM_VERSION",
                "https://registry.npmjs.org/npm/latest",
                "https://registry.npmmirror.com/npm/latest"
        )
        registryEndpoints.forEach { endpoint ->
            try {
                val conn = URL(endpoint).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) {
                    conn.disconnect()
                    return@forEach
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                val tarball = json.optJSONObject("dist")?.optString("tarball", "")
                if (!tarball.isNullOrBlank()) return tarball
            } catch (e: Exception) {
                Log.w(TAG, "fetchNpmTarballUrl failed for $endpoint: ${e.message}")
            }
        }
        return null
    }

    private fun downloadAndExtractTarball(url: String, outputDir: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) {
                conn.disconnect()
                return false
            }
            conn.inputStream.use { stream ->
                extractTarGzFromStream(stream, outputDir)
            }
            conn.disconnect()
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadAndExtractTarball failed", e)
            false
        }
    }

    suspend fun installDetailed(
            context: Context,
            onProgress: (String) -> Unit,
            onComplete: () -> Unit,
            onError: (String) -> Unit
    ): Unit =
            withContext(Dispatchers.IO) {
                try {
                    val base = getPayloadDir(context)
                    base.deleteRecursivelySafe()
                    base.mkdirs()

                    val ok =
                            context.extractTarXz(OpenClawConstants.PAYLOAD_ASSET, base) {
                                    pct,
                                    read,
                                    total,
                                    currentFile ->
                                val json =
                                        JSONObject()
                                                .apply {
                                                    put("step", 1)
                                                    put("totalSteps", 2)
                                                    put("extractedMB", read / 1024 / 1024)
                                                    put("totalMB", total / 1024 / 1024)
                                                    put("percent", pct)
                                                    put("currentFile", currentFile)
                                                    put("stepName", OpenClawConstants.PAYLOAD_ASSET)
                                                }
                                                .toString()
                                onProgress(json)
                            }
                    if (!ok) throw Exception("Fallo en extracción de ${OpenClawConstants.PAYLOAD_ASSET}")

                    if (!ensureNpmPackageInstalled(context, base)) {
                        throw Exception("npm no incluido y no se pudo descargar")
                    }

                    deployNativeLibs(context, base)
                    deployScripts(context, base)
                    fixPermissions(base)

                    val migrationExists =
                            try {
                                context.assets.open(OpenClawConstants.MIGRATION_ASSET).close()
                                true
                            } catch (_: Exception) {
                                false
                            }

                    if (migrationExists) {
                        val migrationDest =
                                openClawArchiveDestination(
                                        context,
                                        assetContainsOpenClawRoot(context, OpenClawConstants.MIGRATION_ASSET)
                                )
                        context.extractTarGz(OpenClawConstants.MIGRATION_ASSET, migrationDest) {
                                pct,
                                read,
                                total,
                                currentFile ->
                            val json =
                                    JSONObject()
                                            .apply {
                                                put("step", 2)
                                                put("totalSteps", 2)
                                                put("extractedMB", read / 1024 / 1024)
                                                put("totalMB", total / 1024 / 1024)
                                                put("percent", pct)
                                                put("currentFile", currentFile)
                                                put("stepName", OpenClawConstants.MIGRATION_ASSET)
                                            }
                                            .toString()
                            onProgress(json)
                        }
                    }

                    deployScripts(context, base)
                    fixPermissions(base)
                    setupFilesLayout(context)
                    OpenClawTerminalManager(context).createBusyboxSymlinks()

                    context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(OpenClawConstants.KEY_PAYLOAD_INSTALLED, true)
                            .apply()

                    onComplete()
                } catch (e: Exception) {
                    onError(e.message ?: "Error desconocido")
                }
            }

    suspend fun installDetailedFromFiles(
            context: Context,
            payloadFile: File?,
            migrationFile: File?,
            onProgress: (String) -> Unit,
            onComplete: () -> Unit,
            onError: (String) -> Unit
    ): Unit =
            withContext(Dispatchers.IO) {
                try {
                    val base = getPayloadDir(context)
                    base.deleteRecursivelySafe()
                    base.mkdirs()

                    val payloadOk: Boolean
                    if (payloadFile != null) {
                        payloadOk =
                                payloadFile.inputStream().use { raw ->
                                    val tracked =
                                            ProgressInputStream(raw, payloadFile.length()) {
                                                    read,
                                                    total ->
                                                onProgress(
                                                        progressJson(
                                                                1,
                                                                payloadFile.name,
                                                                read,
                                                                total,
                                                                ""
                                                        )
                                                )
                                            }
                                    extractTarXzFromStream(tracked, base)
                                }
                    } else {
                        payloadOk =
                                context.extractTarXz(OpenClawConstants.PAYLOAD_ASSET, base) {
                                        _,
                                        read,
                                        total,
                                        currentFile ->
                                    onProgress(
                                            progressJson(
                                                    1,
                                                    OpenClawConstants.PAYLOAD_ASSET,
                                                    read,
                                                    total,
                                                    currentFile
                                            )
                                    )
                                }
                    }
                    if (!payloadOk)
                            throw Exception(
                                    "Fallo en extraccion de ${payloadFile?.name ?: OpenClawConstants.PAYLOAD_ASSET}"
                            )

                    if (!ensureNpmPackageInstalled(context, base)) {
                        throw Exception("npm no incluido y no se pudo descargar")
                    }

                    deployNativeLibs(context, base)
                    deployScripts(context, base)
                    fixPermissions(base)

                    val migrationOk: Boolean
                    if (migrationFile != null) {
                        val migrationDest =
                                openClawArchiveDestination(
                                        context,
                                        migrationFile.inputStream().use(::tarGzContainsOpenClawRoot)
                                )
                        migrationOk =
                                migrationFile.inputStream().use { raw ->
                                    val tracked =
                                            ProgressInputStream(raw, migrationFile.length()) {
                                                    read,
                                                    total ->
                                                onProgress(
                                                        progressJson(
                                                                2,
                                                                migrationFile.name,
                                                                read,
                                                                total,
                                                                ""
                                                        )
                                                )
                                            }
                                    extractTarGzFromStream(tracked, migrationDest)
                                }
                    } else if (assetExists(context, OpenClawConstants.MIGRATION_ASSET)) {
                        val migrationDest =
                                openClawArchiveDestination(
                                        context,
                                        assetContainsOpenClawRoot(context, OpenClawConstants.MIGRATION_ASSET)
                                )
                        migrationOk =
                                context.extractTarGz(OpenClawConstants.MIGRATION_ASSET, migrationDest) {
                                        _,
                                        read,
                                        total,
                                        currentFile ->
                                    onProgress(
                                            progressJson(
                                                    2,
                                                    OpenClawConstants.MIGRATION_ASSET,
                                                    read,
                                                    total,
                                                    currentFile
                                            )
                                    )
                                }
                    } else {
                        migrationOk = true
                    }
                    if (!migrationOk) {
                        throw Exception(
                                "Fallo en extraccion de ${migrationFile?.name ?: OpenClawConstants.MIGRATION_ASSET}"
                        )
                    }

                    deployScripts(context, base)
                    fixPermissions(base)
                    setupFilesLayout(context)
                    OpenClawTerminalManager(context).createBusyboxSymlinks()

                    context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(OpenClawConstants.KEY_PAYLOAD_INSTALLED, true)
                            .apply()

                    onComplete()
                } catch (e: Exception) {
                    onError(e.message ?: "Error desconocido")
                }
            }

    private fun progressJson(
            step: Int,
            stepName: String,
            read: Long,
            total: Long,
            currentFile: String
    ): String {
        val safeTotal = total.coerceAtLeast(0L)
        val percent = if (total > 0) ((read * 100) / total).toInt().coerceIn(0, 100) else 0
        return JSONObject()
                .apply {
                    put("step", step)
                    put("totalSteps", 2)
                    put("extractedMB", read / 1024 / 1024)
                    put("totalMB", safeTotal / 1024 / 1024)
                    put("percent", percent)
                    put("currentFile", currentFile)
                    put("stepName", stepName)
                }
                .toString()
    }

    private fun assetExists(context: Context, filename: String): Boolean {
        return try {
            context.assets.open(filename).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun assetContainsOpenClawRoot(context: Context, filename: String): Boolean =
            try {
                context.assets.open(filename).use(::tarGzContainsOpenClawRoot)
            } catch (_: Exception) {
                false
            }

    private fun openClawArchiveDestination(context: Context, containsOpenClawRoot: Boolean): File {
        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        return if (containsOpenClawRoot) {
            homeDir
        } else {
            File(homeDir, ".openclaw").apply { mkdirs() }
        }
    }

    private fun tarGzContainsOpenClawRoot(inputStream: InputStream): Boolean =
            try {
                GZIPInputStream(inputStream.buffered(1 shl 16)).use { gzIn ->
                    TarArchiveInputStream(gzIn).use { tarIn ->
                        @Suppress("DEPRECATION") var entry = tarIn.nextTarEntry
                        while (entry != null) {
                            val name = normalizeArchiveEntryRoot(entry.name)
                            if (name == ".openclaw" || name.startsWith(".openclaw/")) return true
                            @Suppress("DEPRECATION")
                            entry = tarIn.nextTarEntry
                        }
                    }
                }
                false
            } catch (_: Exception) {
                false
            }

    private fun normalizeArchiveEntryRoot(rawName: String): String {
        var name = rawName.replace('\\', '/').trim()
        while (name.startsWith("./")) name = name.removePrefix("./")
        while (name.startsWith("/")) name = name.removePrefix("/")
        return name
    }

    suspend fun installPayload(
            context: Context,
            onProgress: (msg: String, pct: Int) -> Unit
    ): Boolean =
            withContext(Dispatchers.IO) {
                val base = getPayloadDir(context)
                base.deleteRecursivelySafe()
                base.mkdirs()
                val ok =
                        context.extractTarXz(OpenClawConstants.PAYLOAD_ASSET, base) { pct, _, _, _ ->
                            onProgress("Extrayendo...", pct)
                        }
                if (ok) {
                    if (!ensureNpmPackageInstalled(context, base)) {
                        throw Exception("npm no incluido y no se pudo descargar")
                    }
                    deployNativeLibs(context, base)
                    deployScripts(context, base)
                    fixPermissions(base)
                    setupFilesLayout(context)
                    context.getSharedPreferences(OpenClawConstants.PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(OpenClawConstants.KEY_PAYLOAD_INSTALLED, true)
                            .apply()
                }
                ok
            }

    suspend fun fixPermissions(base: File) {
        withContext(Dispatchers.IO) {
            if (!base.exists()) return@withContext

            try {
                Log.i("Installer", "Aplicando permisos globales a: ${base.absolutePath}")
                base.walkTopDown().forEach { file ->
                    try {
                        file.setReadable(true, false)
                        file.setWritable(true, false)

                        // Reglas ESTRICTAS para marcar como ejecutable:
                        // 1. Directorios: SI
                        // 2. Archivos en /bin/: SI (pero NO archivos .js/.mjs/.ts/.json)
                        // 3. Archivos .sh: SI (pero NO en /lib/)
                        // 4. CUALQUIER OTRO CASO: NO
                        // 5. ESPECIAL: NUNCA ejecutar archivos con extensiones .js/.mjs/.json/.ts o
                        // en /lib/
                        val isInLibDir = file.path.contains("/lib/")
                        val isJavascriptFile =
                                file.name.endsWith(".js") ||
                                        file.name.endsWith(".mjs") ||
                                        file.name.endsWith(".ts") ||
                                        file.name.endsWith(".json")

                        val isExecutable =
                                file.isDirectory ||
                                        (file.path.contains("/bin/") &&
                                                !isJavascriptFile &&
                                                !isInLibDir) ||
                                        (file.name.endsWith(".sh") &&
                                                !isInLibDir &&
                                                !isJavascriptFile)

                        // NUNCA permitir que archivos .js/.mjs/.ts/.json o en /lib/ sean
                        // ejecutables
                        if (isJavascriptFile || isInLibDir) {
                            file.setExecutable(false, false)
                        } else if (isExecutable) {
                            file.setExecutable(true, false)
                        } else {
                            file.setExecutable(false, false)
                        }

                        file.chmodWithOs()
                    } catch (e: Exception) {
                        Log.w("Installer", "chmod failed on ${file.absolutePath}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("Installer", "Error aplicando chmod global", e)
            }
        }

    }

    fun setupFilesLayout(context: Context) {
        val filesDir = context.filesDir
        val payload = getPayloadDir(context)
        val homeDir = File(filesDir, "home").apply { mkdirs() }

        File(homeDir, ".openclaw/tmp").mkdirs()
        File(filesDir, "usr/bin").mkdirs()
        File(filesDir, "usr/opt").mkdirs()

        mapOf(
                "usr/lib" to File(payload, "lib"),
                "usr/glibc" to File(payload, "glibc"),
                "usr/etc" to File(payload, "etc"),
                "usr/tmp" to context.cacheDir
        ).forEach { (rel, target) -> ensureSymlink(filesDir, rel, target) }
    }

    private fun ensureSymlink(filesDir: File, rel: String, target: File) {
        val link = File(filesDir, rel)
        try {
            link.parentFile?.mkdirs()
            val linkPath = link.toPath()
            if (Files.isSymbolicLink(linkPath)) {
                val current = runCatching { Files.readSymbolicLink(linkPath).toString() }.getOrNull()
                if (current == target.absolutePath) return
                Files.deleteIfExists(linkPath)
            } else if (link.exists()) {
                link.deleteRecursivelySafe()
            }
            Os.symlink(target.absolutePath, link.absolutePath)
        } catch (e: Exception) {
            Log.w(
                    TAG,
                    "No se pudo crear symlink ${link.absolutePath} -> ${target.absolutePath}: ${e.message}"
            )
        }
    }

    fun ensureRuntimeWrappers(context: Context) {
        val base = getPayloadDir(context)
        val primaryOpenClaw = File(context.filesDir, "usr/bin/openclaw")
        val legacyWrappers =
                listOf(
                        File(context.filesDir, "app_payload/bin/node"),
                        File(context.filesDir, "app_payload/bin/openclaw"),
                        File(context.dataDir, "app_payload/bin/node"),
                        File(context.dataDir, "app_payload/bin/openclaw")
                )

        val primaryNeedsRepair =
                !primaryOpenClaw.exists() ||
                        !safeRead(primaryOpenClaw).contains("OPENCLAW_NO_RESPAWN=1") ||
                        !safeRead(primaryOpenClaw).contains("--disable-warning=ExperimentalWarning")
        val legacyNeedsRepair =
                legacyWrappers.any { !it.exists() || safeRead(it).contains("app_payload/bin/node") }

        if (primaryNeedsRepair || legacyNeedsRepair) {
            deployScripts(context, base)
        } else {
            ensureLegacyWrapperPermissions(context)
        }
    }

    private fun safeRead(file: File): String =
            try {
                file.readText()
            } catch (_: Exception) {
                ""
            }

    fun deployNativeLibs(context: Context, base: File) {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val payloadBinDir = File(base, "bin")
        val payloadGlibcLibDir = File(base, "glibc/lib")

        // Map of source files (in payload) to destination files (in nativeLibraryDir)
        val libsToCopy =
                mapOf(
                        selectFirstExisting(
                                File(payloadBinDir, "node.real"),
                                File(nativeDir, "libnode.so")
                        ) to File(nativeDir, "libnode.so"),
                        File(payloadGlibcLibDir, "ld-linux-aarch64.so.1") to
                                File(nativeDir, "libldlinux.so"),
                        File(payloadBinDir, "busybox.real") to File(nativeDir, "libbusybox.so")
                )

        libsToCopy.forEach { (src, dst) ->
            try {
                if (src.exists()) {
                    Log.i(TAG, "Copying ${src.name} to ${dst.absolutePath}")
                    src.inputStream().use { input ->
                        dst.outputStream().use { output -> input.copyTo(output) }
                    }
                    // Set read/execute permissions for the native libraries
                    dst.setReadable(true, false)
                    dst.setExecutable(true, false)
                    dst.chmodWithOs()
                } else {
                    Log.w(TAG, "Source library not found: ${src.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy ${src.name}: ${e.message}", e)
            }
        }
    }

    private fun selectFirstExisting(vararg candidates: File?): File {
        return candidates.firstOrNull { it?.exists() == true }
                ?: candidates.firstOrNull()
                ?: File("")
    }

    fun deployScripts(context: Context, base: File) {
        val binDir = File(context.filesDir, "usr/bin")
        if (!binDir.exists()) binDir.mkdirs()
        val legacyBinDirs = listOf(
                File(context.filesDir, "app_payload/bin"),
                File(context.dataDir, "app_payload/bin")
        )
        legacyBinDirs.forEach { if (!it.exists()) it.mkdirs() }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val linker = "$nativeDir/libldlinux.so"
        val nodeLib = "$nativeDir/libnode.so"
        val glibcLib = "$base/glibc/lib"
        val nodeModules = "$base/lib/node_modules"
        val openclawHome = File(context.filesDir, "home/.openclaw").absolutePath
        val tmpDir = "$openclawHome/tmp"
        val ocPathFull = "$base/lib/node_modules/openclaw/openclaw.mjs"
        val npmPathFull = "$base/lib/node_modules/npm/bin/npm-cli.js"

        // Crear wrapper para 'node' (Rutas absolutas inyectadas)
        val nodeWrapper = File(binDir, "node")
        if (nodeWrapper.exists()) nodeWrapper.delete()
        nodeWrapper.writeText(
                """
            #!/system/bin/sh
            # Rutas inyectadas (no derivadas para evitar problemas con $0 en exec)
            LINKER="$linker"
            NODE_LIB="$nodeLib"
            LIBS="$nativeDir:$glibcLib"

            unset LD_PRELOAD
            unset NODE_OPTIONS
            export NODE_NO_WARNINGS=1
            export OPENCLAW_NO_RESPAWN=1
            export OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED=1
            export OPENCLAW_SOURCE_COMPILE_CACHE_RESPAWNED=1
            export LD_LIBRARY_PATH="${'$'}LIBS"
            export NODE_PATH="$nodeModules"
            exec "${'$'}LINKER" --library-path "${'$'}LIBS" "${'$'}NODE_LIB" "${'$'}@"
        """.trimIndent()
        )
        nodeWrapper.chmodWithOs()

        // Crear wrapper para 'openclaw' (Rutas absolutas inyectadas)
        val openClawWrapper = File(binDir, "openclaw")
        if (openClawWrapper.exists()) openClawWrapper.delete()
        openClawWrapper.writeText(
                """
            #!/system/bin/sh
            # Rutas inyectadas
            NODE_BIN="$nodeLib"
            OPENCLAW_SCRIPT="$ocPathFull"
            LINKER="$linker"
            LIBS="$nativeDir:$glibcLib"

            unset LD_PRELOAD
            unset NODE_OPTIONS
            export NODE_NO_WARNINGS=1
            export OPENCLAW_NO_RESPAWN=1
            export OPENCLAW_PACKAGED_COMPILE_CACHE_RESPAWNED=1
            export OPENCLAW_SOURCE_COMPILE_CACHE_RESPAWNED=1
            export LD_LIBRARY_PATH="${'$'}LIBS"
            export NODE_PATH="$nodeModules"
            export OPENCLAW_HOME="$openclawHome"
            export TMPDIR="$tmpDir"
            exec "${'$'}LINKER" --library-path "${'$'}LIBS" "${'$'}NODE_BIN" --disable-warning=ExperimentalWarning "${'$'}OPENCLAW_SCRIPT" "${'$'}@"
        """.trimIndent()
        )
        openClawWrapper.chmodWithOs()

        // Crear wrapper para 'npm' (Rutas absolutas inyectadas)
        val npmWrapper = File(binDir, "npm")
        if (npmWrapper.exists()) npmWrapper.delete()
        npmWrapper.writeText(
                """
            #!/system/bin/sh
            # Rutas inyectadas
            NODE_BIN="$nodeLib"
            NPM_CLI="$npmPathFull"
            LINKER="$linker"
            LIBS="$nativeDir:$glibcLib"

            if [ ! -f "${'$'}NPM_CLI" ]; then
                echo "npm: no incluido"
                exit 127
            fi
            unset LD_PRELOAD
            unset NODE_OPTIONS
            export NODE_NO_WARNINGS=1
            export LD_LIBRARY_PATH="${'$'}LIBS"
            export NODE_PATH="$nodeModules"
            exec "${'$'}LINKER" --library-path "${'$'}LIBS" "${'$'}NODE_BIN" "${'$'}NPM_CLI" "${'$'}@"
        """.trimIndent()
        )
        npmWrapper.chmodWithOs()

        // Crear wrapper para 'pnpm' si se instala en el payload
        val pnpmWrapper = File(binDir, "pnpm")
        if (pnpmWrapper.exists()) pnpmWrapper.delete()
        pnpmWrapper.writeText(
                """
            #!/system/bin/sh
            # Rutas inyectadas
            NODE_BIN="$nodeLib"
            PNPM_CLI="$base/lib/node_modules/pnpm/bin/pnpm.cjs"
            LINKER="$linker"
            LIBS="$nativeDir:$glibcLib"

            if [ ! -f "${'$'}PNPM_CLI" ]; then
                echo "pnpm: no incluido"
                exit 127
            fi
            unset LD_PRELOAD
            unset NODE_OPTIONS
            export NODE_NO_WARNINGS=1
            export LD_LIBRARY_PATH="${'$'}LIBS"
            export NODE_PATH="$nodeModules"
            exec "${'$'}LINKER" --library-path "${'$'}LIBS" "${'$'}NODE_BIN" "${'$'}PNPM_CLI" "${'$'}@"
        """.trimIndent()
        )
        pnpmWrapper.chmodWithOs()

        // Compatibilidad con instalaciones antiguas que invocan wrappers legacy:
        // - /data/user/0/<pkg>/files/app_payload/bin/*
        // - /data/user/0/<pkg>/app_payload/bin/*
        legacyBinDirs.forEach { legacyBinDir ->
            val legacyNodeWrapper = File(legacyBinDir, "node")
            legacyNodeWrapper.writeText(
                    """
                #!/system/bin/sh
                exec /system/bin/sh "${nodeWrapper.absolutePath}" "${'$'}@"
            """.trimIndent()
            )
            legacyNodeWrapper.chmodWithOs()

            val legacyOpenClawWrapper = File(legacyBinDir, "openclaw")
            legacyOpenClawWrapper.writeText(
                    """
                #!/system/bin/sh
                exec /system/bin/sh "${openClawWrapper.absolutePath}" "${'$'}@"
            """.trimIndent()
            )
            legacyOpenClawWrapper.chmodWithOs()
        }
        ensureLegacyWrapperPermissions(context)

        // Crear .mkshrc para alias automáticos en el terminal
        val mkshrc = File(base, ".mkshrc")
        mkshrc.writeText(
                """
            node() { sh "${File(binDir, "node").absolutePath}" "${'$'}@"; }
            npm() { sh "${File(binDir, "npm").absolutePath}" "${'$'}@"; }
            pnpm() { sh "${File(binDir, "pnpm").absolutePath}" "${'$'}@"; }
            openclaw() { sh "${File(binDir, "openclaw").absolutePath}" "${'$'}@"; }
            export PATH=${binDir.absolutePath}:${'$'}PATH
        """.trimIndent()
        )

        try {
            context.assets.list("scripts")?.forEach { name ->
                context.assets.open("scripts/$name").use { input ->
                    File(binDir, name).outputStream().use { input.copyTo(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy assets/scripts", e)
        }
    }

    fun ensureLegacyWrapperPermissions(context: Context) {
        val legacyRoots = listOf(File(context.filesDir, "app_payload"), File(context.dataDir, "app_payload"))

        legacyRoots.forEach { root ->
            val binDir = File(root, "bin")
            val wrappers = listOf(File(binDir, "node"), File(binDir, "openclaw"))

            listOf(root, binDir).forEach { dir ->
                if (dir.exists()) {
                    dir.setReadable(true, false)
                    dir.setWritable(true, true)
                    dir.setExecutable(true, false)
                    dir.chmodWithOs(493)
                }
            }

            wrappers.forEach { file ->
                if (file.exists()) {
                    file.setReadable(true, false)
                    file.setWritable(true, true)
                    file.setExecutable(true, false)
                    file.chmodWithOs(493)
                }
            }
        }
    }

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
}
