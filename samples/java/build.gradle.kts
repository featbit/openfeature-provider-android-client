plugins { id("com.android.application") }

android {
    namespace = "co.featbit.sample.java"
    compileSdk = 34
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "co.featbit.openfeature.sample.java"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").apply {
        res.srcDir("../shared/res")
        assets.srcDir("../shared/assets")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sample-only local installability. Never used for SDK publication.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    lint {
        abortOnError = true
        disable += "GradleDependency"
    }
}

dependencies {
    implementation(
        "co.featbit:featbit-openfeature-provider-android:${providers.gradleProperty("providerVersion").getOrElse("0.1.0-SNAPSHOT")}"
    )
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
