import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Baked defaults (gitignored damage-secrets.properties at the repo root):
//   token=...            the content/transport token from ~/.damage/config.json
//   serverHost=...       beardos's tailscale address
// Runtime Settings can override both; baking just makes the sideloaded APK
// work with zero setup (the G2CC harness pattern).
val secrets = Properties().apply {
    val f = rootProject.file("damage-secrets.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "wm.damage.phone"
    compileSdk = 35
    // /opt/android-sdk holds exactly build-tools 35.0.0 and is system-owned;
    // pin so AGP never tries to auto-install another (the G2CC lesson).
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "wm.damage.phone"
        minSdk = 29
        targetSdk = 35
        // Bump BOTH on every build Adam installs (monotonic versionCode makes a
        // stale Downloads-folder APK refuse to install over a newer one).
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "DAMAGE_TOKEN", "\"${secrets.getProperty("token", "")}\"")
        buildConfigField("String", "SERVER_HOST", "\"${secrets.getProperty("serverHost", "100.107.139.121")}\"")
        buildConfigField("int", "CONTENT_PORT", secrets.getProperty("contentPort", "7401"))
        buildConfigField("int", "TRANSPORT_PORT", secrets.getProperty("transportPort", "7402"))
    }

    // ONE canonical signing identity, pinned, with NO exists() fallback: a
    // missing keystore fails the build loudly rather than silently signing
    // with whatever ambient debug key the session resolves (the G2CC
    // "App not installed" roulette, ended the same way).
    signingConfigs {
        create("damage") {
            storeFile = file(System.getProperty("user.home") + "/.damage/damage-debug.keystore")
            storePassword = "damagewm"
            keyAlias = "damage"
            keyPassword = "damagewm"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("damage")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("damage")
            isMinifyEnabled = false
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
        buildConfig = true
    }
    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // BLE driver — same library G2CC ships on. The transport is BANKED until
    // flash day (Settings default keeps it off; the capability gate refuses
    // stock firmware anyway) but builds against the real API now.
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
}
