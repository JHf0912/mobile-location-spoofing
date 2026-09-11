plugins {
    `kotlin-dsl`
}

group = "com.snake.mobilespoofing.buildlogic"

dependencies {
    // compileOnly, not implementation: these are already on the buildscript classpath when the
    // convention plugin runs. The -gradlePlugin artifacts (rather than the plugin markers) are
    // required because a marker does not expose the implementation types we configure against.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    // Needed by the pure-JVM convention plugin, which applies org.jetbrains.kotlin.jvm and
    // configures KotlinJvmProjectExtension. Only *Android* modules get Kotlin from AGP.
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "mobilespoofing.android.application"
            implementationClass = "com.snake.mobilespoofing.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "mobilespoofing.android.library"
            implementationClass = "com.snake.mobilespoofing.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "mobilespoofing.android.compose"
            implementationClass = "com.snake.mobilespoofing.buildlogic.AndroidComposeConventionPlugin"
        }
        register("kotlinJvm") {
            id = "mobilespoofing.kotlin.jvm"
            implementationClass = "com.snake.mobilespoofing.buildlogic.KotlinJvmConventionPlugin"
        }
        register("androidHilt") {
            id = "mobilespoofing.android.hilt"
            implementationClass = "com.snake.mobilespoofing.buildlogic.AndroidHiltConventionPlugin"
        }
    }
}
