plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Shared POM metadata and optional signing, so every module is Maven Central ready.
// Signing only happens when a key is supplied:
//   -PsigningInMemoryKey=... -PsigningInMemoryKeyPassword=...
subprojects {
    group = rootProject.group
    version = rootProject.version

    pluginManager.withPlugin("maven-publish") {
        apply(plugin = "signing")

        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(project.description)
                    url.set("https://github.com/GuusLab/nemi-android")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("guuslab")
                            name.set("GuusLab")
                            url.set("https://github.com/GuusLab")
                        }
                    }
                    scm {
                        url.set("https://github.com/GuusLab/nemi-android")
                        connection.set("scm:git:https://github.com/GuusLab/nemi-android.git")
                        developerConnection.set("scm:git:ssh://git@github.com/GuusLab/nemi-android.git")
                    }
                }
            }
        }

        val signingKey = providers.gradleProperty("signingInMemoryKey").orNull
        if (signingKey != null) {
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey, providers.gradleProperty("signingInMemoryKeyPassword").orNull)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}
