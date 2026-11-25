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
    // Feed (FEED.md §2.7): the article extractor parses HTML with jsoup — pure
    // Java, MIT, runs on Android and the JVM alike
    implementation(libs.jsoup)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    // Build S1-3 B12: the terminal library on the TEST class path only, so the real-server look (`LookServer`) serves
    // it as the PC does; core's own code never depends on it (the phone never carries it)
    testRuntimeOnly(libs.xterm)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
        showStandardStreams = false
    }
    // The test JVM's temp folders live under this module's build directory, never /tmp (stage TMP, 2026-11-25: the
    // runs had left 10,002 folders there in nine days). Emptied at each run's start, so a stopped run's folders go with
    // the next run and two trees never share one; set as the task runs, so the path stays out of its cache key.
    val testTmp = layout.buildDirectory.dir("test-tmp")
    doFirst {
        val d = testTmp.get().asFile
        if (!d.deleteRecursively()) logger.warn("test-tmp: $d could not be emptied; what is left stays beside this run")
        d.mkdirs()
        systemProperty("java.io.tmpdir", d.absolutePath)
    }
}

// Build S1-2 Q2 (S1-2 R0 §1, the real-server look; test code only): the test runtime classpath written to a file, so
// the stage starts `wm.damage.core.LookServerKt` as ONE JVM by hand, through the machine's JVM lock, and ends it.
tasks.register("lookClasspath") {
    dependsOn("testClasses")
    val out = layout.buildDirectory.file("look.classpath")
    val cp = sourceSets["test"].runtimeClasspath
    outputs.file(out)
    doLast { out.get().asFile.writeText(cp.files.joinToString(System.getProperty("path.separator"))) }
}
