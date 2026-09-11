package com.snake.mobilespoofing.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose support: enables the build feature, pulls in the BOM, and adds the standard artifacts.
 *
 * The Compose compiler stays a separate plugin — AGP does not take it over the way it took over
 * Kotlin. Its version must equal the KGP that AGP bundles, otherwise the two disagree about the
 * compiler.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        extensions.configure<ApplicationExtension> {
            buildFeatures {
                compose = true
            }
        }

        val catalog = libs
        dependencies {
            add("implementation", platform(catalog.findLibrary("androidx-compose-bom").get()))
            add("implementation", catalog.findLibrary("androidx-compose-ui").get())
            add("implementation", catalog.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", catalog.findLibrary("androidx-compose-material3").get())
            add("debugImplementation", catalog.findLibrary("androidx-compose-ui-tooling").get())
            add("debugImplementation", catalog.findLibrary("androidx-compose-ui-test-manifest").get())
        }
    }
}
