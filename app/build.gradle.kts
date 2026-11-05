import java.util.Properties

plugins {
    id("mobilespoofing.android.application")
    id("mobilespoofing.android.compose")
    id("mobilespoofing.android.hilt")
}

/**
 * Resolves the AMap key from, in order: -P / gradle.properties, local.properties, the environment.
 *
 * Two things here are deliberate:
 *
 *  - local.properties is read explicitly, because Gradle does NOT load it into project properties.
 *    Only AGP reads that file, and only for sdk.dir/ndk.dir — so a bare findProperty() silently
 *    misses anything a contributor put there, which is exactly the bug this replaced.
 *
 *  - it is read through the Provider API so the configuration cache registers the file as an
 *    input. A plain File read is not tracked: the cached configuration would outlive an edit to
 *    the key and the build would keep injecting the stale value with no warning at all.
 */
val localPropertiesKey: Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .map { text ->
            Properties().apply { load(text.reader()) }.getProperty("AMAP_API_KEY").orEmpty()
        }

val amapKey: String? = listOf(
    providers.gradleProperty("AMAP_API_KEY"),
    localPropertiesKey,
    providers.environmentVariable("AMAP_API_KEY"),
).firstNotNullOfOrNull { it.orNull?.takeIf(String::isNotBlank) }

// Signing material lives in a git-ignored file. Absent on a fresh clone and in CI without
// secrets, in which case the release build simply stays unsigned instead of failing to configure.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

if (amapKey.isNullOrBlank()) {
    logger.warn(
        "AMAP_API_KEY is not set. The build will succeed, but the map will fail to render at " +
            "runtime with an auth error. Set it in local.properties, gradle.properties, or the " +
            "environment. See README.md for the registration steps."
    )
}

android {
    namespace = "com.snake.mobilespoofing"

    defaultConfig {
        applicationId = "com.snake.mobilespoofing"
        versionCode = 1
        versionName = "1.0.0"

        // NOTE: do NOT add an applicationIdSuffix to the debug build type. The AMap key is
        // registered against the package name (and the signing certificate's SHA-1), so a
        // ".debug" suffix would silently break the map in every debug build.
        manifestPlaceholders["AMAP_API_KEY"] = amapKey ?: "replace_with_your_key"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(requireNotNull(keystoreProperties.getProperty("storeFile")) {
                    "keystore.properties is missing storeFile"
                })
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Pairing resource shrinking with minification is standard, but AMap resolves some
            // resources reflectively, so verify a release build on a device before shipping one.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:geo"))
    implementation(project(":core:data"))
    implementation(project(":core:location"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.google.android.material)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.amap.maps)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
