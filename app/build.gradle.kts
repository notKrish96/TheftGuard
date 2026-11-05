import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing: the key and its passwords live OUTSIDE the repo.
// keystore.properties (project root) is git-ignored; see keystore.properties.example.
// Without it, release builds are simply left unsigned.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.krishgoel.theftguard"
    compileSdk = 37                 // build against Android 17's APIs

    defaultConfig {
        applicationId = "dev.krishgoel.theftguard"
        minSdk = 36                 // Android 16: all three phones are on 16 or newer
        targetSdk = 37              // opt in to Android 17's behaviour rules
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true      // BuildConfig.DEBUG: switches debug-only tools off in release builds
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Release = what you actually carry around: not debuggable (no
            // `adb run-as` access to its data), debug tools compiled out.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
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
