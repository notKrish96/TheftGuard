plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.krishgoel.theftguard"
    compileSdk = 37                 // build against Android 17's APIs

    defaultConfig {
        applicationId = "dev.krishgoel.theftguard"
        minSdk = 36                 // Android 16: all three phones are on 16 or newer
        targetSdk = 37              // opt in to Android 17's behaviour rules
        versionCode = 1
        versionName = "0.1-recorder"
    }

    buildFeatures {
        buildConfig = true      // BuildConfig.DEBUG: switches debug-only tools off in release builds
    }

    buildTypes {
        release {
            // Release = what you actually carry around: not debuggable (no
            // `adb run-as` access to its data), debug tools compiled out.
            isMinifyEnabled = false
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Zero runtime dependencies on purpose (decision D1). Only the Kotlin stdlib ships in the APK.
    testImplementation(libs.junit)
}
