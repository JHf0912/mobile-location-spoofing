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

// Dependency direction is one-way: :app -> :core:*; :core:location -> {:core:geo, :core:model};
// :core:geo -> :core:model; and :core:data -> :core:model (not :core:geo).
// :core:model and :core:geo are pure JVM (no Android plugin), so the compiler itself enforces
// that they stay free of android.* imports.
include(":app")
include(":core:model")
include(":core:geo")
include(":core:data")
include(":core:location")
