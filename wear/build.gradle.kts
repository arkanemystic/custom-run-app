plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Same fixed key as the phone app: the Wear Data Layer only connects apps with the same
// package name and signature.
val keystoreFile: String? = System.getenv("PACER_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.pacer.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pacer"
        // Wear OS 4 (API 33) and up, so a Galaxy Watch4 works even before its Wear OS 5 update.
        minSdk = 33
        targetSdk = 37
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${versionCode}"
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("pacer") {
                storeFile = file(keystoreFile)
                storeType = "pkcs12"
                storePassword = System.getenv("PACER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PACER_KEY_ALIAS")
                keyPassword = System.getenv("PACER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreFile != null) signingConfig = signingConfigs.getByName("pacer")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.ongoing)
    implementation(libs.wear)
    implementation(libs.play.services.location)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
}
