# Behavior and Architecture

## 1. Responsibility boundaries

The provider handles context mapping, lifecycle coordination, value/error conversion, and event bridging. The core SDK remains the sole implementation of networking, caching, anonymous storage, Android foreground/background and connectivity handling, evaluation events, and the Track queue. Do not open a second WebSocket, duplicate the evaluation cache, or register another set of Android lifecycle listeners.

Internal responsibilities, data structures, and linearization points follow the [detailed architecture](./architecture.md). Only ClientOwner initiates SDK identity/mode changes. Synchronous evaluation and Track obtain a read lease through AdmissionGate, execute outside the lock, and prevent identity submission until leases drain. Never call the SDK, parse JSON, or suspend while holding the lock.

## 2. EvaluationContext → User

| Input | Mapping/handling |
| --- | --- |
| Nonblank targetingKey | User.key; preserve valid strings without trimming or UUID/other format checks |
| Null context / empty targetingKey | Default TARGETING_KEY_MISSING; with explicit anonymous policy, an empty key without attributes selects anonymity. OpenFeature defaults the key to an empty string, so omission and an explicitly empty string are indistinguishable |
| Nonempty, whitespace-only targetingKey | TARGETING_KEY_MISSING; do not implicitly convert to anonymous |
| Top-level name | Nonblank Value.String → User.name; default to targetingKey; Null, other types, or blank strings produce INVALID_CONTEXT |
| Top-level keyId | Reject to avoid conflict with FeatBit reserved fields; targetingKey is the only identity entry point |
| Ordinary String attribute | Preserve text as AttributeValue.text |
| Boolean / Integer / Long attribute | Lowercase Boolean or decimal integer string; never convert Long through Double |
| Finite Double attribute | Locale-independent, round-trippable numeric string; NaN/Infinity produce INVALID_CONTEXT |
| Null attribute | AttributeValue.nullValue; distinct from deleting an attribute |
| List / Structure / Instant attribute | INVALID_CONTEXT in the first release; no implicit JSON encoding, flattening, or time formatting. Applications must explicitly convert to String |
| Absent attribute | Omit from User.attributes; each context completely replaces attributes rather than incrementally merging them |
| Empty attribute name or SDK-reserved attribute | Use existing User/ClientOptions validity rules; prohibit user-supplied featbit.sdk.* when automatic attributes are enabled |

Anonymous mode accepts only a null context or an empty targetingKey with no attributes. Initialize with a null user and anonymousEnabled=true; subsequent transitions use identifyAnonymousContext to obtain an adoption receipt, followed by a separate readiness wait. The SDK does not expose atomic creation of an anonymous ID with arbitrary context attributes. The provider must not invent IDs or maintain another anonymous identity store. Applications requiring anonymous attributes can maintain a stable ID themselves and provide it as targetingKey through the named path.

Compare complete canonical key/name/attribute values, independent of Map ordering, object identity, or key alone. Retain desired context, applied context, and operation generation. SDK automatic attributes are not part of input-context equality. Error logs must not expose user attributes, credentials, or raw responses.

## 3. Initialization, transitions, and concurrency

