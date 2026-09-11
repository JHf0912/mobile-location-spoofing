plugins {
    id("mobilespoofing.android.library")
    alias(libs.plugins.ksp)
}

android {
    // Namespaces are per-module and must be unique; Android would fail the build on a collision.
    namespace = "com.snake.mobilespoofing.core.data"
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:geo"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
