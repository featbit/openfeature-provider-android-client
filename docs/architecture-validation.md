# Detailed Architecture Acceptance Protocol

Status: baseline reviewed on 2026-10-06; five design boundaries and additional cases added on 2026-10-09. Released core 0.2.0 satisfies G01. The provider is implemented and some A01–A15/R01–R24 checks have run. The tables retain the complete targets; see the [verification record](./verification.md) for actual coverage. They do not establish that G02–G06 have all passed. The [implementation plan](./implementation-plan.md) defines overall scope. This document adds deterministic sequences and release gates; design reasoning is not a passing test result.

## 1. Invariants

| ID | Must always hold |
| --- | --- |
| I01 | At most one factory invocation, one submitted create, and one owned client per provider; late-instance cleanup remains part of ownership |
| I02 | No evaluation/Track admission when desiredContext differs from adoptedContext |
| I03 | SDK identity/mode changes cannot cross existing read leases; the close exception must reject late successful returns |
| I04 | Without an adoption receipt, old-identity readiness cannot establish target-identity adoption |
| I05 | Obsolete token completions cannot change current phase, admission, or event state |
| I06 | Type/parse failures may record selected remote variations; never fabricate fallback events. Concurrent cancellation does not retract submitted events |
| I07 | Timeout, cancelling observation, and cancelling SDK work are distinct operations |
| I08 | Slow collectors do not block SDK callbacks; final state and full invalidation cannot evict each other. Subscriber counts cannot exclude the OpenFeature internal listener |
| I09 | close settles at most once; unknown cleanup cannot report cleanupComplete=true |
| I10 | No locked path invokes factory, logger, SDK, JSON parsing, suspend, or application callbacks |

## 2. Required controllable test barriers

The internal CoreFacade fake must pause create completion, identity adoption, readiness, resolve submission, Track submission, and close completion. Advance elapsed time independently and delay/reorder callbacks. Test injection must not change production public APIs.

Pure mapping tests do not start Android. Controller tests use virtual coroutine scheduling and barriers rather than sleep-based timing guesses. Integration additionally uses the real OpenFeature API, actual AAR, and core TestData/controlled services to avoid reproducing incorrect assumptions in fakes.

