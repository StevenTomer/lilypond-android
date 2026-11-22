plugins {
    alias(libs.plugins.android.library)
}

// Native artifacts produced by native/build.py package (not checked in):
//   native/out/jniLibs/<abi>/liblilypond.so, libgs.so
//   native/out/assets/lilypond-runtime.zip, lilypond-runtime.version
val nativeOut = rootProject.file("native/out")

android {
    namespace = "org.lilyengine.engine"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets {
        getByName("main") {
            jniLibs.directories.add(nativeOut.resolve("jniLibs").path)
            assets.directories.add(nativeOut.resolve("assets").path)
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
