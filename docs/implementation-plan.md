# Source References, Gaps, and Implementation Acceptance

## 1. Reviewed baselines

Updated: 2026-10-06. Core release source, post-release documentation commits, and provider acceptance are recorded separately.

| Source | Pinned baseline/evidence |
| --- | --- |
| FeatBit Android SDK | Released dependency `co.featbit:featbit-client-android:0.2.0`; tag `v0.2.0`, release commit `7210251fbe5e3f9f452ef15e370da265f1009702`; required core capabilities are released |
| Core post-release documentation | Commit `dc351c6aa58c39fa594968e17ee6a5a2bde9049b` records public-artifact verification and installation; it does not change the release tag or runtime source |
| OpenFeature Kotlin | Reviewed release/development/reproduction baseline: tag v0.9.0, commit `72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b`; G02 is a known upstream limitation outside the current release-fix scope |
| OpenFeature main | `8d727fbead4b8d8bc66dc271ad35de1ece603a8b`; differs from v0.9.0 only in two release configuration files; referenced runtime/API sources are identical |
| LaunchDarkly Java server provider | `2363198f45cbe7c06bec73bf1cf070a2281eb17a` |
| Documentation | [OpenFeature Kotlin SDK documentation](https://openfeature.dev/docs/reference/sdks/client/kotlin/), reviewed on 2026-10-06; pinned source governs interface details |

FeatBit 0.2.0 satisfies the G01 core prerequisites:

- The [official release](https://github.com/featbit/featbit-android-client-sdk/releases/tag/v0.2.0) and [Maven Central artifacts](https://repo.maven.apache.org/maven2/co/featbit/featbit-client-android/0.2.0/) are public.
- The [release workflow](https://github.com/featbit/featbit-android-client-sdk/actions/runs/37489895116) succeeded and Central reported `PUBLISHED`. Signatures/checksums for the public AAR, POM, Gradle metadata, sources, and documentation passed and matched workflow archives.
- All 155 core tests and the independent Java/Kotlin 1.9.24/1.9.25/2.2.10 artifact matrix passed. Post-release Java/Kotlin consumers downloaded from Central with fresh dependency caches and passed Debug, Release/R8, unit tests, and lint. That public-download verification did not rerun device or live-service checks.
- Detailed evidence is in the core [release verification record](https://github.com/featbit/featbit-android-client-sdk/blob/dc351c6aa58c39fa594968e17ee6a5a2bde9049b/docs/verification.md#maven-central-020-publication--2026-10-06).

This establishes availability of the core dependency, not provider acceptance. G02 remains open. OpenFeature 0.9.0 and the pinned main have identical status arbitration; this project has reproduced the defect, as recorded in [verification](./verification.md). Provider compilation, runtime behavior, and Android compatibility with both dependencies require separate acceptance.

### Exact OpenFeature source references

- [FeatureProvider](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/FeatureProvider.kt): six evaluations, initialization, context changes, synchronous shutdown, optional track/observe.
- [OpenFeatureAPIInstance](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureAPIInstance.kt): provider listening/registration, context reconciliation, and status/error handling.
- [OpenFeatureClient](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/OpenFeatureClient.kt): NotReady/Fatal admission, exceptions/hooks, and Track forwarding.
- [Value](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/Value.kt), [ProviderEvaluation](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/ProviderEvaluation.kt), and [Provider events](https://github.com/open-feature/kotlin-sdk/blob/72fab71809c3c8ab5c8ecac090ac7a24fe2fd42b/kotlin-sdk/src/commonMain/kotlin/dev/openfeature/kotlin/sdk/events/OpenFeatureProviderEvents.kt): concrete value and event models.

### Exact FeatBit source references

These links pin the 0.2.0 release commit. Later documentation commits in the adjacent core repository do not change these runtime sources.

- [ClientContract](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/api/ClientContract.kt): Operation, evaluation, identifyContext/identifyAnonymousContext, readiness, subscriptions, and close.
- [ClientOptions](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/api/ClientOptions.kt) and [User](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/api/User.kt): configuration and identity validation.
- [LocalClient](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/internal/LocalClient.kt) and [Values](https://github.com/featbit/featbit-android-client-sdk/blob/7210251fbe5e3f9f452ef15e370da265f1009702/sdk/src/main/kotlin/co/featbit/android/internal/Values.kt): conversions, snapshot arbitration, and evaluation-event admission.

## 2. What to reuse from LaunchDarkly

The review covered [Provider.java](https://github.com/launchdarkly/openfeature-java-server/blob/2363198f45cbe7c06bec73bf1cf070a2281eb17a/src/main/java/com/launchdarkly/openfeature/serverprovider/Provider.java) and [EvaluationDetailConverter.java](https://github.com/launchdarkly/openfeature-java-server/blob/2363198f45cbe7c06bec73bf1cf070a2281eb17a/src/main/java/com/launchdarkly/openfeature/serverprovider/EvaluationDetailConverter.java).

Useful patterns include a thin adapter, centralized conversion, native configuration reuse, and status listeners. That provider uses the Java server SDK and supplies LDContext per evaluation; a mobile SDK must identify to switch the current user. Its public constructor takes key/config, while client injection is package-private, so it is not evidence of public arbitrary-client takeover. It exposes the underlying client and offers richer evaluation metadata/Track data; this design does not duplicate those APIs.

## 3. Agreed core integration approach (updated 2026-10-06)

Core ordinary and Detail methods consistently check declared types. The provider reuses bool/string/number/jsonVariationDetail and retains the Double/FbValue model. Int/Long convert from Double with truncation and saturation; full Long precision is not guaranteed. Type/parse failures may still collect selected remote variation events; do not extend server error-event fields. The proposed resolveDetail and exact numeric tree have been withdrawn.

identifyContext and identifyAnonymousContext are direct FeatBitClient methods in release 0.2.0; success means adoption only. Numeric, error, and event details are in the [core contract](./core-sdk-contract.md). A provider development implementation exists; see the [verification record](./verification.md) for acceptance status.

Other boundaries remain: create ClientOptions through a factory; arbitrate generation-free status using provider tokens and awaitReady; omit Track structured details; document the upstream G02 limitation for OpenFeature initialization/reconciliation status commits.

## 4. Implementation sequence (authorized on 2026-10-09)

1. Core G01 is complete and the dependency is pinned to 0.2.0. No additional core interface or release is required.
2. Retain the R15 reproductions against OpenFeature 0.9.0 for initialization, context reconciliation, and replacement. They document upstream G02 behavior; upstream changes are outside the current FeatBit scope.
3. The independent Android library project exists, with G02 tracked separately. Development/reproduction pins OpenFeature 0.9.0 and core 0.2.0. Pin AGP/Gradle/JDK; expose both public dependencies through api and internal dependencies through implementation. This development baseline is not release-dependency acceptance.
4. Implement pure mappings/EvaluationAdapter, followed by client ownership, lifecycle, and event bridging. Reuse TestData instead of duplicating a test source. Retain Double boundaries without restoring exact Long or zero-events-on-failure requirements.
5. Keep OpenFeature 0.9.0 pinned and retain its known-limitations documentation. Complete independent Kotlin/Java consumers, Android device, and server-event acceptance. Recheck integration if the dependency changes later. Core compatibility evidence does not replace these checks.
6. Complete integration documentation, limitations, migration/compatibility tables, and artifact verification. Release only after all applicable gates pass.

Directories now exist for provider, consumer-tests/kotlin, consumer-tests/java, tools, and docs. Execution evidence and incomplete gates are in the [verification record](./verification.md).

## 5. Acceptance matrix

| ID | Required behavior |
| --- | --- |
| A01 | Builder performs no I/O; no Activity leak; factory-user mismatch fails before creation; repeated registration and registration after close have defined behavior |
| A02 | Online cold start, cache, Bootstrap, offline, TestData; initialization completes only on actual readiness; no initial-listener gap |
| A03 | Context changes around factory and create submission, late create, initialization cancellation/timeout, close races; frozen creation identity, one owned client, no reads before latest identity readiness, eventual late-resource cleanup |
| A04 | key/name, whitespace, Null/absence, all attribute types, reserved fields, attribute order, and anonymous round trips |
| A05 | Rapid A→B→C, same-value retry, invalid context, synchronization after cancellation; no stale-value leaks or incorrect event attribution |
| A06 | All six types: success, missing flag, wrong type, malformed content; exact original defaults and error hooks on failure |
| A07 | Int/Long boundaries, 2^53±1, Long.MAX_VALUE, scientific notation, fractions, overflow, NaN/Infinity; verify Double rounding, truncation toward zero, saturation, and nonfinite parse failure |
| A08 | JSON objects/arrays/scalars/Null, nested integers, invalid JSON, depth boundaries, preserved fallback containing Instant/Long |
| A09 | Ready/Stale/Error recovery, termination, background/offline pauses, disconnect at initialization completion; late events cannot overwrite current identity |
| A10 | Single-key/full changes, slow/cancelled/repeated subscribers, more than 64 early subscribers; internal listener is not rejected, final state is retained, core subscriptions are shared, and full invalidation is recognizable |
| A11 | Track name/finite Double, permitted rounding/underflow, dropped nonfinite/conversion failures, structured-data limits, identity admission, deduplication/disabling/capacity, flush loss results; failed parsing may record selected remote variation without fabricated fallback/error fields |
| A12 | Actual-state recovery after mode success/failure/timeout; queue-inclusive budgets and no pre-submission-timeout side effects; nonblocking shutdown, idempotent close, accurate cleanup results, no error for normal close |
| A13 | Actual published AAR in Kotlin 2.2.10/Java consumers, Debug/Release/R8, minSdk 21 and current emulator; no inherited core Kotlin 1.9 compatibility promise |
| A14 | OpenFeature 0.9.0 registration/context/error-hook acceptance, with upstream G02 reproductions tracked separately; separate providers per isolated API instance, without sharing a mutable-identity client |
| A15 | Real-server synchronization and persisted event attribution; HTTP 200 alone does not prove server acceptance |

Provider functionality, race, artifact, and device checks have started. A01–A15 define complete scope, not proof that every variant has run. Evidence and gaps are in the [verification record](./verification.md). Core acceptance still establishes only G01.

See the [detailed acceptance protocol](./architecture-validation.md) for controlled barriers, 24 sequences, 10 invariants, and G01–G06. The review also covered OpenFeature's [provider specification](https://openfeature.dev/specification/sections/providers/), [context specification](https://openfeature.dev/specification/sections/evaluation-context/), and [event specification](https://openfeature.dev/specification/sections/events/). Online specifications may be newer than v0.9.0; do not add domain parameters absent from that version's interface.
