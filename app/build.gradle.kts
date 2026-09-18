plugins {
    alias(libs.plugins.android.application)
    // Still required under AGP 9's built-in Kotlin: the Compose compiler is a
    // Kotlin compiler plugin, and AGP does not bundle it. Its version MUST equal
    // the Kotlin version (both point at `kotlin` in libs.versions.toml).
    alias(libs.plugins.kotlin.compose)
    // NOTE: org.jetbrains.kotlin.android is deliberately absent. AGP 9.0+
    // compiles Kotlin itself; applying that plugin is a hard error.
}

android {
    // Compile-time package for generated classes (R, BuildConfig).
    namespace = "com.test.island.dynamic.android"

    // Which android.jar we compile against -- a TOOLCHAIN setting. It controls
    // which APIs we may reference; it does NOT change runtime behaviour.
    //
    // The spec asked for 36. It is 37 because androidx.core:core-ktx 1.19.0 and
    // compose-bom 2026.09.00 publish `minCompileSdk 37` in their AAR metadata,
    // so AGP refuses to build them against android-36. Those are the versions
    // that carry NotificationCompat.ProgressStyle / setRequestPromotedOngoing,
    // so downgrading them would cost us the feature under test.
    //
    // This app is still an Android 16 app by every runtime measure: targetSdk
    // stays 36 below. 37 >= 36, so all API 36 promoted-notification symbols
    // still resolve.
    compileSdk = 37

    defaultConfig {
        // Runtime identity on the device / Play Store.
        applicationId = "com.test.island.dynamic.android"

        // Oldest Android that may install this. 26 = Oreo, the release where
        // NotificationChannel became mandatory — so channel code needs no branch.
        // It also means API 36 calls MUST be guarded with an SDK_INT check.
        minSdk = 26

        // Which OS behaviour changes we've tested against. Does NOT affect
        // which APIs are available to call.
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // No `kotlin { compilerOptions { jvmTarget } }` block needed: under built-in
    // Kotlin, jvmTarget defaults to compileOptions.targetCompatibility above.
}

dependencies {
    // core-ktx 1.19.0 — needed for NotificationCompat.ProgressStyle and
    // setRequestPromotedOngoing(). Spec asked for 1.17.0+.
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // platform() marks this a BOM: contributes versions, ships no code.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // @Preview renderer — debug builds only.
    debugImplementation(libs.androidx.compose.ui.tooling)
}
