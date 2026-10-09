# FeatBit OpenFeature Android Provider Design

Design date: 2026-10-05; baseline update: 2026-10-06; design additions: 2026-10-09. Status: implementation and verification are underway. A development implementation exists but has not been officially released. See the [verification record](./verification.md) for build, test, and device evidence. Core SDK 0.2.0 has been released and verified.

The goal is an independent Android AAR implementing OpenFeature Kotlin `FeatureProvider`, reusing the FeatBit Android SDK's synchronization, cache, identity, lifecycle, and event capabilities. The implementation is Kotlin, with Java-compatible public construction and extension APIs. OpenFeature's own suspend/Flow interfaces retain their original calling conventions.

## Reading order

- [Maven Central release workflow](release.md)

For application integration, start with the [main README](../README.md) or the [Java README](../README.java.md).

1. [Detailed architecture](./architecture.md): components, state machine, internal contracts, concurrency, resources, events, and cleanup.
2. [Public API](./api-design.md): public types, methods, configuration, and application workflow.
3. [Behavior specification](./behavior-design.md): context, evaluation, error, and Track mappings.
4. [Core SDK integration contract](./core-sdk-contract.md): declared-type checks, Double numbers, identity-adoption receipts, and compatibility boundaries.
5. [Detailed acceptance protocol](./architecture-validation.md): invariants, deterministic race cases, and release gates.
6. [Source references and implementation acceptance](./implementation-plan.md): pinned source baselines, implementation sequence, and overall acceptance checklist.

## Recommended direction

| Area | Design recommendation |
| --- | --- |
| Publication coordinates | Retain `co.featbit:featbit-openfeature-provider-android:<version>`, established in the core SDK architecture |
| Package | `co.featbit.openfeature.android`, proposed for this provider |
| Dependency baseline | Pin the released and verified `co.featbit:featbit-client-android:0.2.0` (G01 satisfied); use OpenFeature 0.9.0 for development/reproduction. The pinned OpenFeature version remains 0.9.0; G02 is tracked as an external limitation, not a required FeatBit fix |
| Platform | Android, minSdk 21, JVM 11 bytecode, Kotlin 2.2.10 design baseline; verify the JDK/AGP/Gradle combination through consumers |
| Ownership | Each provider creates and exclusively owns one FeatBitClient; the first release does not take over an externally running client |
| User model | The current OpenFeature EvaluationContext is the identity source; transitions follow onContextSet → adoption receipt → awaitReady |
| Configuration | An options factory receives the mapped User and reuses ClientOptions without duplicating all SDK configuration |
| Default identity | Require targetingKey by default; explicitly opt into the core SDK's persisted anonymous identity |
| Evaluation | Boolean, String, Int, Long, Double, and Object, with complete error information and preserved caller defaults |
| Events | Ready, Stale, Error, ConfigurationChanged; OpenFeature manages context Reconciling |
| Extensions | Connection information, offline/online, flush, clearCache, and observable close completion |

## Review focus

Core SDK 0.2.0, released on 2026-10-06, includes consistent declared-type checks, the Double model, selected-variation events for failed evaluations, and both identity-adoption APIs. The provider no longer requires exact Long parsing or zero events on failure; see the [core contract](./core-sdk-contract.md). This is a numerical policy that permits precision loss, not an implementation of exact Long parsing.

G01 is satisfied. Evidence for public artifacts, signatures, and independent Java/Kotlin consumers is in the [baseline and evidence](./implementation-plan.md#1-reviewed-baselines). The reviewed OpenFeature release is 0.9.0. Its initialization/context completion can overwrite newer status; deterministic tests reproduce the issue in the [verification record](./verification.md), so G02 remains open. Required arbitration rules are in the [architecture](./architecture.md). The authorized provider implementation and minimal reproductions are now available; no further core release is required. Provider release assessment covers FeatBit-owned behavior and acceptance checks; fixing upstream G02 is outside the current scope.

FeatBit Track currently accepts only an event name and a numeric value. It cannot carry arbitrary structured attributes from OpenFeature TrackingEventDetails; this limitation must be documented for the first release.

The provider APIs and policies are implemented; the core APIs already exist in 0.2.0. Core release/test evidence establishes only G01 and does not replace this project's integration and release acceptance.

## Design additions on 2026-10-09

- Treat mode operation results separately from actual mode. After failure or timeout, reconfirm readability for the current identity without automatic rollback.
- Include queueing in extension-operation budgets. Do not submit expired work; a timeout after submission does not cancel core side effects.
- Do not impose a subscriber-count limit on observe. Keep bounded per-subscriber merge slots and release them on cancellation.
- Freeze the creation identity once the factory starts, create once, then reconcile the latest user. The core may initially synchronize the creation identity.
- Convert Track numbers to finite Double values, accepting precision loss and underflow. Drop nonfinite results and conversion failures.

These additions correspond to R19–R24. They are provider decisions and do not require core SDK changes. Implementations and tests are available; see the [verification record](./verification.md) for actual coverage and remaining variants. G02 remains a documented upstream limitation; the provider does not work around it.
