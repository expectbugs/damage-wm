import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)
    // PC-direct BLE: BlueZ over D-Bus (Linux only; §10.7 defers macOS/Windows)
    implementation(libs.bluez.dbus)
    implementation(libs.dbus.java.core)
    runtimeOnly(libs.dbus.java.unixsocket)
    runtimeOnly(libs.slf4j.simple)
    // Music (MUSIC.md §9.1): Postgres over the Unix socket, peer auth
    implementation(libs.postgresql)
    implementation(libs.junixsocket.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

application {
    mainClass.set("wm.damage.desktop.MainKt")
}

tasks.test {
    useJUnit()
}

// A runnable fat jar so `damage` works without gradle in the loop.
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("damage")
    manifest { attributes["Main-Class"] = "wm.damage.desktop.MainKt" }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
}

// Stage the jar at the STABLE path the OpenRC service runs (DAILY.md): builds
// land in build/libs and get copied here deliberately, so a half-broken tree
// never changes what the daily driver boots. Staged via a temp file + ATOMIC
// move: an in-place truncation broke the RUNNING service's lazy class loads
// (its shutdown hook threw NoClassDefFoundError, 2026-08-31) — a new inode
// leaves the live process its old jar until restart.
tasks.register("stageJar") {
    dependsOn("fatJar")
    val src = layout.buildDirectory.file("libs/damage.jar")
    doLast {
        val dst = File(System.getProperty("user.home"), ".damage/damage.jar").toPath()
        val tmp = dst.resolveSibling("damage.jar.staging")
        Files.createDirectories(dst.parent)
        Files.copy(src.get().asFile.toPath(), tmp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
