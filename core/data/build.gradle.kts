plugins {
    id("mobilespoofing.android.library")
    // Applies KSP as well, so no separate alias here.
    id("mobilespoofing.android.hilt")
}

android {
    // Namespaces are per-module and must be unique; Android would fail the build on a collision.
    namespace = "com.snake.mobilespoofing.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:geo"))

    // api, not implementation: SpoofingDatabase extends RoomDatabase, so any consumer that
    // references it needs Room on its own compile classpath too.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
