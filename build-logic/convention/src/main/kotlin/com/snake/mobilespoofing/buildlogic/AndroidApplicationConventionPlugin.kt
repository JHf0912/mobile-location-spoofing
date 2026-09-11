package com.snake.mobilespoofing.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Shared configuration for Android application modules.
 *
 * Note what is NOT here: `org.jetbrains.kotlin.android`. Since AGP 9.0 Kotlin compilation is built
 * into AGP, and applying that plugin fails outright with "no longer required for Kotlin support
 * since AGP 9.0". Kotlin's jvmTarget also needs no separate setting — it defaults to
 * `compileOptions.targetCompatibility`, configured below.
 */
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")

        val catalog = libs
        val compileSdkVersion = catalog.intVersion("compileSdk")
        val minSdkVersion = catalog.intVersion("minSdk")
        val targetSdkVersion = catalog.intVersion("targetSdk")

        extensions.configure<ApplicationExtension> {
            compileSdk = compileSdkVersion

            defaultConfig {
                minSdk = minSdkVersion
                targetSdk = targetSdkVersion
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                vectorDrawables { useSupportLibrary = true }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            buildFeatures {
                buildConfig = true
            }

            packaging {
                resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
    }
}