Initialization freezes creationContext when the factory call is submitted. Context changes during the factory or create wait do not create another client; the single client subsequently reconciles the latest valid request. The core may initially synchronize the creation identity. Provider evaluation/Track remain blocked until the latest identity is adopted and ready; see the [initialization protocol](./architecture.md#6-initialization-protocol).

initialize proceeds as follows: validate context → invoke the options factory and verify user equality → asynchronous create → install status/change subscriptions → read the current snapshot to cover the registration window → adopt the latest valid identity if needed → awaitReady. Online mode requires REMOTE_CONFIRMED; explicit offline/LOCAL sources accept OFFLINE_LOCAL/CUSTOM_LOCAL, with event metadata identifying local readiness. Disk cache or Bootstrap alone does not establish online remote confirmation.

Initialization timeout throws ProviderNotReadyError. An already-created SDK may continue synchronization and later recover through real readiness events. Unrecoverable factory/creation failure does not cause a rebuild loop. Caller cancellation is not a business error and must propagate as CancellationException. Cancelling observation does not cancel creation; ClientOwner must receive and close a late instance.

onContextSet first installs a new request token and transition barrier in a short critical section. It maps outside the lock, drains old read leases, submits identity adoption, and waits for readiness only after the receipt arrives. Cancellation/timeout of an old waiter cannot roll back a newer identity. An already-adopted identical identity only waits again; if adoption failed, the same identity requires another adoption attempt. See the [context transition protocol](./architecture.md#7-context-transition-protocol).

Evaluation context must match the applied identity; synchronous evaluation must not identify. A matching identity that is not ready returns PROVIDER_NOT_READY; a mismatch returns INVALID_CONTEXT. Track uses the same gate because upstream track does not check readiness for the provider. Even while OpenFeature is Reconciling/Error, the provider must not return an old user's value or attribute a new user's event to the old user.

If the new context is invalid, the SDK may retain its old identity, but the provider marks the requested context unavailable. An old Ready callback cannot clear that error; a subsequent valid context can recover. SDK status callbacks have no generation field, so queued READY notifications cannot establish readiness for a new user. Confirm through the current identify/awaitReady results and refresh the snapshot. The SDK still isolates obsolete network responses itself.

OpenFeature updates statusFlow on lifecycle success, but observe consumes the provider's event source, so the provider must also emit a Ready event. The unconditional Ready write after initialization in v0.9.0 can overwrite newer events; provider delays/rechecks cannot strictly solve this. See [status handoff limitations](./architecture.md#10-openfeature-v090-status-handoff-limitations) for the upstream limitation and recommended fix, which is outside the current FeatBit scope.

## 4. Strict evaluation and numbers

Core SDK 0.2.0, released on 2026-10-06, uses consistent declared-type checks and a Double numeric model; see the [core contract](./core-sdk-contract.md). Provider mappings are implemented; evidence is in the [verification record](./verification.md).

| OpenFeature method | FeatBit variationType | Conversion |
| --- | --- | --- |
| Boolean | boolean | Valid Boolean; no conversion from other declared types |
| String | string | Preserve the original string |
| Integer | number | Finite Double to Int, truncating toward zero and saturating out-of-range values |
| Long | number | Finite Double to Long with truncation and saturation; full Long precision is not guaranteed |
| Double | number | Finite Double with normal IEEE 754 rounding; reject nonfinite results |
| Object | json | Strict JSON recursively mapped to OpenFeature Value; objects, arrays, Null, and JSON scalars are valid |

JSON numbers map to OpenFeature Double values and accept IEEE 754 rounding. Null is valid JSON; do not infer Instant from JSON. Retain the core JSON depth constraint without adding arbitrary flag-size/count limits.

On failure, return the caller's default unchanged, without a round trip through FbValue/Double. This matters especially for Long and object defaults containing Long/Instant. An Object default is an opaque fallback and need not be serializable as FeatBit JSON.

A single core call must perform declared-type validation, parsing, result selection, and evaluation-event admission against the same atomic SDK view. Failed evaluations may record the selected remote variation under core rules. On success, the provider only converts Double or maps FbValue; it adds no integer/precision rejection. Do not read through allVariations and then evaluate separately, or combine numberVariationDetail with another string read to fabricate an exact result.

## 5. Reasons, errors, and metadata

| Condition | reason | errorCode |
| --- | --- | --- |
| Success with only SDK MATCH information | UNKNOWN | None |
| Not initialized or current identity still waiting | ERROR | PROVIDER_NOT_READY |
| Missing/archived flag | ERROR | FLAG_NOT_FOUND |
| Declared-type mismatch | ERROR | TYPE_MISMATCH |
| Core WRONG_TYPE, including malformed content/invalid JSON | ERROR | TYPE_MISMATCH |
| Required targetingKey missing | ERROR | TARGETING_KEY_MISSING |
| Invalid or mismatched context | ERROR | INVALID_CONTEXT |
| Unrecoverable instance synchronization termination or call after closing | ERROR | PROVIDER_FATAL |
| Other internal failure | ERROR | GENERAL |

MATCH does not establish a targeting-rule, rollout, or default-rule match; do not translate it to TARGETING_MATCH. EvaluationDetail has no variationId, so variant remains null rather than using the flag value as a variant. Evaluation metadata is initially empty. Do not claim that an individual value came from CACHE/REMOTE: a connection snapshot cannot establish a specific flag's origin.

Failed provider evaluations should throw concrete OpenFeatureError exceptions so error hooks run in the current Kotlin SDK. OpenFeature Client constructs defaultValue + reason/errorCode/errorMessage. Merely returning a ProviderEvaluation with errorCode does not automatically run error hooks in the current upstream implementation. Return ProviderEvaluation on success. Use fixed, safe diagnostic messages; do not infer error categories by parsing human-readable explanation text.

## 6. Provider events

| Core condition | OpenFeature event/handling |
| --- | --- |
| Initialization/context transition reaches READY | Lifecycle method returns; provider emits one ProviderReady for observe while OpenFeature manages statusFlow. If already STALE, retain Stale instead of emitting Ready |
| Current identity recovers after timeout or Stale | ProviderReady, deduplicated by state transition |
| SyncStatus.STALE after readiness | ProviderStale; old data for the current user remains readable subject to admission checks |
| TERMINAL | ProviderError with PROVIDER_FATAL; do not promise automatic recovery |
| Flag changes | ProviderConfigurationChanged with SDK keys in flagsChanged |
| allFlagsChanged=true | Empty flagsChanged plus featbit.allFlagsChanged=true in eventMetadata; consumers invalidate all flags |
| BACKGROUND/OFFLINE pause or candidateFailure alone | Not inherently fatal; interpret actual SyncStatus and explicitly identify local/offline readiness in metadata |
| Normal shutdown | Isolate listeners first; do not manufacture an error event for normal cleanup |

Register status/change listeners before reading current status. Registration failure cannot report successful initialization. Use one shared producer with separate per-subscriber state and change-merge slots, rather than mixing all events into a single replay=1 buffer. Capacity, initial subscriptions, slow consumers, and closing follow the [event bridge](./architecture.md#9-event-bridge-and-capacity).

## 7. Track and closing

Without details.value, call SDK track(name), whose current default numeric value is 1.0. Otherwise call Number.toDouble() and require a finite result. Submit finite values directly, accepting rounding and underflow to zero without checking exact integer ranges or lossless BigDecimal conversion. Long, BigInteger, BigDecimal, and other Number implementations use the same conversion. Do not infer numbers from strings or promise exact integer/decimal statistics.

Conversion exceptions, NaN, and positive/negative Infinity, including overflow from large numbers, produce a fixed safe diagnostic and drop the event. Do not propagate ordinary conversion exceptions into application workflows or log raw numbers/exception messages. Convert custom Number values before obtaining a read lease or submitting Track. Recheck identity/state afterwards before taking the lease, so application conversion code never runs under the lock. Do not swallow fatal JVM errors as ordinary numeric failures. This policy accepts Double precision loss consistently with flag numbers. Applications requiring exact currency or large-integer statistics must choose appropriate business units and precision before calling.

TrackingEventDetails.structure is not transmitted in the first release. Still send the name/value and log a diagnostic that structured attributes were omitted, without logging their contents. Document this limitation. Do not temporarily identify to carry the attributes or merge them into user attributes. The SDK owns deduplication, disabling, capacity, and closing outcomes; track returning Unit is not delivery confirmation. The provider must not add another flag evaluation event.

Track failures caused by invalid context, not-ready status, nonfinite values, and similar conditions produce safe diagnostics and drop the event instead of throwing into business workflows. An upstream IllegalArgumentException for an empty event name retains OpenFeature's behavior. Use flush to observe queue processing; ACCEPTED does not establish server persistence.

shutdown/close first close evaluation/Track admission, detach subscriptions, and isolate callbacks, then start SDK close. Do not implement separate flush-then-close delivery paths for ordinary cleanup; reuse SDK close budgeting and final delivery. Never use runBlocking to wait on the Android main thread, or cancel the cleanup scope before resources are released.
