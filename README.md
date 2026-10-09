# FeatBit OpenFeature Android Provider

[Kotlin](./README.md) | [Java](./README.java.md)

## Introduction

The FeatBit OpenFeature Android provider is implemented in Kotlin with Java-compatible public APIs. It owns one FeatBit SDK client; applications evaluate flags and change identity through OpenFeature.

In Kotlin, call OpenFeature's suspend lifecycle methods from a coroutine. The owner below exposes suspend functions without blocking the Android main thread.

This is the unreleased `0.1.0-SNAPSHOT` development implementation, using FeatBit Android SDK `0.2.0` and OpenFeature Kotlin SDK `0.9.0`. OpenFeature lifecycle completion can overwrite Stale/Fatal status; tests reproduce this upstream issue and G02 is a known upstream limitation outside the current FeatBit release-fix scope. Provider evaluation checks remain active. See the [verification record](docs/verification.md).

## Get Started

### Installation

Requires Android API 21+. The verified build uses JDK 17, Java 11 source/bytecode, AGP 8.10.1, and Gradle 8.11.1. The verified Kotlin consumer uses Kotlin 2.2.10. A Java application does not need the Kotlin Gradle plugin; Kotlin runtime dependencies are still required and are resolved transitively.

Publish the development AAR locally from this repository:

```powershell
.\gradlew.bat :provider:publishReleasePublicationToTestRepository
```

In the application's `settings.gradle.kts`, add the local repository alongside Google and Maven Central. Replace the path with this checkout's absolute path:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("D:/Workspace/FeatBit/openfeature-provider-android-client/build/repository") }
        google()
        mavenCentral()
    }
}
```

In the application module's `build.gradle.kts` (using the Android application and Kotlin Android plugins):

```kotlin
android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation("co.featbit:featbit-openfeature-provider-android:0.1.0-SNAPSHOT")
}
```

This snapshot has not been published to Maven Central. OpenFeature and FeatBit are transitive API dependencies of the provider.

### Prerequisite

Use a client SDK key and endpoint addresses from your FeatBit deployment.

### Quick Start

Keep the provider in an application-scoped owner. The options factory must use the supplied user; it runs once during initialization. Replace the SDK key and endpoint placeholders with your FeatBit deployment configuration.

The following class is application code, not an additional provider API. Its lifecycle methods are suspend functions. Serialize registration, identity changes, and shutdown; wait for one call to return before starting the next lifecycle operation.

```kotlin
import android.content.Context
import co.featbit.android.api.ClientOptions
import co.featbit.openfeature.android.FeatBitProvider
import dev.openfeature.kotlin.sdk.Client
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import dev.openfeature.kotlin.sdk.OpenFeatureStatus
import kotlinx.coroutines.Dispatchers

class FeatureFlags(context: Context) {
    private val api = OpenFeatureAPI
    val provider: FeatBitProvider
    val client: Client
    val status: OpenFeatureStatus get() = api.getStatus()

    init {
        val built = FeatBitProvider.builder(context.applicationContext) { user ->
            ClientOptions.builder()
                .user(user)
                .sdkKey("YOUR_CLIENT_SDK_KEY")
                .streamingUrl("wss://YOUR_EVALUATION_SERVER")
                .eventsUrl("https://YOUR_EVALUATION_SERVER")
                .build()
        }
            .initializationTimeoutMillis(10_000L)
            .contextChangeTimeoutMillis(10_000L)
            .build()
        require(built.isSuccess) { "Provider configuration: ${built.code}" }
        provider = requireNotNull(built.value)
        client = api.getClient("my-android-app", "1.0.0")
    }

    suspend fun start(targetingKey: String) {
        api.setProviderAndWait(provider, ImmutableContext(targetingKey), Dispatchers.IO)
    }

    suspend fun changeUser(targetingKey: String) {
        api.setEvaluationContextAndWait(ImmutableContext(targetingKey))
    }

    suspend fun changeContext(context: ImmutableContext) {
        api.setEvaluationContextAndWait(context)
    }

    // Detaches the global provider. Observe provider.close() separately for cleanup completion.
    suspend fun stop() {
        api.shutdown()
    }
}
```

Create one `FeatureFlags` owner and call `start` once from a coroutine owned by your application. The following snippet runs inside that coroutine:

```kotlin
val flags = FeatureFlags(applicationContext)
try {
    flags.start("user-123")
    android.util.Log.i("Flags", "OpenFeature status: ${flags.status}")
    val enabled = flags.client.getBooleanValue("new-checkout", false)
    // Update application state using enabled; false is the safe fallback.
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    android.util.Log.e("Flags", "Registration failed", failure)
}
```

A normally returning suspend call means the lifecycle call completed, not that flags are necessarily ready. OpenFeature converts initialization failures into status. Inspect status and evaluation details; because of the known upstream status issue, even Ready is not sufficient evidence of a successful flag evaluation. Before initialization, during reconciliation, or after failure, OpenFeature returns caller defaults when evaluation is unavailable.

Keep the owner and its coroutine scope at application scope, and avoid retaining destroyed Activities. Use structured concurrency and propagate cancellation. Coroutine cancellation does not prove an already submitted core operation was undone. Run UI updates on the main dispatcher. See the [Kotlin Café sample](samples/kotlin/README.md) for a lifecycle-aware UI integration.

## Examples

- [Kotlin Café sample](samples/kotlin/README.md): a runnable Local/Live application using the OpenFeature Client.
- [Kotlin acceptance consumer](consumer-tests/kotlin/src/main/kotlin/co/featbit/consumer/kotlin/SmokeActivity.kt): a Kotlin interoperability fixture using local TestData.

The Café sample supports Local/Live configuration, flag editing, evaluation, context changes, Track, Flush, and diagnostics. The acceptance consumer uses TestData and does not contact a server or send analytics events.

## SDK

### Evaluation

Use the OpenFeature `Client`, which applies defaults and hooks. Do not call the provider's evaluation methods directly.

```kotlin
val client = flags.client
val enabled = client.getBooleanValue("new-checkout", false)
val title = client.getStringValue("checkout-title", "Checkout")
val limit = client.getIntegerValue("item-limit", 10)
val count = client.getLongValue("display-count", 0L)
val discount = client.getDoubleValue("discount", 0.0)
val menu = client.getObjectValue("menu", dev.openfeature.kotlin.sdk.Value.Null)

