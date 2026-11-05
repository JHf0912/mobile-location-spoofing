import com.google.devtools.ksp.gradle.KspExtension

plugins {
    id("mobilespoofing.android.library")
    // Applies KSP as well, so no separate alias here.
    id("mobilespoofing.android.hilt")
}

// Room writes the exported schema into the source tree so it is reviewed and committed with the
// code. Version 1 is treated as already distributed: any later schema change needs a migration
// that this file can be diffed against, never a destructive fallback.
val roomSchemaDirectory = layout.projectDirectory.dir("schemas").asFile.absolutePath

extensions.configure<KspExtension> {
    arg("room.schemaLocation", roomSchemaDirectory)
}

android {
    // Namespaces are per-module and must be unique; Android would fail the build on a collision.
    namespace = "com.snake.mobilespoofing.core.data"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:model"))

    // api, not implementation: SpoofingDatabase extends RoomDatabase, so any consumer that
    // references it needs Room on its own compile classpath too.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
