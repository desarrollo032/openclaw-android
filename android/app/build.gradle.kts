import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Dynamic versioning from git ───────────────────────────────────────────────

// ── Dynamic versioning from git ───────────────────────────────────────────────

fun runGit(vararg args: String): String = try {
    ProcessBuilder("git", *args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText().trim()
} catch (e: Exception) { "" }

val gitVersionCode: Int = runGit("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

val gitVersionName: String = run {
    val raw = runGit("describe", "--tags", "--always", "--dirty=-dev")
    if (raw.isEmpty()) {
        "0.0-${runGit("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }}"
    } else if (raw.first().isDigit() || raw.startsWith("v")) {
        raw
    } else {
        "0.0-$raw"
    }
}

// ── Web UI auto-build task ────────────────────────────────────────────────────

val wwwSrcDir   = file("${rootProject.projectDir}/www")
val wwwDistDir  = file("${wwwSrcDir}/dist")
val wwwAssetsDir = file("${projectDir}/src/main/assets/www")

tasks.register<Exec>("buildWebUI") {
    description = "Build the React web UI and copy dist to Android assets"
    group       = "build"

    workingDir = wwwSrcDir
    // Use npm on Windows (cmd /c npm run build) or npm directly on Unix
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine("cmd", "/c", "npm", "run", "build")
    } else {
        commandLine("npm", "run", "build")
    }

    // Only re-run if sources changed
    inputs.dir(file("${wwwSrcDir}/src"))
    inputs.file(file("${wwwSrcDir}/package.json"))
    inputs.file(file("${wwwSrcDir}/vite.config.ts"))
    outputs.dir(wwwDistDir)

    doLast {
        // Copy dist → assets/www
        if (wwwAssetsDir.exists()) wwwAssetsDir.deleteRecursively()
        wwwDistDir.copyRecursively(wwwAssetsDir, overwrite = true)
        println("✓ Web UI built and copied to assets/www")
    }
}

// ── Scripts bundling task ─────────────────────────────────────────────────────
val scriptsAssetsDir = file("${projectDir}/src/main/assets/scripts")
val rootScripts = listOf(
    "oa.sh", "bootstrap.sh", "install.sh", "uninstall.sh", 
    "update.sh", "update-core.sh", "post-setup.sh"
)

tasks.register<Copy>("bundleScripts") {
    description = "Copy root maintenance scripts to Android assets"
    group       = "build"

    from(rootProject.projectDir)
    into(scriptsAssetsDir)
    include(rootScripts)
}

// Vinculación robusta: asegurar que el frontend compile antes de procesar assets
tasks.named("generateDebugAssets") {
    dependsOn("buildWebUI")
    dependsOn("bundleScripts")
}
tasks.named("generateReleaseAssets") {
    dependsOn("buildWebUI")
    dependsOn("bundleScripts")
}

android {
    namespace = "com.openclaw.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openclaw.android"
        minSdk = 24
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = true
    }

    // Prevent aapt from compressing binary archives — critical for large assets
    androidResources {
        noCompress += listOf("xz", "gz", "tar", "tar.xz", "tar.gz")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // For robust tar.gz and tar.xz extraction
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")
    implementation("androidx.webkit:webkit:1.10.0")

    // ── Termux Terminal Libraries (Local AARs) ───────────────────────────────
    implementation(files("libs/terminal-emulator.aar"))
    implementation(files("libs/terminal-view.aar"))


    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

