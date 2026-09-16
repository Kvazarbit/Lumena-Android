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
include(":app")
include(":llama")
project(":llama").projectDir = file("vendor/llama.cpp/examples/llama.android/lib")
