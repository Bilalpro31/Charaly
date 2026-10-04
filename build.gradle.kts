plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * Full deterministic verification. Runs the entire story runtime on a plain JVM:
 * no device, no emulator, no server, no model download.
 */
tasks.register("verify") {
    group = "verification"
    description = "Compile and run every deterministic Charaly runtime test."
    dependsOn(":charaly-runtime:test")
}
