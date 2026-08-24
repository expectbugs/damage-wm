// Damage (damage-wm) — Gradle root.
//
// Three modules, per DESIGN.md §10 (the deployment topology):
//   :core    — the shell, compositor, wire codecs, and the byte-exact glass simulator.
//              Plain Kotlin/JVM, no Android and no AWT: the SAME bytecode runs inside
//              the desktop program and the phone APK ("one shell that relocates").
//   :desktop — the PC program: AWT text rasterizer, Swing 1x lens preview, content host,
//              laptop-direct + remote-shell entry points.
//   :phone   — the Android app: transport (BLE, banked until flash day), on-phone shell
//              with lens preview, content client with copy-on-open caching.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "damage-wm"
include(":core", ":desktop", ":phone")
