pluginManagement {
    // Makes this project's convention plugins (mobilespoofing.*) resolvable by id below.
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MobileLocationSpoofing"

// Dependency direction is one-way: :app -> :core:* and (location|data) -> geo -> model.
// :core:model and :core:geo are pure JVM (no Android plugin), so the compiler itself enforces
// that they stay free of android.* imports.
include(":app")
include(":core:model")
include(":core:geo")
include(":core:data")
include(":core:location")
