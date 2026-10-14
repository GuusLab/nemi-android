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

rootProject.name = "nemi-android-sdk"

include(":nemi-core")

// The Android module needs an Android SDK. Pass -Pnemi.skipAndroid=true to build
// only the pure Kotlin core on a machine without one.
if (providers.gradleProperty("nemi.skipAndroid").orNull != "true") {
    include(":nemi-android")
}
