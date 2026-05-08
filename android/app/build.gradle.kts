import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── Dynamic versioning from git ───────────────────────────────────────────────

fun runGit(vararg args: String): String = try {
    ProcessBuilder("git", *args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().readText().trim()
} catch (e: Exception) { "" }

// versionCode: total commit count — increments automatically on every commit
val gitVersionCode: Int = runGit("rev-list", "--count", "HEAD").toIntOrNull() ?: 1

// versionName: nearest tag (e.g. "1.2.0"), or "0.0-<short-hash>" if no tag exists.
// git describe returns the tag when on a tagged commit, or "<tag>-<n>-g<hash>" otherwise.
// If there are no tags at all it returns just the hash — detect that and add a prefix.
val gitVersionName: String = run {
    val raw = runGit("describe", "--tags", "--always", "--dirty=-dev")
    if (raw.isEmpty()) {
        "0.0-${runGit("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }}"
    } else if (raw.first().isDigit() || raw.startsWith("v")) {
        // Looks like a real tag-based version (e.g. "1.0.1", "1.0.1-dev", "1.0.1-3-gabcdef")
        raw
    } else {
        // No tags — git returned a bare hash
        "0.0-$raw"
    }
}

android {
    namespace = "com.openclaw.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openclaw.android"
        minSdk = 24
        targetSdk = 34
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Prevent aapt from compressing binary archives — critical for large assets
    androidResources {
        noCompress += listOf("xz", "tar", "gz")
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

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