| ID | Setup and trigger | Assertions |
| --- | --- | --- |
| R01 | Block resolve(A); submit B; evaluate B; release A | Early B uses fallback; core adopts B only after A's lease drains; reject A's late return; submitted events belong only to A |
| R02 | Pause anonymous B preparation; complete newer named C; release B | Core never adopts B late; provider remains C; no B Ready |
| R03 | Anonymous B reaches its deadline before adoption while old A is READY | B adoption fails; A's awaitReady cannot unlock B; only explicit retry submits another adoption |
| R04 | B adoption succeeds; delay its callback beyond caller timeout | getResult retains SUCCESS; owner can await B data; no duplicate anonymous creation |
| R05 | B ReadyResult succeeds with delayed callback; submit C | B completion cannot change C state; C requires its own receipt/readiness |
| R06 | Retain queued READY(A); switch B to WAITING; deliver old notification | Reread the current snapshot; do not complete B or admit old values |
| R07 | Subscribe before initialization; initialize; add a late subscriber | Both receive current provider Ready; only 2 core listeners; late subscriber receives All |
| R08 | Block one collector; send more than 1,024 distinct changed keys and Stale→Ready | Other collectors progress; resumed slow collector gets latest Ready and All; storage remains bounded |
| R09 | Race close against successful create callback | No live-client leak; exactly one core close; timeout does not fabricate loss counts |
| R10 | Block factory beyond close deadline, then let it return | close already failed; prevent unsubmitted create; work eventually exits without rewriting the result |
| R11 | SDK observe registration fails; operation completes later | getResult/shared probe receives completion; cancelling caller await does not lose the owned client |
| R12 | Flag declared string contains true; request Boolean | TYPE_MISMATCH, preserved default, one error hook; evaluation event allowed when core conditions hold |
| R13 | number=9007199254740993 / Long.MAX_VALUE / one beyond the boundary | Convert after Double rounding; permit precision loss and saturation without extra range rejection |
| R14 | Invalid C arrives during B adoption, then B becomes READY | C error persists and old identity stays blocked; valid D recovers |
| R15 | Emit Fatal/Stale just before initialize returns, then allow upstream commit; also normally return from same-identity Stale onContextSet | Provider admission remains correct; old Ready must not overwrite upstream getStatus. Original 0.9.0 exposes the defect; reproduction has run, but G02 is not satisfied |
| R16 | setOffline precedes readiness completion; then setOnline | Distinguish mode result from remote readiness; old-mode Ready cannot unlock reads; empty offline data yields FLAG_NOT_FOUND |
| R17 | Race cache clearing with user transition | Target the stable identity at submission; do not clear the old user then claim the new user was cleared |
| R18 | Exceed pending-command/lifecycle-waiter capacity; reentrant logger throws | Explicit rejection, no deadlock, accepted work continues; subscriber count is not limited; logs contain no input values |
| R19 | Offline startup lacks online endpoints and setOnline fails; also repeat setOnline and reject excess capacity | Preserve original failure; restore reads from actual mode/readiness; no permanent barrier or removal of valid online confirmation |
| R20 | setOffline changes actual mode but stopping the old connection times out; switch identity or close during recovery | Original result remains TIMED_OUT; current valid identity can recover under OFFLINE_LOCAL; old mode results cannot cross newer identity/close barriers; no rollback |
| R21 | Pause control queue/read lease; expire setOffline/setOnline/clearCache before and after submission; also expire queued flush | No core call before submission and remove that command's barrier; submit only remaining budget; retain ownership after submission, settle externally once, allow core side effects; flush does not extend external budget |
| R22 | Start at least 65 observe collectors before real OpenFeature registration/listening; block some, then cancel all | Internal listener succeeds; state/changes continue with only 2 core listeners; bounded slow slots are reclaimed; total memory may scale with active subscribers |
| R23 | Insert A→B before factory, during factory, after return but before create, and after create; also invalid/expired B and close | Latest identity may be selected before factory; afterwards freeze creationContext and call factory/create once. Do not admit B early or fall back to A for invalid B; close prevents unsubmitted create; account for possible A synchronization |
| R24 | Track Long.MAX_VALUE, 2^53+1, BigInteger, BigDecimal, underflow, custom Number; also NaN/Infinity/overflow/conversion exceptions and identity change during conversion | Accept finite Double with rounding/underflow, without exactness checks; safely diagnose/drop nonfinite/failing conversions; conversion holds no lock/lease; recheck identity before submission; flag conversion rules stay unchanged |

## 3. State-machine property checks

Use a finite model to enumerate relative create/adopt/ready/timeout/cancel/close ordering, including repeated close, duplicate callbacks, zero/multiple subscribers, and every phase exit. Check I01–I10 rather than private-method invocation order. Resource counts must reach zero at completion; uncleaned work must correspond to an explicit timeout failure.

## 4. Release gates

| Gate | Required evidence | Current status |
| --- | --- | --- |
| G01 Core capabilities | FeatBitClient identity APIs, consistent declared-type checks, Double model, and compatible consumers; 0.2.0 released with verified public artifacts, see [release evidence](./implementation-plan.md#1-reviewed-baselines) | Satisfied; pin 0.2.0 |
| G02 OpenFeature status commit | R15 plus provider replacement, initialization failure, and context reconciliation variants pass against a released dependency containing the fix that prevents obsolete lifecycle results from overwriting newer events | Not passed; 0.9.0 is the development/reproduction baseline, and deterministic tests confirm the defect |
| G03 Functionality and races | Deterministic A01–A12, A14, and R01–R24 checks pass | Implemented with test coverage; the complete gate has not been declared passed; see verification record |
| G04 Android artifacts | Independent Kotlin/Java builds, Debug/Release/R8, API 21 and newer devices/emulators, memory/main-thread I/O checks | Java/Kotlin Debug/Release/R8 and API 34 verified; API 21 and other checks remain |
| G05 End-to-end events | Real-server flag/user/Track attribution and experiment events, including transition ordering and failed-parse selected-variation events under core policy | Provider end-to-end verification pending |
| G06 Documentation and publication | Maven metadata, license, dependency locks, API compatibility table, anonymous/TrackingDetails limitations, and explicit untested scope | Development docs/local artifacts exist; release preparation remains incomplete |

Compilation does not establish identity, state, or event correctness. Core 0.2.0 acceptance establishes only G01, not provider G03–G05. G02 remains an external dependency condition. The authorized provider implementation and minimal reproductions exist, but G02 must pass before release. OpenFeature has not been modified; its defect remains.
