// Worm — books & audiobooks. Licensed under GPL-3.0-or-later (see LICENSE).
import java.util.Properties

// Signing comes from keystore.properties (git-ignored, local) or environment variables (CI).
// Never commit the keystore or its passwords. Without either, builds are unsigned.
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(name: String, env: String): String? = keyProps.getProperty(name) ?: System.getenv(env)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // No Google-only encrypted dependency metadata in the APK (IzzyOnDroid / F-Droid require this).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    namespace = "com.worm.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.worm.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "0.7.1"
    }

    // One fixed key so every build installs as an update over the last.
    val storePath = secret("storeFile", "WORM_KEYSTORE_FILE")
    val wormSigning = storePath?.let { path ->
        signingConfigs.create("worm") {
            storeFile = rootProject.file(path)
            storePassword = secret("storePassword", "WORM_KEYSTORE_PASSWORD")
            keyAlias = secret("keyAlias", "WORM_KEY_ALIAS")
            keyPassword = secret("keyPassword", "WORM_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            wormSigning?.let { signingConfig = it }
            applicationIdSuffix = ".debug"
        }
        release {
            wormSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    // One APK per CPU type keeps each download small (arm64 = nearly every modern phone).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation-graphics")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.window:window:1.5.1")
    implementation("androidx.graphics:graphics-shapes:1.1.0")

    // Audiobooks: playback, background service, notification, Android Auto.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    // Silk Spinner: hardware AAC encoding / joining tracks into one .m4b.
    implementation("androidx.media3:media3-transformer:1.11.1")

    // Reader: foliate-js runs inside a WebView served from assets.
    implementation("androidx.webkit:webkit:1.18.0")

    // Wormhole: tiny HTTP server, QR codes, camera for scanning.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")

    // Torrent client: libtorrent via libtorrent4j (MIT); native code per CPU type.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-39")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:2.1.0-39")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    // Lets Coil load covers from the internet (search results, Readest cloud).
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("androidx.palette:palette-ktx:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // Home screen widgets
    implementation("androidx.glance:glance-appwidget:1.2.0")
    implementation("androidx.glance:glance-material3:1.2.0")

    testImplementation("junit:junit:4.13.2")
    // Real XmlPullParser for JVM tests (android.jar only has stubs).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.media3.common.util.UnstableApi",
        )
    }
}
