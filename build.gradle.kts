// Root build — plugin versions come from gradle/libs.versions.toml.
// Nothing is applied at the root; each module applies what it needs.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
}
