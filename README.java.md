# FeatBit OpenFeature Android Provider — Java

[Java](./README.java.md) | [Kotlin](./README.md)

## Introduction

The FeatBit OpenFeature Android provider is implemented in Kotlin with Java-compatible public APIs. It owns one FeatBit SDK client; applications evaluate flags and change identity through OpenFeature.

In Java, OpenFeature's asynchronous lifecycle methods use Kotlin continuations. The helper below adapts them to callbacks without blocking the Android main thread.

This is the unreleased `0.1.0-SNAPSHOT` development implementation, using FeatBit Android SDK `0.2.0` and OpenFeature Kotlin SDK `0.9.0`. OpenFeature lifecycle completion can overwrite Stale/Fatal status; tests reproduce this upstream issue and G02 is a known upstream limitation outside the current FeatBit release-fix scope. Provider evaluation checks remain active. See the [verification record](docs/verification.md).

## Get Started

### Installation

Requires Android API 21+. The verified build uses JDK 17, Java 11 source/bytecode, AGP 8.10.1, and Gradle 8.11.1. The verified Kotlin consumer uses Kotlin 2.2.10. A Java application does not need the Kotlin Gradle plugin; Kotlin runtime dependencies are still required and are resolved transitively.

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
    implementation 'co.featbit:featbit-openfeature-provider-android:0.1.0-SNAPSHOT'
}
```

This snapshot has not been published to Maven Central. OpenFeature and FeatBit are transitive API dependencies of the provider.

### Prerequisite

Use a client SDK key and endpoint addresses from your FeatBit deployment.

### Quick Start

Keep the provider in an application-scoped owner. The options factory must use the supplied user; it runs once during initialization. Replace the SDK key and endpoint placeholders with your FeatBit deployment configuration.

The following class is application code, not an additional provider API. Its completion callbacks run on the Android main thread. Serialize registration, identity changes, and shutdown; wait for one callback before starting the next lifecycle operation.

```java
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import co.featbit.android.api.ClientOptions;
import co.featbit.android.api.Outcome;
import co.featbit.openfeature.android.FeatBitProvider;
import dev.openfeature.kotlin.sdk.Client;
import dev.openfeature.kotlin.sdk.ImmutableContext;
import dev.openfeature.kotlin.sdk.OpenFeatureAPI;
import dev.openfeature.kotlin.sdk.OpenFeatureAPIInstance;
import dev.openfeature.kotlin.sdk.OpenFeatureStatus;
import java.util.Collections;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.Dispatchers;

public final class FeatureFlags {
    private final OpenFeatureAPIInstance api = OpenFeatureAPI.OpenFeatureAPI;
    private final FeatBitProvider provider;
    private final Client client;
    private final Handler main = new Handler(Looper.getMainLooper());

    public FeatureFlags(Context context) {
        Outcome<FeatBitProvider> built = FeatBitProvider.builder(
                context.getApplicationContext(),
                user -> ClientOptions.builder()
                        .user(user)
                        .sdkKey("YOUR_CLIENT_SDK_KEY")
                        .streamingUrl("wss://YOUR_EVALUATION_SERVER")
                        .eventsUrl("https://YOUR_EVALUATION_SERVER")
                        .build())
                .initializationTimeoutMillis(10_000L)
                .contextChangeTimeoutMillis(10_000L)
                .build();
        if (!built.isSuccess()) {
            throw new IllegalArgumentException("Provider configuration: " + built.getCode());
        }
        provider = built.getValue();
        client = api.getClient("my-android-app", "1.0.0");
    }

    public void start(String targetingKey, Completion completion) {
        run(cont -> api.setProviderAndWait(
                provider,
                new ImmutableContext(targetingKey, Collections.emptyMap()),
                Dispatchers.getIO(),
                cont), completion);
    }

    public void changeUser(String targetingKey, Completion completion) {
        run(cont -> api.setEvaluationContextAndWait(
                new ImmutableContext(targetingKey, Collections.emptyMap()), cont), completion);
    }

    public void changeContext(ImmutableContext context, Completion completion) {
        run(cont -> api.setEvaluationContextAndWait(context, cont), completion);
    }

    public Client client() { return client; }
    public FeatBitProvider provider() { return provider; }
    public OpenFeatureStatus status() { return api.getStatus(); }

    // Detaches the global provider. Observe provider.close() separately for cleanup completion.
    public void stop(Completion completion) {
        run(cont -> api.shutdown(cont), completion);
    }

    public interface Completion {
        void complete(Throwable failure);
    }

    private interface SuspendCall {
        Object invoke(Continuation<? super Unit> continuation);
    }

