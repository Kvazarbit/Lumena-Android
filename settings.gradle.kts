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
rootProject.name = "LumenaAndroid"

// llama.cpp is vendored reproducibly by scripts/sync_llama_cpp.sh into third_party/llama.cpp.
// Keeping it out of the APK source tree until sync avoids silently shipping stale native binaries.
include(":app")
