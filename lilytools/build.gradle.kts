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

// LilyPond's own Python tools (midi2ly, convert-ly, musicxml2ly and
// the modules they use), taken from the source release matching the engine's
// LilyPond version, so they update together. They're arch-independent, so
// they come from the source tarball rather than the (cached) native build.
val nativeOut = (findProperty("lilyengine.nativeOut") as String?)?.let(rootProject::file)
    ?: rootProject.file("native/out")
val lilypondPython = layout.buildDirectory.dir("generated/lilypond-python")
val prepareLilyPondPython by tasks.registering {
    val versionFile = nativeOut.resolve("assets/lilypond-runtime.version")
    inputs.file(versionFile).optional()
    outputs.dir(lilypondPython)
    doLast {
        val out = lilypondPython.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        if (!versionFile.exists()) {
            logger.warn("No native build ($versionFile): LilyPond's Python tools are left out")
            return@doLast
        }
        val version = versionFile.readText().trim().substringBefore('-')
        val name = "lilypond-$version.tar.gz"
        val tarball = rootProject.file("native/downloads/$name").takeIf { it.exists() }
            ?: layout.buildDirectory.file("downloads/$name").get().asFile.also { f ->
                if (!f.exists()) {
                    f.parentFile.mkdirs()
                    val series = version.split(".").take(2).joinToString(".")
                    uri("https://lilypond.org/download/source/v$series/$name").toURL().openStream()
                        .use { input -> f.outputStream().use { input.copyTo(it) } }
                }
            }
        val scripts = mapOf(
            "midi2ly.py" to "midi2ly.py", "convert-ly.py" to "convert_ly.py",
            "musicxml2ly.py" to "musicxml2ly.py",
        ) // (abc2ly runs its conversion at import time, so it can't be used as a module)
        copy {
            from(tarTree(resources.gzip(tarball))) {
                include("lilypond-$version/scripts/*.py", "lilypond-$version/python/*.py")
                exclude("**/book_*.py")
            }
            eachFile {
                val file = path.substringAfterLast('/')
                val isScript = path.contains("/scripts/")
                if (isScript && file !in scripts) { exclude(); return@eachFile }
                path = if (isScript) scripts.getValue(file) else file
            }
            includeEmptyDirs = false
            filter { line ->
                // convert-ly calls main() unconditionally; importing it must not run it
                if (line == "main()") return@filter "if __name__ == '__main__':\n    main()"
                line.replace("@TOPLEVEL_VERSION@", version)
                    .replace("@TARGET_PYTHON@", "python3")
                    .replace("@relocate-preamble@", "")
                    .replace("@localedir@", "")
            }
            into(out)
        }
    }
}
tasks.matching { it.name.contains("PythonSource") && it.name != "prepareLilyPondPython" }.configureEach {
    dependsOn(prepareLilyPondPython)
}

chaquopy {
    sourceSets {
        getByName("main") { srcDir(lilypondPython) }
    }
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
