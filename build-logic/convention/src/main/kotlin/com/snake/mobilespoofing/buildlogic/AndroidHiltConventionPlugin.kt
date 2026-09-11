package com.snake.mobilespoofing.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Hilt for any Android module that declares an `@Module` or provides `@Inject` constructors.
 *
 * Apply this when a module is created, not when its first binding is needed: a `@Module` in a
 * module without this plugin compiles cleanly and only fails much later, during app assembly,
 * with a missing-binding error. That feedback loop is slow enough to be worth avoiding.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("com.google.dagger.hilt.android")

        val catalog = libs
        dependencies {
            add("implementation", catalog.findLibrary("hilt-android").get())
            add("ksp", catalog.findLibrary("hilt-compiler").get())
        }
    }
}
