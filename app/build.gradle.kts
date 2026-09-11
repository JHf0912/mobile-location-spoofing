import java.util.Properties

plugins {
    id("mobilespoofing.android.application")
    id("mobilespoofing.android.compose")
    id("mobilespoofing.android.hilt")
}

/**
 * Reads a value from, in order: Gradle properties (-P / gradle.properties), then
 * local.properties, then the environment.
 *
 * local.properties matters here because Gradle does NOT load it into project properties — only
 * AGP reads it, and only for sdk.dir/ndk.dir. Calling findProperty() alone therefore silently
 * misses anything a contributor put there.
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun configValue(key: String): String? =
    (findProperty(key) as String?) ?: localProperties.getProperty(key) ?: System.getenv(key)

// Signing material lives in a git-ignored file. Absent on a fresh clone and in CI without
// secrets, in which case the release build simply stays unsigned instead of failing to configure.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use { load(it) }
}

val amapKey = configValue("AMAP_API_KEY")
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
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.google.android.material)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.google.play.services.location)

    implementation(libs.amap.maps)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
