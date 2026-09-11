plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Declared here purely so the plugin version is pinned on the buildscript classpath; the
    // convention plugins are what actually apply it, per module.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}

// `./gradlew check` stops at the unit tests and never produces an APK, so a contributor cannot
// verify the project the way CI does with one command. This adds the missing piece without
// pulling in an external task runner.
tasks.register("verify") {
    group = "verification"
    description = "Assembles the debug APK and runs the unit tests — what CI runs."
    dependsOn(":app:assembleDebug", ":app:testDebugUnitTest")
}