val details = client.getBooleanDetails("new-checkout", false)
if (details.errorCode != null) {
    android.util.Log.w("Flags", "Evaluation failed: ${details.errorCode}")
}
```

The core checks the declared flag type. A mismatch preserves the original caller default and invokes OpenFeature error hooks. Numeric flags use Double internally: Int/Long conversion can lose precision, truncate fractions, and saturate at the target range. Do not use Long evaluation to transport exact 64-bit identifiers.

### Identity

Call `flags.changeUser("user-456")` from a coroutine after the previous lifecycle operation completes. Identity changes must go through OpenFeature so its context stays aligned with the provider. There is no public underlying FeatBit client or provider `identify` method.

For attributes, supply a complete context through the same OpenFeature lifecycle:

```kotlin
val attributes = mapOf(
    "name" to dev.openfeature.kotlin.sdk.Value.String("Jane"),
    "plan" to dev.openfeature.kotlin.sdk.Value.String("premium"),
)
val context = ImmutableContext("user-456", attributes)
try {
    flags.changeContext(context)
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    android.util.Log.e("Flags", "Context change failed", failure)
}
```

The default identity policy requires a nonempty targeting key. Whitespace-only keys are invalid. `name`, when provided, must be a nonblank string; `keyId` is reserved. Other supported scalar attributes are converted to text, while null values remain null. Empty attribute names are invalid; `featbit.sdk.*` is reserved when automatic attributes are enabled. Lists, structures, and timestamps are rejected as user attributes. Each context supplies the intended identity and attributes; do not rely on attributes from a previous identity being retained.

For persistent anonymous users, change both parts of the builder in `FeatureFlags`:

- Add `.identityPolicy(co.featbit.openfeature.android.IdentityPolicy.PERSISTENT_ANONYMOUS)` to the provider builder.
- Add `.anonymousEnabled(true)` to the `ClientOptions` builder, including when the initial user is named.

Then `flags.start("")` starts anonymously, and `flags.changeUser("")` logs out to the persisted anonymous identity. These methods create an empty context with no attributes. The anonymous ID is reused rather than reset; a missing key with additional attributes is invalid.

### Track and connection management

```kotlin
flags.client.track("order-completed", dev.openfeature.kotlin.sdk.TrackingEventDetails(
    29.95, dev.openfeature.kotlin.sdk.ImmutableStructure(emptyMap()),
))

flags.provider.flush().observe { outcome ->
    android.util.Log.i("Flags", "Flush result: ${outcome.code}")
}
```

Track accepts an event name and a finite numeric value. Structured tracking attributes are ignored with at most one warning per provider. Returning from Track does not acknowledge delivery. Inspect operation outcomes for Flush; do not equate submission with success.

Additional provider extensions are `getConnectionInformation()`, `setOffline(timeoutMillis)`, `setOnline(timeoutMillis)`, and `clearCache(scope, timeoutMillis)`. Connection information is diagnostic and does not establish readiness for the current user. Mode/cache timeouts include provider queueing; timing out after submission does not undo the core operation.

Operation observers have no main-thread delivery guarantee; marshal UI updates to the main thread yourself. `observe` returns an `Outcome<Registration>`; check it if observation must be guaranteed, retain the registration to detach later, and call its `close()` when no longer interested. Detaching an observer does not cancel the operation.

### Cleanup

Stop the application-scoped integration when its owner is finished, rather than on each Activity recreation. The example owns the global OpenFeature API; do not call its shutdown if another component has taken ownership of that API.

```kotlin
try {
    flags.stop()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    android.util.Log.e("Flags", "OpenFeature shutdown failed", failure)
} finally {
    flags.provider.close().observe { outcome ->
        if (outcome.isSuccess) {
            val result = requireNotNull(outcome.value)
            android.util.Log.i("Flags", "Cleanup complete: ${result.cleanupComplete}" +
                "; undelivered events: ${result.undeliveredEvents}")
        } else {
            android.util.Log.w("Flags", "Close result: ${outcome.code}")
        }
    }
}
```

OpenFeature shutdown initiates provider cleanup without waiting for it. `provider.close()` immediately closes admission and returns the same observable operation on repeated calls, so it can also observe cleanup already initiated by shutdown. A returning shutdown call is not a cleanup or event-delivery acknowledgment.

## See Also

- [Maven Central release workflow](docs/release.md)

- [Development notes and build commands](DEVELOPMENT.md): local publication and acceptance checks.
- [Design overview](docs/README.md)
- [Detailed architecture](docs/architecture.md)
- [Public API](docs/api-design.md)
- [Behavior specification](docs/behavior-design.md)
- [Core SDK integration contract](docs/core-sdk-contract.md)
- [Detailed acceptance protocol](docs/architecture-validation.md)
- [Source references and implementation acceptance](docs/implementation-plan.md)
- [Verification record](docs/verification.md): completed checks and remaining release gates.
