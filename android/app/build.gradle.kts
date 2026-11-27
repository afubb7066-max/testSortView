import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.mohammadjafari.sortview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mohammadjafari.sortview"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.0.1"
    }

    // Phase 5 release signing: the release-apk.yml workflow exposes the
    // four GitHub Secrets as environment variables; when they are absent
    // (a plain local build) the release APK is simply built unsigned so
    // assembleRelease keeps succeeding everywhere.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SORTVIEW_KEYSTORE_FILE")
            if (ksPath != null) {
                storeFile = File(ksPath)
                storePassword = System.getenv("SORTVIEW_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SORTVIEW_KEY_ALIAS")
                keyPassword = System.getenv("SORTVIEW_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("SORTVIEW_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Phase 1 core: coroutines for the scanner/transfer workers,
    // kotlinx-serialization for the desktop-compatible config.json,
    // Coil for image thumbnails, ViewModel wiring for Compose.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.core:core-ktx:1.15.0")

    // Phase 5 splash screen (Theme.SplashScreen backport; the Starting
    // theme lives in res/values/themes.xml).
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Phase 2 thumbnails: commons-compress provides the archive first-image
    // previews for tar/tar.gz (the desktop's tarfile module) and 7z (the
    // desktop's py7zr). zip rides java.util.zip. RAR stays on the type card,
    // exactly like the portable desktop build without unrar (main.py L1094);
    // junrar remains an optional later upgrade (ROADMAP mapping table).
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Phase 3 preview: Media3/ExoPlayer replaces the desktop's Qt Multimedia
    // + libVLC + ffplay stack (ROADMAP mapping table). Video loops forever
    // (REPEAT_MODE_ONE == setLoops(-1)), audio plays without a video surface.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    // JVM regression tests (PathsTest) — run in CI via testDebugUnitTest.
    testImplementation("junit:junit:4.13.2")
}
