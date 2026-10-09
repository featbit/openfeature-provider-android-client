plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "co.featbit.consumer.kotlin"
    compileSdk = 34
    defaultConfig {
        applicationId = "co.featbit.provider.consumer.kotlin"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("co.featbit:featbit-openfeature-provider-android:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
