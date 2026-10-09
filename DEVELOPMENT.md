# FeatBit OpenFeature Android Provider Development

An OpenFeature Kotlin provider for the FeatBit Android SDK, implemented in Kotlin with Java-compatible construction and extension APIs.

For Java setup, asynchronous initialization, evaluation, and cleanup, see the [Java README](README.java.md).

The current development implementation is `0.1.0-SNAPSHOT` and has not been officially released. It depends on FeatBit `0.2.0` and OpenFeature Kotlin `0.9.0`. Tests reproduce an OpenFeature issue in which lifecycle completion overwrites Stale/Fatal status: the provider still enforces evaluation admission, but the upstream public status can be inaccurate. G02 remains a release blocker; see the [verification record](docs/verification.md).

## Usage

Local AAR coordinates: `co.featbit:featbit-openfeature-provider-android:0.1.0-SNAPSHOT`. Requires Android API 21+. The verified Kotlin consumer version is 2.2.10, with JVM 11 bytecode. Builds use JDK 17, AGP 8.10.1, and Gradle 8.11.1.

```kotlin
val provider = FeatBitProvider.builder(applicationContext) { user ->
    ClientOptions.builder()
        .user(user)
        .sdkKey("YOUR_CLIENT_SDK_KEY")
        .streamingUrl("wss://YOUR_EVALUATION_SERVER")
        .eventsUrl("https://YOUR_EVALUATION_SERVER")
        .build()
}.build().value ?: error("Invalid provider configuration")

// Register from a coroutine. Check status; upstream converts initialization failures into status.
OpenFeatureAPI.setProviderAndWait(provider, ImmutableContext("user-123"))
val enabled = OpenFeatureAPI.getClient().getBooleanValue("new-checkout", false)

// Update identity and attributes through OpenFeature, without accessing the underlying client.
OpenFeatureAPI.setEvaluationContextAndWait(ImmutableContext("user-456"))

// Admission closes immediately. Operation completion determines the cleanup result.
provider.close().observe { outcome ->
    // Check outcome.code and, on success, cleanupComplete / undeliveredEvents.
}
```

Use the endpoint addresses required by your FeatBit deployment. The factory must configure the supplied user; it is invoked once during initialization. The provider exclusively owns its SDK client.

Anonymous identity requires both `identityPolicy(IdentityPolicy.PERSISTENT_ANONYMOUS)` and `ClientOptions.anonymousEnabled(true)`. An empty context with no attributes selects the persisted anonymous user; the default policy requires a nonempty targetingKey. On logout, set `ImmutableContext()` to return to the persisted anonymous identity without resetting its ID.

All six evaluation types are supported: Boolean, String, Int, Long, Double, and Object. Type errors let OpenFeature preserve the caller's original default and invoke error hooks. Numbers retain the Double model: Int/Long conversions allow precision loss, truncation, and saturation. Track values are converted to finite Double values; structured attributes are not sent. Each provider logs at most one warning about ignored structured attributes.

Provider extensions include `getConnectionInformation`, `setOffline`, `setOnline`, `flush`, `clearCache`, and `close`. Mode/cache budgets include queueing; a timeout after submission does not undo the core operation. `shutdown()` does not wait for cleanup, while `close()` returns an observable result.

Complete runnable integration examples:

- [Kotlin consumer](consumer-tests/kotlin/src/main/kotlin/co/featbit/consumer/kotlin/SmokeActivity.kt): the real OpenFeature Client, six evaluation types, named/anonymous transitions, error defaults, and cleanup.
- [Java consumer](consumer-tests/java/src/main/java/co/featbit/consumer/java/SmokeActivity.java): Builder/SAM usage, suspend Continuation invocation, evaluation, and cleanup.

These acceptance apps use TestData. They do not contact a server or send analytics events.

## Build and verification

Configure JDK 17 and the Android SDK through `ANDROID_HOME` or `local.properties`.

```powershell
.\gradlew.bat spotlessCheck :provider:testDebugUnitTest :provider:lintDebug
.\gradlew.bat :provider:publishReleasePublicationToTestRepository
python tools/acceptance.py
# Optional: install and run both languages and both build variants on a running device.
python tools/acceptance.py --device emulator-5554
```

Direct Gradle publication writes to `build/repository`. The acceptance script instead stages each run in a new `build/acceptance/<run-id>/repository` and passes that repository to the independent Java/Kotlin consumers. `--repository <new-directory>` selects an explicit fresh destination; existing directories are rejected. Consumers and samples accept `-PtestRepository=<absolute-path>` to use that same publication. Remote upload is available only through the manually dispatched [release workflow](docs/release.md).

Acceptance runs the script's own unit tests, formatting, provider unit tests and Debug/Release lint, publication/API checks, and both consumers' Debug/Release (R8) builds, JVM unit tests and Debug/Release lint. Tests use the published AAR through Maven, not a provider project dependency or internal test hooks. JVM tests cover configuration rejection, caller defaults/error details, and initial context mapping; real core lifecycle and identity-switch execution remain device acceptance responsibilities. Stub Android methods are enabled only for JVM fixtures.

`--rerun-tasks` forces new test execution; missing, failed, or all-skipped XML test results fail acceptance. Each run retains command logs and a report with test counts and artifact SHA-256 hashes. Device success requires the current run's complete unique marker; failure takes precedence over success, and timeout fails the run.

### API and publication contract

`tools/check_publication.py` resolves the exact snapshot selected by Maven metadata. It requires AAR, POM, Gradle module metadata, sources JAR, and generated documentation JAR, validates SHA-256 sidecars and the AAR against this build, checks public classes/source presence, and verifies coordinates, dependency versions/scopes, and API/runtime module variants. The `javadoc` classifier contains Dokka HTML. Signing and Central upload are opt-in release operations; see [release instructions](docs/release.md).

`tools/check_api.py` compares the published AAR's public JVM declarations and descriptors with `provider/api/jvm-api.txt`, rejects bytecode newer than Java 11 and unexpected bundled packages. The JVM baseline deliberately includes Kotlin-internal types that compile as public: it detects changes to that Java-visible surface without declaring those types supported application APIs. It is not a complete Kotlin metadata/nullability compatibility checker. Review intentional changes before explicitly updating the baseline:

```powershell
python tools/check_api.py --update
python -B -m unittest discover -s tools -p 'test_*.py'
```

The update command reads `provider/build/outputs/aar/provider-release.aar`; build the current release first. CI never updates the baseline automatically. Passing these checks does not resolve G02 or authorize a release.

## Documentation

- [Implementation and verification record](docs/verification.md)
- [Design overview](docs/README.md)
- [Detailed architecture](docs/architecture.md)
- [Public API](docs/api-design.md)
- [Behavior specification](docs/behavior-design.md)
- [Core SDK integration contract](docs/core-sdk-contract.md)
- [Detailed acceptance protocol](docs/architecture-validation.md)
- [Source references and implementation acceptance](docs/implementation-plan.md)

## Café sample apps

[OpenFeature Café samples](samples/README.md) provide Kotlin and Java versions of the Android SDK Café demo, with Local/Live configuration, flag editing and evaluation, context switching, Track, Flush and provider diagnostics. All flag evaluation and custom tracking use an OpenFeature Client backed by FeatBitProvider.
