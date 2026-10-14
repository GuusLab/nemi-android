pluginManagement {
    repositories {
        // Google's mirror of Maven Central, tried first. Maven Central answers
        // shared build hosts such as JitPack with 429 Too Many Requests at
        // busy times, which failed whole releases on a rate limit.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google's mirror of Maven Central, tried first. Maven Central answers
        // shared build hosts such as JitPack with 429 Too Many Requests at
        // busy times, which failed whole releases on a rate limit.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
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
