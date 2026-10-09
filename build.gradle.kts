plugins {
    id("org.jetbrains.dokka") version "2.2.0" apply false
    id("com.android.library") version "8.10.1" apply false
    kotlin("android") version "2.2.10" apply false
    id("com.diffplug.spotless") version "7.0.2"
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    java {
        target("consumer-tests/java/src/**/*.java", "samples/java/src/**/*.java")
        googleJavaFormat("1.24.0").aosp()
    }
    kotlin {
        target(
            "provider/src/**/*.kt",
            "consumer-tests/kotlin/src/**/*.kt",
            "samples/kotlin/src/**/*.kt",
        )
        ktfmt("0.54").kotlinlangStyle()
    }
    kotlinGradle {
        target(
            "*.gradle.kts",
            "provider/*.gradle.kts",
            "consumer-tests/**/*.gradle.kts",
            "samples/**/*.gradle.kts",
        )
        ktfmt("0.54").kotlinlangStyle()
    }
}
