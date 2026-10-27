plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ascend.ui"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ascend.ui"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    // A fixed key, so every new build installs over the previous one without uninstalling.
    signingConfigs {
        create("ascend") {
            storeFile = file("ascend.jks")
            storePassword = "ascend-key"
            keyAlias = "ascend"
            keyPassword = "ascend-key"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ascend")
        }
        debug {
            signingConfig = signingConfigs.getByName("ascend")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
