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
