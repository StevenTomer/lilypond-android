plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "org.lilyengine.lilytools"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 28
        // Chaquopy needs the ABIs up front; LilyPond only exists for these.
        // (-Plilyengine.abis=arm64-v8a, as for the app, builds just one.)
        val abis = (findProperty("lilyengine.abis") as String?)?.split(",")
            ?: listOf("arm64-v8a", "x86_64")
        ndk { abiFilters += abis }
    }
}

chaquopy {
    defaultConfig {
        // Must match the build machine's python3 (Chaquopy compiles .pyc with it).
        version = "3.14"
        pip {
            install("python-ly==0.9.10")
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
