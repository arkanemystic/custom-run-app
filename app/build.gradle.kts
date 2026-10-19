plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// CI decodes the signing keystore to a temp file and points this at it. Local builds
// without it fall back to the default debug key.
val keystoreFile: String? = System.getenv("PACER_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "dev.pacer"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.pacer"
        minSdk = 34
        targetSdk = 37
        // Monotonic across CI builds so each APK installs over the previous one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${versionCode}"

        // Pixel 10 is arm64; ship only that ABI of MapLibre's native library.
        ndk { abiFilters += "arm64-v8a" }
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.play.services.location)
    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
