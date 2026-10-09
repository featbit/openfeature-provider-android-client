# Core SDK Evaluation and Identity Contract

Status: core SDK 0.2.0 was officially released on 2026-10-06 and passed public-download, signature, and independent-consumer verification. The provider pins `co.featbit:featbit-client-android:0.2.0`, whose release source is `7210251fbe5e3f9f452ef15e370da265f1009702`. This document describes the available integration contract and the agreed LaunchDarkly-style type/numeric policy. It replaces the earlier resolveDetail, ResolutionResult, ResolvedValue, and exact JsonValue proposals; those additions are no longer required. Provider integration uses public APIs; production code must not reference core internal APIs. See the [implementation baseline](./implementation-plan.md#1-reviewed-baselines) for release evidence.

## 1. Reuse public evaluation APIs

Ordinary and Detail methods follow the same type rules. The provider uses the Detail result to determine success/failure, not equality between the returned value and fallback.

| Provider request | Core call | Behavior |
| --- | --- | --- |
| Boolean | boolVariationDetail | Accept only a boolean declaration |
| String | stringVariationDetail | Accept only a string declaration; do not automatically serialize other types |
| Int / Long / Double | numberVariationDetail | Accept only a number declaration, parsed first as finite Double |
| Object | jsonVariationDetail | Accept only a json declaration; return an FbValue tree with Double numbers |

Generic variation/variationDetail parse according to the declaration and do not require the result to match fallback.kind. Specialized jsonTextVariation requires a json declaration but preserves raw text without validating JSON syntax. allVariations preserves raw text and does not collect events.

## 2. Numeric and error mappings

Retain the existing FbValue/Double model without adding exact integers or a JSON numeric tree. The provider calls Double.toInt()/toLong() only after numberVariationDetail succeeds: truncate fractions toward zero and saturate out-of-range values at JVM/Kotlin boundaries. Do not promise full Long precision or add post-evaluation integer, safe-integer-range, or precision checks. The core returns WRONG_TYPE for Double parse failures and nonfinite results.

JSON numbers also remain Double and map to OpenFeature Double values without claiming preservation of original integer precision. Recursively map JSON null, arrays, and objects from immutable FbValue. Preserve the caller's original OpenFeature fallback on failure instead of converting it through Double or FbValue, which could alter Long or complex defaults.

| Core reason | Provider handling |
| --- | --- |
| MATCH | Return the converted value; without reliable origin information, use reason=UNKNOWN and variant=null |
| WRONG_TYPE | TYPE_MISMATCH with the original default; the API does not distinguish declaration errors from content parse failures, so do not guess PARSE_ERROR |
| FLAG_NOT_FOUND | FLAG_NOT_FOUND with the original default |
| CLIENT_NOT_READY | PROVIDER_NOT_READY with the original default |
| ERROR | GENERAL with the original default |

The provider lifecycle gate checks closing, termination, and context availability. Core support for local reads after close does not establish Provider Ready.

## 3. Type checks and events within one evaluation

1. Read the current record from the core's immutable view.
2. Check the declared type and parse outside the gate, retaining type/parse failures as candidate results.
3. Reenter the gate and confirm the view has not changed; retry stale snapshots on both success and failure paths.
4. For a currently confirmed remote record, attempt event admission under existing metadata, disabling, offline, closing, deduplication, and capacity rules. Evaluation failure does not automatically prohibit an event.
5. Return the successful value or the original fallback + WRONG_TYPE. Ordinary methods return its value.

The event describes the variation selected by the server, not proof that the application used it. The FeatBit protocol has no local type-error reason or caller-fallback fields. Do not extend the protocol, fabricate variationId, or replace the selected value with a fallback. Missing/archived flags, bootstrap, unconfirmed cache, and local sources still do not produce these evaluation events. The provider neither duplicates nor retracts core events.

## 4. Java/Kotlin compatibility boundaries

The core remains a Kotlin implementation with Java-compatible public APIs. identifyContext and identifyAnonymousContext are direct FeatBitClient methods returning IdentityReceipt; no capability interface or cast is needed. Pin the exact Maven core version to avoid accidentally using a version without declared-type checks. Third-party FeatBitClient implementations must honor these contracts.

## 5. Identity-adoption receipt protocol

Both identify and identifyAnonymous prepare/adopt identity in the background and then await readiness. A TIMED_OUT result alone cannot determine whether adoption already occurred.

The two Context APIs in 0.2.0 settle adoption separately:

1. Validate input, timeout, and capacity, and allocate an identity request sequence; a newer request may supersede older anonymous preparation.
2. The named path reuses enrich/contextKey; the anonymous path reuses persisted repository.prepare/withCurrent. The provider must not create anonymous IDs.
3. Under the same gate, verify that the request is still latest, the client is not closing, and the adoption deadline has not passed. Adopt, create IdentityReceipt, and synchronously settle SUCCESS.
4. Delayed callback delivery cannot turn successful adoption into TIMED_OUT. Operation.getResult retains the successful receipt.
5. If the deadline, storage failure, or supersession wins before adoption, settle the corresponding failure and prohibit late preparation from adopting. If a new request arrives after SUCCESS, retain the original SUCCESS: it proves prior adoption, not that the identity is still current.

The provider additionally checks its RequestToken and sets adoptedContext only for a successful receipt belonging to the current request, then separately calls awaitReady. Mode changes, background state, cache hits, and remote recovery do not create identity receipts. SUCCESS does not mean flags are ready for evaluation.

These API timeouts cover identity preparation/adoption only, excluding remote data readiness. The provider calculates a separate overall lifecycle budget. An operation may succeed before its method returns, but observe must retain the core asynchronous callback contract.

## 6. Verification boundaries

Core verification covers declared-type mismatches, ordinary/Detail consistency, generic fallback-kind independence, Double rounding, failed-evaluation events, and origin/metadata restrictions. Provider verification must additionally cover Int/Long truncation and saturation, original defaults, JSON mappings, error hooks, identity leases, and close races. Passing core tests does not establish provider completion or release readiness.
