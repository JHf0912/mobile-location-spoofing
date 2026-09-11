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
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "mobilespoofing.android.application"
            implementationClass = "com.snake.mobilespoofing.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidCompose") {
            id = "mobilespoofing.android.compose"
            implementationClass = "com.snake.mobilespoofing.buildlogic.AndroidComposeConventionPlugin"
        }
    }
}