    private void run(SuspendCall call, Completion completion) {
        Continuation<Unit> continuation = new Continuation<Unit>() {
            @Override public CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override public void resumeWith(Object result) {
                Throwable failure = null;
                try {
                    ResultKt.throwOnFailure(result);
                } catch (Throwable error) {
                    failure = error;
                }
                Throwable deliveredFailure = failure;
                main.post(() -> completion.complete(deliveredFailure));
            }
        };
        try {
            Object result = call.invoke(continuation);
            if (result != IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                continuation.resumeWith(result);
            }
        } catch (Throwable failure) {
            main.post(() -> completion.complete(failure));
        }
    }
}
```

Create one `FeatureFlags` owner and call `start` once:

```java
FeatureFlags flags = new FeatureFlags(applicationContext);
flags.start("user-123", failure -> {
    if (failure != null) {
        android.util.Log.e("Flags", "Registration failed", failure);
        return;
    }
    android.util.Log.i("Flags", "OpenFeature status: " + flags.status());
    boolean enabled = flags.client().getBooleanValue("new-checkout", false);
    // Update application state using enabled; false is the safe fallback.
});
```

A successful callback means the lifecycle call completed, not that flags are necessarily ready. OpenFeature converts initialization failures into status. Inspect status and evaluation details; because of the known upstream status issue, even Ready is not sufficient evidence of a successful flag evaluation. Before initialization, during reconciliation, or after failure, OpenFeature returns caller defaults when evaluation is unavailable.

The minimal helper has no cancellation handle. Keep it and its callbacks in the application-scoped owner, and avoid retaining destroyed Activities. For a lifecycle-aware UI example with cancellable Flow observation, see the [Java sample bridge](samples/java/src/main/java/co/featbit/sample/java/OpenFeatureBridge.java).

## Examples

- [Java Café sample](samples/java/README.md): a runnable Local/Live application using the OpenFeature Client.
- [Java acceptance consumer](consumer-tests/java/src/main/java/co/featbit/consumer/java/SmokeActivity.java): a Java interoperability fixture using local TestData.

The Café sample supports Local/Live configuration, flag editing, evaluation, context changes, Track, Flush, and diagnostics. The acceptance consumer uses TestData and does not contact a server or send analytics events.

## SDK

### Evaluation

Use the OpenFeature `Client`, which applies defaults and hooks. Do not call the provider's evaluation methods directly.

```java
Client client = flags.client();
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

Call `flags.changeUser("user-456", completion)` after the previous lifecycle operation completes. Identity changes must go through OpenFeature so its context stays aligned with the provider. There is no public underlying FeatBit client or provider `identify` method.

For attributes, supply a complete context through the same OpenFeature lifecycle:

```java
java.util.Map<String, dev.openfeature.kotlin.sdk.Value> attributes = new java.util.HashMap<>();
attributes.put("name", new dev.openfeature.kotlin.sdk.Value.String("Jane"));
attributes.put("plan", new dev.openfeature.kotlin.sdk.Value.String("premium"));
ImmutableContext context = new ImmutableContext("user-456", attributes);
flags.changeContext(context, failure -> {
    if (failure != null) {
        android.util.Log.e("Flags", "Context change failed", failure);
    }
});
```

The default identity policy requires a nonempty targeting key. Whitespace-only keys are invalid. `name`, when provided, must be a nonblank string; `keyId` is reserved. Other supported scalar attributes are converted to text, while null values remain null. Empty attribute names are invalid; `featbit.sdk.*` is reserved when automatic attributes are enabled. Lists, structures, and timestamps are rejected as user attributes. Each context supplies the intended identity and attributes; do not rely on attributes from a previous identity being retained.

For persistent anonymous users, change both parts of the builder in `FeatureFlags`:

- Add `.identityPolicy(co.featbit.openfeature.android.IdentityPolicy.PERSISTENT_ANONYMOUS)` to the provider builder.
- Add `.anonymousEnabled(true)` to the `ClientOptions` builder, including when the initial user is named.

Then `flags.start("", completion)` starts anonymously, and `flags.changeUser("", completion)` logs out to the persisted anonymous identity. These methods create an empty context with no attributes. The anonymous ID is reused rather than reset; a missing key with additional attributes is invalid.

### Track and connection management

```java
flags.client().track("order-completed", new dev.openfeature.kotlin.sdk.TrackingEventDetails(
        29.95, new dev.openfeature.kotlin.sdk.ImmutableStructure(Collections.emptyMap())));

flags.provider().flush().observe(outcome -> {
    android.util.Log.i("Flags", "Flush result: " + outcome.getCode());
});
```

Track accepts an event name and a finite numeric value. Structured tracking attributes are ignored with at most one warning per provider. Returning from Track does not acknowledge delivery. Inspect operation outcomes for Flush; do not equate submission with success.

Additional provider extensions are `getConnectionInformation()`, `setOffline(timeoutMillis)`, `setOnline(timeoutMillis)`, and `clearCache(scope, timeoutMillis)`. Connection information is diagnostic and does not establish readiness for the current user. Mode/cache timeouts include provider queueing; timing out after submission does not undo the core operation.

Operation observers have no main-thread delivery guarantee; marshal UI updates to the main thread yourself. `observe` returns an `Outcome<Registration>`; check it if observation must be guaranteed, retain the registration to detach later, and call its `close()` when no longer interested. Detaching an observer does not cancel the operation.

### Cleanup

Stop the application-scoped integration when its owner is finished, rather than on each Activity recreation. The example owns the global OpenFeature API; do not call its shutdown if another component has taken ownership of that API.

```java
flags.stop(failure -> {
    if (failure != null) {
        android.util.Log.e("Flags", "OpenFeature shutdown failed", failure);
    }
    flags.provider().close().observe(outcome -> {
        if (outcome.isSuccess()) {
            android.util.Log.i("Flags", "Cleanup complete: "
                    + outcome.getValue().getCleanupComplete()
                    + "; undelivered events: " + outcome.getValue().getUndeliveredEvents());
        } else {
            android.util.Log.w("Flags", "Close result: " + outcome.getCode());
        }
    });
});
```

OpenFeature shutdown initiates provider cleanup without waiting for it. `provider.close()` immediately closes admission and returns the same observable operation on repeated calls, so it can also observe cleanup already initiated by shutdown. A completed shutdown callback is not a cleanup or event-delivery acknowledgment.

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
