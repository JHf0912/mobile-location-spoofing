plugins {
    id("mobilespoofing.android.library")
    id("mobilespoofing.android.hilt")
}

android {
    // Namespaces are per-module and must be unique; Android would fail the build on a collision.
    namespace = "com.snake.mobilespoofing.core.location"
}

dependencies {
    api(project(":core:model"))
    api(project(":core:geo"))

    implementation(libs.google.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.lifecycle.service)
}
