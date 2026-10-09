# Public API Design

## 1. API layers

Applications evaluate flags and Track through the OpenFeature Client, and use the OpenFeature API to register the provider, set context, and observe status. FeatBitProvider handles adaptation; applications do not need to create a FeatBitClient themselves.

The first release adds three top-level public types: `FeatBitProvider`, `FeatBitOptionsFactory`, and `IdentityPolicy`. Builder is nested in FeatBitProvider. Mappers, state coordination, value conversion, and event bridging remain internal.

The signatures below define the contract. The implementation is in the provider module; see the [verification record](./verification.md) for coverage. In construction/configuration APIs, `Context` means Android Context; `EvaluationContext` means the OpenFeature context.

## 2. Construction and configuration

| API | Type/result | Contract |
| --- | --- | --- |
| FeatBitProvider.builder(applicationContext, optionsFactory) | Builder with a Java static entry point | Retain only applicationContext; do not start networking, disk access, listeners, or SDK creation |
| Builder.identityPolicy(policy) | Builder | Default REQUIRE_TARGETING_KEY; alternative PERSISTENT_ANONYMOUS |
| Builder.initializationTimeoutMillis(value) | Builder | Default 10,000; positive with no arbitrary fixed upper limit; timeout ends only the wait |
| Builder.contextChangeTimeoutMillis(value) | Builder | Default 10,000; positive; the SDK continues synchronization after a transition |
| Builder.build() | Outcome&lt;FeatBitProvider&gt; | Validate only provider configuration using the core Outcome model; invoke the factory during initialize |
| FeatBitOptionsFactory.create(user: User?) | Outcome&lt;ClientOptions&gt; | Kotlin SAM/Java functional interface; receive the frozen creation user or null for explicit anonymity; invoke once during initial setup |

The factory must return newly constructed, immutable ClientOptions. It must not create a client and should not perform blocking I/O. initialUser must equal the supplied user in key/name/attributes. PERSISTENT_ANONYMOUS always requires anonymousEnabled=true; an anonymous initial user must be null. A mismatch fails before SDK creation. In named mode, the factory must not silently substitute another user.

