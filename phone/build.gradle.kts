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
        // 31+: the modern BLE permission model only — the legacy
        // BLUETOOTH/ACCESS_FINE_LOCATION pair is deliberately not carried
        // (the target device is a Pixel 10a)
        minSdk = 31
        targetSdk = 35
        // Bump BOTH on every build Adam installs (monotonic versionCode makes a
        // stale Downloads-folder APK refuse to install over a newer one).
        versionCode = 18
        versionName = "0.18"

        buildConfigField("String", "DAMAGE_TOKEN", "\"${secrets.getProperty("token", "")}\"")
        buildConfigField("String", "SERVER_HOST", "\"${secrets.getProperty("serverHost", "100.107.139.121")}\"")
        buildConfigField("int", "CONTENT_PORT", secrets.getProperty("contentPort", "7401"))
        buildConfigField("int", "TRANSPORT_PORT", secrets.getProperty("transportPort", "7402"))
        buildConfigField("int", "REPLICA_PORT", secrets.getProperty("replicaPort", "7403"))
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

// Stage the built APK where the G2CC /setup page's /damage-apk endpoint serves
// it from (2026-08-31, Adam's ask: the Damage APK on the same setup page as
// the G2CC one). The endpoint stamps the download filename from this file's
// mtime, so restaging is all a new build needs.
tasks.register<Copy>("stageApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/phone-debug.apk"))
    into(File(System.getProperty("user.home"), ".damage"))
    rename { "damage-wm.apk" }
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
    // BLE driver — same library G2CC ships on. LIVE on hardware since
    // 2026-08-31 (the phone owns the radio all day); the capability gate
    // still refuses any non-CFW firmware.
    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
}
