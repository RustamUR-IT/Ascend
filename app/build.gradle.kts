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
        versionCode = 3
        versionName = "1.2"
    }

    // A fixed key, so every new build installs over the previous one without uninstalling.
    // The key and its passwords never live in the repo: they come from environment
    // variables (GitHub Actions secrets in CI). Without them, debug builds fall back to
    // the standard Android debug key and release builds stay unsigned.
    val releaseKeystoreFile = System.getenv("RELEASE_KEYSTORE_FILE")
    val releaseKeystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseKeystoreFile, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword
    ).none { it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
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
