pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.10.1"
        kotlin("android") version "2.2.10"
    }
}

dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    url =
                        uri(
                            providers
                                .gradleProperty("testRepository")
                                .orElse("../build/repository")
                                .get()
                        )
                }
            }
            filter { includeModule("co.featbit", "featbit-openfeature-provider-android") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "provider-consumers"

include(":java", ":kotlin")
