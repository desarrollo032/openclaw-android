import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Lazy version resolution (configuration cache safe) ─────────────────────────

// Fallback versioning with defaults
val defaultVersionCode = 1
val defaultVersionName = "0.0-dev"

// Load or create version.properties
val versionPropsFile = file("${rootProject.projectDir}/version.properties")
val versionProps =
        Properties().apply {
            if (versionPropsFile.exists()) {
                load(versionPropsFile.inputStream())
            } else {
                // Create defaults if missing
                setProperty("VERSION_CODE", defaultVersionCode.toString())
                setProperty("VERSION_NAME", defaultVersionName)
            }
        }

val gitVersionCode: Int =
        versionProps.getProperty("VERSION_CODE", defaultVersionCode.toString()).toIntOrNull()
                ?: defaultVersionCode
val gitVersionName: String = versionProps.getProperty("VERSION_NAME", defaultVersionName)

// ── Git version update task (optional, manual trigger) ──────────────────────────

tasks.register("updateVersionFromGit") {
    description = "Update version.properties from git (manual task, not part of build cache)"
    group = "build"

    doLast {
        val processGit = { args: Array<String> ->
            try {
                ProcessBuilder("git", *args)
                        .directory(rootProject.projectDir)
                        .redirectErrorStream(true)
                        .start()
                        .inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
            } catch (e: Exception) {
                ""
            }
        }

        val gitVersionCodeNew =
                processGit(arrayOf("rev-list", "--count", "HEAD")).toIntOrNull() ?: 1
        val gitVersionNameNew = run {
            val raw = processGit(arrayOf("describe", "--tags", "--always", "--dirty=-dev"))
            if (raw.isEmpty()) {
                "0.0-${processGit(arrayOf("rev-parse", "--short", "HEAD")).ifEmpty { "unknown" }}"
            } else if (raw.first().isDigit() || raw.startsWith("v")) {
                raw
            } else {
                "0.0-$raw"
            }
        }

        versionPropsFile.writeText(
                """
            # Auto-generated version properties from git
            # Run 'gradle updateVersionFromGit' to update
            VERSION_CODE=$gitVersionCodeNew
            VERSION_NAME=$gitVersionNameNew
        """.trimIndent()
        )

        println("✓ version.properties updated: code=$gitVersionCodeNew name=$gitVersionNameNew")
    }
}

// ── proot binary download (libproot.so → jniLibs/arm64-v8a/) ───────────────────
//
// Reemplazo del antiguo payload glibc (libnode.so + libldlinux.so + libglibc*.so).
// libproot.so es un ELF estático ARM64 (~500 KB) tomado del proyecto Termux,
// y se carga en runtime para extraer y ejecutar el rootfs Alpine.
//
// Fuente: https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot
// Tamaño esperado: ~500 KB. Para builds offline, deposita el archivo manualmente
// en app/src/main/jniLibs/arm64-v8a/libproot.so.

val prootBinaryUrl =
        "https://skirsten.github.io/proot-portable-android-binaries/aarch64/proot"
val prootBinaryDest =
        file("${projectDir}/src/main/jniLibs/arm64-v8a/libproot.so")

