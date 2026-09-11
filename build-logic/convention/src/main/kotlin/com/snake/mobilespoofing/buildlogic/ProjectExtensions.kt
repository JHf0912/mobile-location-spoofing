package com.snake.mobilespoofing.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** The `libs` catalog from gradle/libs.versions.toml, reachable from a convention plugin. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Reads an SDK level out of the catalog; every Android module shares these. */
internal fun VersionCatalog.intVersion(alias: String): Int =
    findVersion(alias).get().requiredVersion.toInt()
