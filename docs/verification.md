# Implementation and Verification Record

Date: 2026-10-09. The provider is implemented as development version `0.1.0-SNAPSHOT` and has not been officially released. This record describes verification performed in this repository and does not inherit core SDK test results.

## Implementation scope

- FeatBitProvider, nested Builder, FeatBitOptionsFactory, and IdentityPolicy.
- Six evaluation types, named/persisted-anonymous context mapping, strict type errors, JSON conversion, and Track.
- Single-client ownership, asynchronous creation, separate adoption/readiness, obsolete-request isolation, synchronous read leases, and idempotent close.
- Bounded extension commands/waiters, queue-inclusive budgets, recovery after mode failure, retained Operation results, and probing after observation-registration failure.
- Two shared core subscriptions; each observe collector has its own merge slot with no fixed subscriber-count limit.
- Independent Java/Kotlin acceptance apps, local Maven publication, a repeatable verification script, and a CI workflow.

Production code uses only public FeatBit APIs. Neither the core SDK nor OpenFeature was modified. Tests inject a fake through the internal ClientFactory. Test-only reflection constructs IdentityReceipt, whose constructor is not public; it is not included in the AAR.

ClientOwner, AdmissionGate, and TrackingAdapter in the architecture diagram correspond to responsibilities inside ProviderRuntime. EvaluationAdapter's pure conversions are implemented in ValueMapper; there is no separate interface for every responsibility. Control uses a short monitor, a serialized submission mutex, and independent owner coroutines. Extensions follow acceptance order. New contexts discard older unsubmitted reconciliation, while the owner retains submitted adoption receipts. The creation identity is frozen when the factory task is submitted.

## Pinned build combination

| Component | Verified combination |
| --- | --- |
| Provider coordinates | `co.featbit:featbit-openfeature-provider-android:0.1.0-SNAPSHOT` |
| FeatBit | `co.featbit:featbit-client-android:0.2.0`, Maven Central |
| OpenFeature | `dev.openfeature:kotlin-sdk:0.9.0`, Android variant from Maven Central |
| Kotlin / AGP / Gradle | 2.2.10 / 8.10.1 / 8.11.1 |
| JDK / JVM target | JDK 17 / JVM 11 |
| Android | compileSdk 34, minSdk 21; device verification on API 34 |
| Coroutines | kotlinx-coroutines 1.8.1 |

Public dependencies use api. Consumers do not reference the provider source module, composite builds, or dependency substitution. They read the actual AAR from build/repository and resolve core/OpenFeature from Central.

## Unit tests and integration coverage

40 tests passed, including 3 reproduction tests that explicitly assert known upstream defects. Passing a reproduction means the defect remains; it does not satisfy G02.

| Test class | Count | Main coverage |
| --- | ---: | --- |
| MappingTest | 6 | key/name/attributes, anonymous restrictions, reserved fields, Null/absence, integer-attribute precision, type errors, recursive JSON |
| RuntimeTest | 25 | Six reads, Double-to-integer conversion, adoption/readiness separation, named/anonymous races, invalid contexts, obsolete callbacks, cancellation, creation timeout, switching/closing inside factory, late resources, read leases, extension capacity/queueing/timeouts, mode failure, Track rounding/reentrancy, Stale/Fatal |
| EventAndOperationTest | 5 | 70 collectors, late subscribers, All invalidation after more than 1024 keys for slow subscribers, separate state/change slots, close cleanup, observe races, single completion, registration-failure probing |
| OpenFeatureIntegrationTest | 4 | Real API Client defaults/error hook, plus initialization/context-return/provider-replacement status-overwrite reproductions |

Race tests use virtual time, delayed Operations, and reentrant barriers, not sleep-based timing guesses. Fakes do not verify networking, storage, or server event protocols.

## Android artifacts and devices

Passed:

- Spotless, 40 unit tests, and Android lint with no errors. The compileSdk update notice does not affect the build.
- Local publication of the release AAR, sources JAR, POM, and Gradle metadata.
- Independent Java/Kotlin Debug and Release/R8 builds.
- Four Java/Kotlin × Debug/Release runs on Pixel_3a_API_34 / emulator-5554.

Device acceptance uses real core TestData and the actual provider AAR. Kotlin checks all six evaluations, type errors, missing-flag defaults, A→B→anonymous transitions, and close results. Java checks Builder/SAM, Continuation initialization, evaluation, and close. The test source disables analytics, so these results do not establish real-server event correctness.

Entry point:

```powershell
python tools/acceptance.py --device emulator-5554
```

Final implementation-run evidence: `build/acceptance/20261009-151841-d5e39c19/`. Release AAR SHA-256: `eb65e8cb52b16146367b025428cf4e5c3c303f65e7b59835a7292075d186c8bd`. Subsequent changes normalized text line endings and updated documentation, followed by passing Spotless and documentation-link checks.

Each run writes to build/acceptance/&lt;timestamp-id&gt;/, including provider, consumer, installation, launch, and device logs. Device success requires this run's unique runId; the script does not clear device-wide logcat. Build artifacts and logs are gitignored.

## Observed upstream G02 results

The unmodified OpenFeature 0.9.0 Maven dependency exhibits these defects:

1. Before initialization returns, the provider emits Stale or Fatal and the API receives it. A subsequent normal initialization return unconditionally overwrites it with Ready.
2. Before onContextSet returns, the provider emits Stale and the API receives it. A subsequent normal return overwrites it with Ready.
3. Replace a provider whose initialization is pending with a new provider whose initialization fails. When the old initializer returns, it overwrites the current Error with Ready.

The three upstream090... methods in OpenFeatureIntegrationTest reproduce these cases using isolated API instances. They do not modify dependencies, global state, or production code, and do not use fixed delays as a workaround. Provider admission remains enforced, but upstream getStatus() is not guaranteed accurate during these windows.

## Outstanding release verification

- G02: obtain an upstream status-arbitration fix in a released dependency, then convert the reproductions into regressions asserting correct behavior.
- G03: A01–A15/R01–R24 remain the full acceptance targets. The specific scenarios above have coverage, but not every permutation has been exhausted, including all create-submission boundaries, cache-clear/context-transition combinations, logger reentrancy, and real API registration with 65+ existing subscribers.
- G04: API 21 devices, physical devices, and systematic memory/main-thread I/O checks have not run. API 34 emulator results do not replace them.
- G05: real-server cold start, cache/Bootstrap recovery, and persisted analytics attribution have not been verified.
- G06: development documentation and local artifact metadata exist. Remote signing/upload, a published documentation JAR, complete transitive dependency locking, and public-repository verification remain unconfigured/unverified. The new CI workflow has not run on GitHub.

This is a buildable, runnable, tested development implementation, not completed release acceptance.
