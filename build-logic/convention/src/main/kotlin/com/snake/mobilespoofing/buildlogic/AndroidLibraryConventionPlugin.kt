package com.snake.mobilespoofing.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Shared configuration for Android library modules.
 *
 * The `namespace` is intentionally NOT set here: two modules sharing one is a hard build failure,
 * so each module must declare its own.
 */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")

        val catalog = libs
        val compileSdkVersion = catalog.intVersion("compileSdk")
        val minSdkVersion = catalog.intVersion("minSdk")

        extensions.configure<LibraryExtension> {
            compileSdk = compileSdkVersion

            defaultConfig {
                minSdk = minSdkVersion
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            buildFeatures {
                buildConfig = false
            }

            packaging {
                resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }

            // Deliberately no `unitTests.isIncludeAndroidResources` here. AGP 9 treats the
            // unit-test resource source set it creates as "test sources present", and then fails
            // the task with failOnNoDiscoveredTests in any module that has no tests yet. Add it
            // per-module once that module actually has a test needing merged resources.
        }

        dependencies {
            add("testImplementation", catalog.findLibrary("junit").get())
            add("testImplementation", catalog.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", catalog.findLibrary("turbine").get())
        }
    }
}
