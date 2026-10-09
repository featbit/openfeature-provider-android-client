plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

group = "co.featbit"

version = "0.1.0-SNAPSHOT"

android {
    namespace = "co.featbit.openfeature.android"
    compileSdk = 34
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    testOptions { unitTests.isReturnDefaultValues = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api("co.featbit:featbit-client-android:0.2.0")
    api("dev.openfeature:kotlin-sdk:0.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "featbit-openfeature-provider-android"
                pom {
                    name = "FeatBit OpenFeature Android Provider"
                    description = "OpenFeature provider for the FeatBit Android SDK"
                    url = "https://github.com/featbit/openfeature-provider-android-client"
                    licenses {
                        license {
                            name = "MIT"
                            url = "https://opensource.org/license/mit"
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "test"
                url =
                    uri(
                        providers
                            .gradleProperty("testRepository")
                            .orElse(
                                rootProject.layout.buildDirectory
                                    .dir("repository")
                                    .get()
                                    .asFile
                                    .absolutePath
                            )
                            .get()
                    )
            }
        }
    }
}
