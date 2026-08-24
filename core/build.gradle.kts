// :core — the shell that relocates (DESIGN.md §10.1).
//
// HARD RULE: this module must run unmodified on desktop JVM and inside the
// Android APK. Therefore: no java.awt, no android.*, no javax.swing — only
// java.* APIs that exist on Android API 29+ (java.util.zip, java.nio.file,
// java.net are all fine). Platform text rasterization enters through the
// wm.damage.core.text.TextRasterizer seam.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
    }
}