tasks.register("fetchProot") {
    description = "Descarga libproot.so (proot estático ARM64) a jniLibs/arm64-v8a/"
    group = "build"

    outputs.file(prootBinaryDest)

    doLast {
        if (prootBinaryDest.exists() && prootBinaryDest.length() > 0L) {
            println("✓ libproot.so ya existe (${prootBinaryDest.length()} bytes), skip")
            return@doLast
        }
        prootBinaryDest.parentFile?.mkdirs()
        println("⏬ Descargando libproot.so desde $prootBinaryUrl…")
        try {
            java.net.URL(prootBinaryUrl).openStream().use { input ->
                prootBinaryDest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            prootBinaryDest.setExecutable(true, false)
            println("✓ libproot.so descargado (${prootBinaryDest.length()} bytes)")
        } catch (e: Exception) {
            throw GradleException(
                "Falló la descarga de libproot.so: ${e.message}\n" +
                "Solución alternativa: coloca el binario manualmente en " +
                "${prootBinaryDest.absolutePath}"
            )
        }
    }
}

// ── Web UI auto-build task ────────────────────────────────────────────────────

val wwwSrcDir = file("${rootProject.projectDir}/www")
val wwwDistDir = file("${wwwSrcDir}/dist")
val wwwAssetsDir = file("${projectDir}/src/main/assets/www")

tasks.register<Exec>("buildWebUI") {
    description = "Build the React web UI and copy dist to Android assets"
    group = "build"

    workingDir = wwwSrcDir

    // Determine shell based on OS at execution time
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) {
        commandLine("cmd", "/c", "npm", "run", "build")
    } else {
        commandLine("npm", "run", "build")
    }

    // Only re-run if sources changed
    inputs.dir(file("${wwwSrcDir}/src"))
    inputs.file(file("${wwwSrcDir}/package.json"))
    inputs.file(file("${wwwSrcDir}/vite.config.ts"))
    outputs.dir(wwwDistDir)

    // Verifica que el build produjo un index.html. Si no, el APK terminaría
    // empaquetando un assets/www/ inconsistente, así que falla rápido.
    doLast {
        val indexHtml = file("${wwwDistDir}/index.html")
        if (!indexHtml.exists()) {
            throw GradleException(
                "buildWebUI: ${indexHtml} no existe tras `npm run build`. " +
                "Revisa los logs de Vite/TSC."
            )
        }
    }

    // Post-build copy task (registered separately to avoid serialization issues)
    finalizedBy("copyWebUIAssets")
}

tasks.register<Copy>("copyWebUIAssets") {
    description = "Copy built web UI to Android assets"
    // Limpia los assets/www anteriores antes de copiar el nuevo dist —
    // evita chunks JS huérfanos (hashes antiguos) acumulándose en el APK.
    doFirst {
        if (wwwAssetsDir.exists()) {
            wwwAssetsDir.deleteRecursively()
        }
        wwwAssetsDir.mkdirs()
    }
    from(wwwDistDir)
    into(wwwAssetsDir)
    doLast { println("✓ Web UI copied to assets/www") }
}

// ── Scripts bundling task ─────────────────────────────────────────────────────
val scriptsAssetsDir = file("${projectDir}/src/main/assets/scripts")
val rootScripts =
        listOf(
                "oa.sh",
                "bootstrap.sh",
                "install.sh",
                "uninstall.sh",
                "update.sh",
                "update-core.sh",
                "post-setup.sh"
        )

tasks.register<Copy>("bundleScripts") {
    description = "Copy root maintenance scripts to Android assets"
    group = "build"

    from(rootProject.projectDir)
    into(scriptsAssetsDir)
    include(rootScripts)
}

// Vinculación robusta: asegurar que el frontend compile antes de procesar assets
tasks.whenTaskAdded {
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
        dependsOn("buildWebUI")
        dependsOn("copyWebUIAssets")
        dependsOn("bundleScripts")
    }
    // Asegurar que libproot.so esté en jniLibs antes de empaquetarse en el APK
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("fetchProot")
    }
}

android {
    namespace = "com.openclaw.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openclaw.android"
        minSdk = 31
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Configuración NDK para arm64-v8a
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures { viewBinding = true }

    // Prevent aapt from compressing binary archives — critical for large assets
    androidResources { noCompress += listOf("xz", "gz", "tar", "tar.xz", "tar.gz") }

    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // ── Test Configuration ────────────────────────────────────────────────────
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.testLogging {
                    events("passed", "skipped", "failed", "standardOut", "standardError")
                    showExceptions = true
                    showCauses = true
                    showStackTraces = true
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // En el modelo proot, la extracción del rootfs Alpine la hace /system/bin/tar
    // (binario nativo de Android) — no necesitamos commons-compress ni xz aquí.
    implementation("androidx.webkit:webkit:1.10.0")


    // ── Termux Terminal Libraries (Local AARs) ───────────────────────────────
    implementation(files("libs/terminal-emulator.aar"))
    implementation(files("libs/terminal-view.aar"))

    // ── Unit Testing ─────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest:kotest-property:5.8.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // Robolectric 4.14.1+ needed for SDK 35 support
    testImplementation("org.robolectric:robolectric:4.14.1")

    // ── Integration & UI Testing ─────────────────────────────────────────────
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.50")
    androidTestImplementation("io.mockk:mockk-android:1.13.8")
}

