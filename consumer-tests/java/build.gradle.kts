plugins { id("com.android.application") }

android {
    namespace = "co.featbit.consumer.java"
    compileSdk = 34
    defaultConfig {
        applicationId = "co.featbit.provider.consumer.java"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

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
    implementation(
        "co.featbit:featbit-openfeature-provider-android:${providers.gradleProperty("providerVersion").getOrElse("0.1.0-SNAPSHOT")}"
    )
    testImplementation("junit:junit:4.13.2")
}
