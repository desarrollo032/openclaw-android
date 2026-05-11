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
tasks.whenTaskAdded {
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
        dependsOn("buildWebUI")
        dependsOn("bundleScripts")
    }
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

        // Alineación de 16 KB para Android 15+
        externalNativeBuild {
            cmake {
                // Flags de linker para 16 KB
                cppFlags += listOf("-Wl,-z,max-page-size=16384")
            }
        }
        
        // Configuración NDK para arm64-v8a
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // For robust tar.gz and tar.xz extraction
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("org.tukaani:xz:1.9")
    implementation("androidx.webkit:webkit:1.10.0")

    // ── Termux Terminal Libraries (Maven Central) ──────────────────────────
    // Versiones oficiales y ya alineadas para 16 KB (Android 15+)
    implementation("com.termux:terminal-emulator:1.0.16")
    implementation("com.termux:terminal-view:1.0.16")


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
    testImplementation("org.robolectric:robolectric:4.11.1")

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

