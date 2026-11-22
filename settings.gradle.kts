pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lilypond-android"
include(":engine", ":scoreview", ":lilytools", ":midi", ":harness")
// Throwaway spike: embedded Python (Chaquopy) + python-ly. Not built by CI.
include(":pyspike")
