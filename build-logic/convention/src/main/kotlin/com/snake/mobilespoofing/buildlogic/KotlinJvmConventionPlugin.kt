package com.snake.mobilespoofing.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Pure-JVM Kotlin modules.
 *
 * This is load-bearing for the architecture, not a convenience: with no Android plugin applied
 * there is no `android.jar` on the compile classpath, so the compiler itself rejects any
 * `import android.*`. That is what keeps the geo/math and domain-model modules free of Android
 * types — and therefore testable with plain JUnit and virtual time rather than Robolectric.
 */
class KotlinJvmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")

        extensions.configure<JavaPluginExtension> {
            toolchain {
                // .set() rather than `=`: the assignment operator on a Property needs
                // org.gradle.kotlin.dsl.assign, which a plain Kotlin source file does not get.
                languageVersion.set(JavaLanguageVersion.of(17))
            }
        }

        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }

        val catalog = libs
        dependencies {
            add("testImplementation", catalog.findLibrary("junit").get())
            add("testImplementation", catalog.findLibrary("kotlinx-coroutines-test").get())
            add("testImplementation", catalog.findLibrary("turbine").get())
        }
    }
}
