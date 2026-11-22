plugins {
    alias(libs.plugins.android.application)
    id("com.chaquo.python") version "17.2.0"
}

android {
    namespace = "org.lilyengine.pyspike"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "org.lilyengine.pyspike"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
}

chaquopy {
    defaultConfig {
        version = "3.14"
        pip {
            install("python-ly")
        }
    }
}
