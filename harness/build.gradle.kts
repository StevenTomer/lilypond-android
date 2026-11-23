import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.lilyengine.harness"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "org.lilyengine.harness"
        minSdk = 28
        targetSdk = 37
        versionCode = 20
        versionName = "0.17.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // LilyPond is only built for these (64-bit only: Guile bytecode is
        // compiled for 64-bit little-endian).
        // -Plilyengine.abis=arm64-v8a builds a single-ABI APK for one device type.
        val abis = (findProperty("lilyengine.abis") as String?)?.split(",")
            ?: listOf("arm64-v8a", "x86_64")
        ndk { abiFilters += abis }
    }

    // Release signing: the key lives outside the repository, in
    // ~/.android-keys/lilyengine-release.properties (or the file named by the
    // Gradle property lilyengine.signing). Without it, release builds are unsigned.
    val signingFile = (findProperty("lilyengine.signing") as String?)?.let(::file)
        ?: file("${System.getProperty("user.home")}/.android-keys/lilyengine-release.properties")
    val signing = Properties().apply {
        if (signingFile.exists()) signingFile.inputStream().use(::load)
    }
    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Executables must be extracted to nativeLibraryDir to be exec()-able.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":scoreview"))
    implementation(project(":lilytools"))
    implementation(project(":midi"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
