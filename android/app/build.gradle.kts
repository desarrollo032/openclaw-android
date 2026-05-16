plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

// ── Versioning
val defaultVersionCode = 1
val defaultVersionName = "1.5-dev"

val versionPropsFile = file("${rootProject.projectDir}/version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) load(versionPropsFile.inputStream())
    else {
        setProperty("VERSION_CODE", defaultVersionCode.toString())
        setProperty("VERSION_NAME", defaultVersionName)
    }
}

val gitVersionCode: Int = versionProps.getProperty("VERSION_CODE", defaultVersionCode.toString()).toIntOrNull() ?: defaultVersionCode
val gitVersionName: String = versionProps.getProperty("VERSION_NAME", defaultVersionName)

// ── Web UI build
val wwwSrcDir = file("${rootProject.projectDir}/www")
val wwwDistDir = file("${wwwSrcDir}/dist")
val wwwAssetsDir = file("${projectDir}/src/main/assets/www")

tasks.register<Exec>("buildWebUI") {
    description = "Build React web UI"
    group = "build"
    workingDir = wwwSrcDir
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    if (isWindows) commandLine("cmd", "/c", "npm", "run", "build")
    else commandLine("npm", "run", "build")

    inputs.dir(file("${wwwSrcDir}/src"))
    inputs.file(file("${wwwSrcDir}/package.json"))
    inputs.file(file("${wwwSrcDir}/vite.config.ts"))
    outputs.dir(wwwDistDir)

    doLast {
        val indexHtml = file("${wwwDistDir}/index.html")
        if (!indexHtml.exists()) throw GradleException("buildWebUI: index.html no existe. Revisa Vite/TSC logs")
    }

    finalizedBy("copyWebUIAssets")
}

tasks.register<Copy>("copyWebUIAssets") {
    description = "Copy built web UI to Android assets"
    doFirst {
        if (wwwAssetsDir.exists()) wwwAssetsDir.deleteRecursively()
        wwwAssetsDir.mkdirs()
    }
    from(wwwDistDir)
    into(wwwAssetsDir)
    doLast { println("✓ Web UI copied to assets/www") }
}

// ── Remove old scripts bundling (no longer needed)

// ── Ensure frontend is built before merging assets
tasks.whenTaskAdded {
    if (name == "mergeDebugAssets" || name == "mergeReleaseAssets") {
        dependsOn("buildWebUI")
        dependsOn("copyWebUIAssets")
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
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures { viewBinding = true }
    androidResources { noCompress += listOf("xz", "gz", "tar", "tar.xz", "tar.gz") }
    packaging { jniLibs { useLegacyPackaging = true } }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

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

    // For tar.gz/tar.xz extraction
    implementation("org.apache.commons:commons-compress:1.26.0")
    implementation("commons-codec:commons-codec:1.16.1")
    implementation("org.tukaani:xz:1.9")
    implementation("androidx.webkit:webkit:1.10.0")

    // Termux Terminal AARs
    implementation(files("libs/terminal-emulator.aar"))
    implementation(files("libs/terminal-view.aar"))

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest:kotest-property:5.8.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("org.robolectric:robolectric:4.14.1")

    // UI / integration tests
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
