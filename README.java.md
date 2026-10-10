# FeatBit OpenFeature Android Provider — Java

[Java](./README.java.md) | [Kotlin](./README.md)

## Introduction

The FeatBit OpenFeature Android provider is implemented in Kotlin with Java-compatible public APIs. It owns one FeatBit SDK client; applications evaluate flags and change identity through OpenFeature.

Java can call OpenFeature's non-suspending registration and context methods directly. They schedule work in the background; the Quick Start requires no custom bridge class.

## Get Started

### Installation

Requires Android API 21+ and a build toolchain that supports Java 11 bytecode. A Java application does not need the Kotlin Gradle plugin; Kotlin runtime dependencies are resolved transitively. The tested compiler and build-tool versions are listed in the [development guide](DEVELOPMENT.md#usage).

Enable AndroidX in your application's root `gradle.properties` for the SDK's lifecycle dependencies:

```properties
android.useAndroidX=true
```

Publish the development AAR locally from this repository:

```powershell
.\gradlew.bat :provider:publishReleasePublicationToTestRepository
```

In the application's `settings.gradle` (Groovy), add the local repository alongside Google and Maven Central. Replace the path with this checkout's absolute path:

```groovy
dependencyResolutionManagement {
    repositories {
        maven { url = uri('D:/Workspace/FeatBit/openfeature-provider-android-client/build/repository') }
        google()
        mavenCentral()
    }
}
```

In the application module's `build.gradle`:

```groovy
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_11
        targetCompatibility JavaVersion.VERSION_11
    }
}

dependencies {
    implementation 'co.featbit:featbit-openfeature-provider-android:0.2.0'
}
```

These instructions use the selected release version from the local repository; Maven Central publication has not yet been confirmed. OpenFeature and FeatBit are transitive API dependencies of the provider and do not need separate dependency declarations.

### Prerequisite

Use a client SDK key and endpoint addresses from your FeatBit deployment.

### Quick Start

Create one provider for your application and register it through OpenFeature. The options factory must use the supplied user; it runs once during initialization. Replace the SDK key and endpoint placeholders with your FeatBit deployment configuration.

OpenFeature provides non-suspending `setProvider` and `setEvaluationContext` methods that Java can call directly. No helper class is required for registration, context changes, or evaluation.

Run this code in your application initialization path and retain `api`, `provider`, and `client` for later use:

```java
import co.featbit.android.api.ClientOptions;
import co.featbit.android.api.Outcome;
import co.featbit.openfeature.android.FeatBitProvider;
import dev.openfeature.kotlin.sdk.Client;
import dev.openfeature.kotlin.sdk.ImmutableContext;
import dev.openfeature.kotlin.sdk.OpenFeatureAPI;
import dev.openfeature.kotlin.sdk.OpenFeatureAPIInstance;
import java.util.Collections;
import kotlinx.coroutines.Dispatchers;

OpenFeatureAPIInstance api = OpenFeatureAPI.OpenFeatureAPI;
Outcome<FeatBitProvider> built = FeatBitProvider.builder(
        applicationContext,
        user -> ClientOptions.builder()
                .user(user)
                .sdkKey("YOUR_CLIENT_SDK_KEY")
                .streamingUrl("wss://YOUR_EVALUATION_SERVER")
                .eventsUrl("https://YOUR_EVALUATION_SERVER")
                .build())
        .build();
if (!built.isSuccess()) {
    throw new IllegalArgumentException("Provider configuration: " + built.getCode());
}
FeatBitProvider provider = built.getValue();
Client client = api.getClient(null, null);

api.setProvider(
        provider,
        Dispatchers.getIO(),
        new ImmutableContext("user-123", Collections.emptyMap()));

Runnable refreshCheckout = () -> {
    dev.openfeature.kotlin.sdk.FlagEvaluationDetails<Boolean> details =
            client.getBooleanDetails("new-checkout", false);
    boolean enabled = details.getValue();
    // Apply enabled to your checkout state or view here.
    if (details.getErrorCode() != null) {
        android.util.Log.w("Flags", "Using fallback: " + details.getErrorCode());
    }
};
refreshCheckout.run(); // First read may use the fallback while initialization is pending.
```

Call `refreshCheckout.run()` again when the checkout screen is opened or refreshed, on the main thread if it updates views. Each call reads the current flag value; the first result does not update automatically. This minimal example does not subscribe to live changes. An error may mean the flag is missing or mistyped, not just that initialization is pending; do not retry indefinitely.

The later examples reuse the same `api`, `provider`, and `client`. `setProvider` returns immediately and initializes in the background; returning does not mean that registration or flag loading has completed. Read failures use the caller's default. Inspect evaluation details when you need to distinguish a flag value from a fallback. The known upstream status issue means that Ready alone does not prove a successful evaluation.

Keep the provider at application scope and avoid retaining destroyed Activities. The non-suspending methods return `void` and have no completion callback. Neither a delay nor polling Ready reliably establishes lifecycle completion. If you need to sequence registration, login/logout, and shutdown, use the suspend variants with Kotlin continuation handling. The [optional Java helper](docs/java-suspend-bridge.md) provides that separate integration; it is not required for the default-first evaluation example above.

## Known limitation

The currently integrated OpenFeature Kotlin SDK has a known issue that can report Ready after a newer Stale or Error event. The provider still checks the current identity and data readiness before returning a value. Use evaluation details to distinguish a successful flag evaluation from a fallback; do not rely on status alone. See the [verification record](docs/verification.md#observed-upstream-g02-results) for affected versions and reproduction details.

## Examples

- [Java Café sample](samples/java/README.md): a runnable Local/Live application using the OpenFeature Client.
- [Java acceptance consumer](consumer-tests/java/src/main/java/co/featbit/consumer/java/SmokeActivity.java): a Java interoperability fixture using local TestData.

The Café sample supports Local/Live configuration, flag editing, evaluation, context changes, Track, Flush, and diagnostics. The acceptance consumer uses TestData and does not contact a server or send analytics events.

## SDK

### Evaluation

Use the OpenFeature `Client`, which applies defaults and hooks. Do not call the provider's evaluation methods directly.

```java
boolean enabled = client.getBooleanValue("new-checkout", false);
String title = client.getStringValue("checkout-title", "Checkout");
int limit = client.getIntegerValue("item-limit", 10);
long count = client.getLongValue("display-count", 0L);
double discount = client.getDoubleValue("discount", 0.0);
dev.openfeature.kotlin.sdk.Value menu = client.getObjectValue(
        "menu", dev.openfeature.kotlin.sdk.Value.Null.INSTANCE);

dev.openfeature.kotlin.sdk.FlagEvaluationDetails<Boolean> details =
        client.getBooleanDetails("new-checkout", false);
if (details.getErrorCode() != null) {
    android.util.Log.w("Flags", "Evaluation failed: " + details.getErrorCode());
}
```

The core checks the declared flag type. A mismatch preserves the original caller default and invokes OpenFeature error hooks. Numeric flags use Double internally: Int/Long conversion can lose precision, truncate fractions, and saturate at the target range. Do not use Long evaluation to transport exact 64-bit identifiers.

### Identity

`api.setEvaluationContext` schedules a user change. It does not wait for registration or an earlier change; applications requiring that ordering need the completion-aware integration described above. Identity changes must go through OpenFeature so its context stays aligned with the provider. There is no public underlying FeatBit client or provider `identify` method.

For attributes, supply a complete context through the same OpenFeature lifecycle:

```java
java.util.Map<String, dev.openfeature.kotlin.sdk.Value> attributes = new java.util.HashMap<>();
attributes.put("name", new dev.openfeature.kotlin.sdk.Value.String("Jane"));
attributes.put("plan", new dev.openfeature.kotlin.sdk.Value.String("premium"));
ImmutableContext context = new ImmutableContext("user-456", attributes);
api.setEvaluationContext(context, Dispatchers.getIO());
```

The call schedules the change in the background. Immediately after it returns, the previous context may still be active; once reconciliation starts, unavailable evaluations return defaults. Do not treat the return as confirmation that the new user is active. Use the suspend API with completion sequencing if application actions must wait for the switch.

The default identity policy requires a nonempty targeting key. Whitespace-only keys are invalid. `name`, when provided, must be a nonblank string; `keyId` is reserved. Other supported scalar attributes are converted to text, while null values remain null. Empty attribute names are invalid; `featbit.sdk.*` is reserved when automatic attributes are enabled. Lists, structures, and timestamps are rejected as user attributes. Each context supplies the intended identity and attributes; do not rely on attributes from a previous identity being retained.

For persistent anonymous users, change both builders in Quick Start:

- Add `.identityPolicy(co.featbit.openfeature.android.IdentityPolicy.PERSISTENT_ANONYMOUS)` to the provider builder.
- Add `.anonymousEnabled(true)` to the `ClientOptions` builder, including when the initial user is named.

Then pass `new ImmutableContext("", Collections.emptyMap())` as the initial context to `api.setProvider` to start anonymously, or pass it to `api.setEvaluationContext` to log out. Both use an empty context with no attributes. The anonymous ID is reused rather than reset; a missing key with additional attributes is invalid.

### Track and connection management

```java
client.track("order-completed", new dev.openfeature.kotlin.sdk.TrackingEventDetails(
        29.95, new dev.openfeature.kotlin.sdk.ImmutableStructure(Collections.emptyMap())));

provider.flush().observe(outcome -> {
    android.util.Log.i("Flags", "Flush result: " + outcome.getCode());
});
```

Track accepts an event name and a finite numeric value. Structured tracking attributes are ignored with at most one warning per provider. Returning from Track does not acknowledge delivery. Inspect operation outcomes for Flush; do not equate submission with success.

Additional provider extensions are `getConnectionInformation()`, `setOffline(timeoutMillis)`, `setOnline(timeoutMillis)`, and `clearCache(scope, timeoutMillis)`. Connection information is diagnostic and does not establish readiness for the current user. Mode/cache timeouts include provider queueing; timing out after submission does not undo the core operation.

Operation observers have no main-thread delivery guarantee; marshal UI updates to the main thread yourself. `observe` returns an `Outcome<Registration>`; check it if observation must be guaranteed, retain the registration to detach later, and call its `close()` when no longer interested. Detaching an observer does not cancel the operation.

### Cleanup

Close the provider when the application-scoped integration is finished, rather than on each Activity recreation. Once registration has completed and no lifecycle changes are pending, Java can close the owned provider directly:

```java
provider.close().observe(outcome -> {
    if (outcome.isSuccess()) {
        android.util.Log.i("Flags", "Cleanup complete: "
                + outcome.getValue().getCleanupComplete()
                + "; undelivered events: " + outcome.getValue().getUndeliveredEvents());
    } else {
        android.util.Log.w("Flags", "Close result: " + outcome.getCode());
    }
});
```

`provider.close()` immediately closes admission and returns the same observable operation on repeated calls. Observe its outcome to determine whether cleanup completed; calling it alone is not an event-delivery acknowledgment.

Closing the provider does **not** detach it from OpenFeature, cancel OpenFeature's pending lifecycle jobs, or reset the global API. For full API shutdown, use OpenFeature's suspend `shutdown` via Kotlin interop (see the [optional Java helper](docs/java-suspend-bridge.md)), then observe `provider.close()` for cleanup completion. Only the component that owns the global API should shut it down.

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