Once the factory starts, the creation identity is fixed. Context changes do not trigger another factory call; after the single client is created, it adopts the latest valid identity. The core may initially synchronize the creation identity. Provider evaluation/Track remain blocked until the latest identity is adopted and ready. Closing can prevent an unsubmitted create; see the [initialization protocol](./architecture.md#6-initialization-protocol).

This design is necessary because ClientOptions is immutable and has no public copy/toBuilder, while the initial EvaluationContext is determined only at initialize. Accepting prebuilt ClientOptions and then calling identify could start synchronization and user events for the wrong user.

Configure endpoints, sdkKey, event settings, privacy attributes, Bootstrap, TestData/custom sources, caching, logging, retries, and background policy through ClientOptions. The provider adds no duplicate forwarding setters and does not change SDK defaults. A factory INVALID result fails initialization. Configuration errors use GENERAL; user mapping errors use INVALID_CONTEXT or TARGETING_KEY_MISSING.

## 3. Required OpenFeature interface

The source interface is OpenFeature Kotlin v0.9.0's `dev.openfeature.kotlin.sdk.FeatureProvider`.

| API | Return type | Provider behavior |
| --- | --- | --- |
| metadata | ProviderMetadata | Stable name `FeatBit` |
| hooks | List&lt;Hook&lt;*&gt;&gt; | Empty in the first release; OpenFeature manages application hooks |
| initialize(initialContext: EvaluationContext?) | suspend Unit | Map identity, create the SDK, install subscriptions, and await readiness; throw OpenFeatureError on failure |
| onContextSet(oldContext: EvaluationContext?, newContext: EvaluationContext) | suspend Unit | Validate and switch the current user, then await readiness; equal-valued contexts are accepted |
| getBooleanEvaluation(key, defaultValue: Boolean, context: EvaluationContext?) | ProviderEvaluation&lt;Boolean&gt; | Strict boolean parsing |
| getStringEvaluation(key, defaultValue: String, context: EvaluationContext?) | ProviderEvaluation&lt;String&gt; | Require string; do not convert numbers/JSON to strings |
| getIntegerEvaluation(key, defaultValue: Int, context: EvaluationContext?) | ProviderEvaluation&lt;Int&gt; | Require number; convert Double to Int with truncation toward zero and saturation |
| getLongEvaluation(key, defaultValue: Long, context: EvaluationContext?) | ProviderEvaluation&lt;Long&gt; | Require number; convert Double to Long, permitting precision loss, truncation, and saturation |
| getDoubleEvaluation(key, defaultValue: Double, context: EvaluationContext?) | ProviderEvaluation&lt;Double&gt; | Require number and a finite Double |
| getObjectEvaluation(key, defaultValue: Value, context: EvaluationContext?) | ProviderEvaluation&lt;Value&gt; | Require json; recursively map JSON objects, arrays, and valid scalars |
| track(trackingEventName, context: EvaluationContext?, details: TrackingEventDetails?) | Unit | Validate the current identity; convert numbers to finite Double values with possible precision loss; omit structured details; included in the first release |
| observe() | Flow&lt;OpenFeatureProviderEvents&gt; | Shared event source, no subscriber-count limit, bounded per-subscriber buffering, and no duplicate SDK listeners |
| shutdown() | Unit, not suspend | Close admission synchronously and start asynchronous cleanup; do not block the main thread waiting for SDK close |

All evaluation keys are String. Each default has the same type as the corresponding result; getObjectEvaluation uses OpenFeature Value. Applications normally call the OpenFeature Client's Value/Details methods rather than the provider methods directly.

## 4. FeatBit extensions

| API | Return type | Purpose and constraints |
| --- | --- | --- |
| getConnectionInformation() | ConnectionInformation? | Null before successful creation; afterwards, the current SDK snapshot, not a replacement for OpenFeature status |
| setOffline(timeoutMillis: Long) | Operation&lt;ModeResult&gt; | Serialize submission with identity changes; include queueing in the timeout; recover reads from actual mode after failure |
| setOnline(timeoutMillis: Long) | Operation&lt;ModeResult&gt; | Include queueing; treat the operation result separately from actual mode/readiness |
| flush() | Operation&lt;FlushResult&gt; | Preserve EMPTY/ALL_DELIVERED/PROCESSED_WITH_LOSS and failure Outcomes; external budget is requestTimeoutMillis, including queueing |
| clearCache(scope: CacheScope, timeoutMillis: Long) | Operation&lt;CacheClearResult&gt; | Retain SDK scopes/results; include queueing; a post-submission timeout does not prove the cache was not cleared |
| close() | Operation&lt;CloseResult&gt; | Same cleanup path as shutdown, with observable undeliveredEvents/cleanupComplete; repeated calls return the same operation |

Budgets start when the provider accepts a call. Do not submit work that expires before submission; a timeout afterwards does not cancel core work. A failed mode operation does not prove the mode stayed unchanged. The owner reads actual mode and restores admission for the current valid identity without changing the original failure into success. See [queueing and timeouts](./architecture.md#extension-operation-queueing-and-timeouts).

Before client creation/subscription installation, extensions other than close return a completed INVALID operation with `provider_not_initialized`; after closing, they return CLOSED. If creation succeeded but remote readiness timed out, offline/online, flush, and other recovery operations remain available. A successful return from initialize is not a prerequisite. clearCache requires a stable identity; other boundaries are in the [state table](./architecture.md#4-state-machine). getConnectionInformation may return a retained final CLOSED snapshot after shutdown.

close must also work before SDK creation: prevent subsequent initialization, await or clean up late creation results, and avoid leaks. Never report cleanupComplete=true while leaving a late client unowned. If cleanup cannot be confirmed within the budget, report failure or cleanupComplete=false and continue late cleanup.

Do not expose getClient, identify, identifyAnonymous, or resetAnonymousIdentity: they bypass OpenFeature context and break identity/event consistency. Anonymous reset is not provided initially. Logging out sets an empty context and returns to the persisted anonymous identity; returning to it does not create a new anonymous ID.

## 5. Application workflow

1. Configure FeatBit endpoints, credentials, events, and cache through the factory, using the supplied User in ClientOptions.
2. Build FeatBitProvider and an OpenFeature ImmutableContext with the user ID as targetingKey.
3. Register and wait with OpenFeature setProviderAndWait, and inspect status. Upstream converts initialization exceptions into status, so return alone does not prove success.
4. Obtain an OpenFeature Client and use its six Value/Details methods and track.
5. Trigger login, attribute changes, or logout through OpenFeature setEvaluationContext. Use status/events to determine completion instead of arbitrary delays.
6. Observe provider.flush/close when needed. OpenFeature shutdown calls provider.shutdown but does not imply that all FeatBit events have been delivered.

Kotlin callers can use the existing ClientAdapters.await for extension Operations; Java callers use Operation.observe. The provider does not duplicate OpenFeature's coroutine entry points as another Java OpenFeature SDK.

## 6. Detailed design dependencies

The provider adds only the three public types listed above. The core dependency is the released `co.featbit:featbit-client-android:0.2.0`. Identity methods are directly available on FeatBitClient; evaluations reuse existing Detail methods and retain the Double model. No additional core interfaces are required; see the [core contract](./core-sdk-contract.md). OpenFeature 0.9.0 is the development/reproduction baseline. Complete status guarantees require a dependency containing the [initialization status arbitration fix](./architecture.md#10-openfeature-v090-status-handoff-limitations); The current dependency remains 0.9.0; an upstream fix is outside this release-fix scope.
