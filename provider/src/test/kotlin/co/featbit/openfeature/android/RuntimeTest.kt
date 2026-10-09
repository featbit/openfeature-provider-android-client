@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.featbit.openfeature.android

import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.*
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class RuntimeTest {
    private val a = ImmutableContext("A")
    private val b = ImmutableContext("B")
    private val c = ImmutableContext("C")

    private fun TestScope.provider(
        core: FakeCore,
        factory: FeatBitOptionsFactory = FeatBitOptionsFactory {
            ClientOptions.builder()
                .user(it)
                .offline(true)
                .anonymousEnabled(true)
                .closeTimeoutMillis(100)
                .build()
        },
    ): FeatBitProvider =
        FeatBitProvider(
            ProviderRuntime(
                TestContext(),
                factory,
                IdentityPolicy.PERSISTENT_ANONYMOUS,
                100,
                100,
                core.factory,
                StandardTestDispatcher(testScheduler),
            ) {
                testScheduler.currentTime
            }
        )

    private suspend fun TestScope.initialize(provider: FeatBitProvider) {
        val job = async { provider.initialize(a) }
        runCurrent()
        job.await()
    }

    private fun TestScope.finish(provider: FeatBitProvider, core: FakeCore) {
        provider.close()
        runCurrent()
        assertEquals(1, core.closeCalls)
        assertEquals(0, core.registrations)
    }

    @Test
    fun buildHasNoFactoryOrRuntimeWork() {
        var calls = 0
        val provider =
            FeatBitProvider.builder(TestContext()) {
                    calls++
                    Outcome.invalid("test")
                }
                .build()
        assertTrue(provider.isSuccess)
        assertEquals(0, calls)
        assertFalse(
            FeatBitProvider.builder(TestContext()) { Outcome.invalid("test") }
                .initializationTimeoutMillis(0)
                .build()
                .isSuccess
        )
        provider.value!!.close()
    }

    @Test
    fun evaluatesSixTypesAndUsesSdkNumberConversions() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        assertTrue(provider.getBooleanEvaluation("x", false, a).value)
        assertEquals("A", provider.getStringEvaluation("x", "fallback", a).value)
        assertEquals(12, provider.getIntegerEvaluation("x", -1, a).value)
        assertEquals(12L, provider.getLongEvaluation("x", -1, a).value)
        assertEquals(12.9, provider.getDoubleEvaluation("x", 0.0, a).value, 0.0)
        assertEquals(
            Value.Null,
            provider.getObjectEvaluation("x", Value.String("default"), a).value,
        )
        for (value in listOf(9007199254740993.0, Long.MAX_VALUE.toDouble(), -1e100, 1e100, -12.9)) {
            core.number = value
            assertEquals(value.toLong(), provider.getLongEvaluation("x", -1, a).value)
            assertEquals(value.toInt(), provider.getIntegerEvaluation("x", -1, a).value)
        }
        core.reason = EvaluationReason.WRONG_TYPE
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            provider.getBooleanEvaluation("x", false, a)
        }
        finish(provider, core)
    }

    @Test
    fun identityReceiptAndReadinessAreSeparateBarriers() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoIdentify = false
        core.autoReady = false
        val change = async { provider.onContextSet(a, b) }
        runCurrent()
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getStringEvaluation("x", "default", b)
        }
        core.identity = "B"
        core.identifies.last().second.complete(core.receipt())
        runCurrent()
        assertFalse(change.isCompleted)
        core.readyOperations.last().complete(Outcome.success(ReadyResult.OFFLINE_LOCAL))
        runCurrent()
        change.await()
        assertEquals("B", provider.getStringEvaluation("x", "default", b).value)
        finish(provider, core)
    }

    @Test
    fun lateOldReceiptCannotUnlockNewIdentity() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoIdentify = false
        val first = async { runCatching { provider.onContextSet(a, b) } }
        runCurrent()
        val second = async { provider.onContextSet(b, c) }
        runCurrent()
        core.identity = "C"
        core.identifies[1].second.complete(core.receipt())
        runCurrent()
        second.await()
        core.identifies[0].second.complete(Outcome.failure(OutcomeCode.SUPERSEDED, null))
        runCurrent()
        assertTrue(first.await().isFailure)
        assertEquals("C", provider.getStringEvaluation("x", "default", c).value)
        finish(provider, core)
    }

    @Test
    fun invalidLatestContextDoesNotFallBackToPendingUser() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoIdentify = false
        val first = async { runCatching { provider.onContextSet(a, b) } }
        runCurrent()
        val invalid = async { runCatching { provider.onContextSet(b, ImmutableContext(" ")) } }
        runCurrent()
        core.identifies[0].second.complete(core.receipt())
        runCurrent()
        assertTrue(invalid.await().exceptionOrNull() is OpenFeatureError.TargetingKeyMissingError)
        assertThrows(OpenFeatureError.TargetingKeyMissingError::class.java) {
            provider.getStringEvaluation("x", "default", a)
        }
        first.await()
        core.autoIdentify = true
        val recover = async { provider.onContextSet(null, c) }
        runCurrent()
        recover.await()
        assertEquals("C", provider.getStringEvaluation("x", "default", c).value)
        finish(provider, core)
    }

    @Test
    fun initializationTimeoutRetainsLateClientAndCanRecover() = runTest {
        val core = FakeCore()
        core.createOperation = ProviderOperation()
        val provider = provider(core)
        val init = async { runCatching { provider.initialize(a) } }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertTrue(init.await().isFailure)
        core.createOperation!!.complete(Outcome.success(core.client))
        runCurrent()
        assertEquals("A", provider.getStringEvaluation("x", "default", a).value)
        finish(provider, core)
    }

    @Test
    fun lateCreateAfterCloseIsClosedExactlyOnceAndTimeoutIsRetained() = runTest {
        val core = FakeCore()
        core.createOperation = ProviderOperation()
        val provider = provider(core)
        val init = async { runCatching { provider.initialize(a) } }
        runCurrent()
        val close = provider.close()
        assertSame(close, provider.close())
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertEquals(OutcomeCode.TIMED_OUT, close.getResult()!!.code)
        core.createOperation!!.complete(Outcome.success(core.client))
        runCurrent()
        assertEquals(1, core.closeCalls)
        assertEquals(0, core.registrations)
        assertEquals(OutcomeCode.TIMED_OUT, close.getResult()!!.code)
        init.await()
    }

    @Test
    fun creationUsesOneFrozenUserThenAdoptsLatest() = runTest {
        val core = FakeCore()
        core.createOperation = ProviderOperation()
        var calls = 0
        val provider =
            provider(core) {
                calls++
                ClientOptions.builder().user(it).offline(true).anonymousEnabled(true).build()
            }
        val initial = async { runCatching { provider.initialize(a) } }
        runCurrent()
        val change = async { provider.onContextSet(a, b) }
        runCurrent()
        core.createOperation!!.complete(Outcome.success(core.client))
        runCurrent()
        change.await()
        initial.await()
        assertEquals(1, calls)
        assertEquals(1, core.createCalls)
        assertEquals(listOf("B"), core.identifies.map { it.first })
        finish(provider, core)
    }

    @Test
    fun mismatchFactoryNeverCreatesClient() = runTest {
        val core = FakeCore()
        val provider =
            provider(core) {
                ClientOptions.builder()
                    .user(User.builder("wrong").name("wrong").build().value)
                    .offline(true)
                    .build()
            }
        val init = async { runCatching { provider.initialize(a) } }
        runCurrent()
        assertTrue(init.await().isFailure)
        assertEquals(0, core.createCalls)
        provider.close()
        runCurrent()
        assertTrue(provider.close().getResult()!!.value!!.cleanupComplete)
    }

    @Test
    fun modeFailureRecoversAccordingToActualOfflineState() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.modeFailure = OutcomeCode.INVALID
        val operation = provider.setOnline(100)
        runCurrent()
        assertEquals(OutcomeCode.INVALID, operation.getResult()!!.code)
        assertEquals("A", provider.getStringEvaluation("x", "default", a).value)
        finish(provider, core)
    }

    @Test
    fun submittedModeTimeoutRetainsCoreWorkAndRestoresReadiness() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoMode = false
        val operation = provider.setOnline(10)
        runCurrent()
        advanceTimeBy(11)
        runCurrent()
        assertEquals(OutcomeCode.TIMED_OUT, operation.getResult()!!.code)
        core.offline = false
        core.modes.single().complete(Outcome.success(ModeResult.ONLINE))
        runCurrent()
        assertEquals(OutcomeCode.TIMED_OUT, operation.getResult()!!.code)
        assertEquals("A", provider.getStringEvaluation("x", "default", a).value)
        finish(provider, core)
    }

    @Test
    fun queuedExpiredModeNeverSubmits() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoMode = false
        provider.setOnline(100)
        runCurrent()
        val queued = provider.setOffline(10)
        runCurrent()
        advanceTimeBy(11)
        runCurrent()
        assertEquals(OutcomeCode.TIMED_OUT, queued.getResult()!!.code)
        core.modes.single().complete(Outcome.success(ModeResult.ONLINE))
        runCurrent()
        assertEquals(1, core.modes.size)
        finish(provider, core)
    }

    @Test
    fun trackAllowsFiniteRoundedNumbersAndDropsNonfinite() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        val numbers =
            listOf<Number>(
                Long.MAX_VALUE,
                BigInteger("9007199254740993"),
                BigDecimal("1e-1000"),
                1.25,
            )
        numbers.forEach { provider.track("metric", a, TrackingEventDetails(it)) }
        provider.track("metric", a, TrackingEventDetails(Double.NaN))
        provider.track("metric", a, TrackingEventDetails(BigDecimal("1e1000")))
        assertEquals(numbers.map { "A" to it.toDouble() }, core.tracks)
        finish(provider, core)
    }

    @Test
    fun staleStatusUsesCurrentSnapshotAndTerminalStopsReads() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        val events = mutableListOf<dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents>()
        val collection =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                provider.observe().collect { events.add(it) }
            }
        core.status = SyncStatus.STALE
        core.signal()
        runCurrent()
        assertTrue(events.any { it is ProviderStale })
        assertEquals("A", provider.getStringEvaluation("x", "default", a).value)
        core.status = SyncStatus.TERMINAL
        core.signal()
        runCurrent()
        assertThrows(OpenFeatureError.ProviderFatalError::class.java) {
            provider.getStringEvaluation("x", "default", a)
        }
        collection.cancel()
        finish(provider, core)
    }

    @Test
    fun activeReadDrainsBeforeIdentitySubmissionAndLateResultIsRejected() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        var change: Deferred<Unit>? = null
        core.read = {
            core.read = null
            change = async(start = CoroutineStart.UNDISPATCHED) { provider.onContextSet(a, b) }
            runCurrent()
            assertTrue(core.identifies.isEmpty())
        }
        assertThrows(OpenFeatureError.InvalidContextError::class.java) {
            provider.getStringEvaluation("flag", "fallback", a)
        }
        runCurrent()
        change!!.await()
        assertEquals("B", provider.getStringEvaluation("flag", "fallback", b).value)
        finish(provider, core)
    }

    @Test
    fun contextThatExpiresWhileReadIsActiveNeverAdoptsLater() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        var change: Deferred<Result<Unit>>? = null
        core.read = {
            core.read = null
            change =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { provider.onContextSet(a, b) }
                }
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
        }
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getStringEvaluation("flag", "fallback", a)
        }
        runCurrent()
        assertTrue(change!!.await().isFailure)
        assertTrue(core.identifies.isEmpty())
        finish(provider, core)
    }

    @Test
    fun closeDuringReadRejectsLateSuccessWithoutWaitingForLease() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.read = {
            provider.close()
            runCurrent()
            assertEquals(1, core.closeCalls)
        }
        assertThrows(OpenFeatureError.ProviderFatalError::class.java) {
            provider.getStringEvaluation("flag", "fallback", a)
        }
        assertTrue(provider.close().getResult()!!.isSuccess)
    }

    @Test
    fun customNumberConversionRechecksIdentityWithoutHoldingReadLease() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        var change: Deferred<Unit>? = null
        val number =
            object : Number() {
                override fun toDouble(): Double {
                    change =
                        async(start = CoroutineStart.UNDISPATCHED) { provider.onContextSet(a, b) }
                    runCurrent()
                    return 1.0
                }

                override fun toFloat() = error("unused")

                override fun toLong() = error("unused")

                override fun toInt() = error("unused")

                override fun toShort() = error("unused")

                override fun toByte() = error("unused")
            }
        provider.track("metric", a, TrackingEventDetails(number))
        change!!.await()
        assertTrue(core.tracks.isEmpty())
        assertEquals("B", core.identity)
        finish(provider, core)
    }

    @Test
    fun extensionCapacityDoesNotCreateExtraCoreWork() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoMode = false
        repeat(64) { provider.setOnline(100) }
        assertEquals(OutcomeCode.CAPACITY_EXCEEDED, provider.setOnline(100).getResult()!!.code)
        runCurrent()
        assertEquals(1, core.modes.size)
        provider.close()
        core.modes[0].complete(Outcome.failure(OutcomeCode.CLOSED, null))
        runCurrent()
        assertEquals(1, core.closeCalls)
    }

    @Test
    fun anonymousPreparationCanBeSupersededByNamedIdentity() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoIdentify = false
        val anonymous = async { runCatching { provider.onContextSet(a, ImmutableContext()) } }
        runCurrent()
        val named = async { provider.onContextSet(null, c) }
        runCurrent()
        assertEquals(listOf("anonymous", "C"), core.identifies.map { it.first })
        core.identity = "C"
        core.identifies[1].second.complete(core.receipt())
        runCurrent()
        named.await()
        core.identifies[0].second.complete(Outcome.failure(OutcomeCode.SUPERSEDED, null))
        runCurrent()
        anonymous.await()
        assertEquals("C", provider.getStringEvaluation("x", "default", c).value)
        finish(provider, core)
    }

    @Test
    fun failedAdoptionNeverUsesOldUsersReadySignal() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoIdentify = false
        val change = async { runCatching { provider.onContextSet(a, b) } }
        runCurrent()
        core.identifies[0].second.complete(Outcome.failure(OutcomeCode.TIMED_OUT, null))
        core.signal()
        runCurrent()
        assertTrue(change.await().isFailure)
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            provider.getStringEvaluation("x", "default", b)
        }
        assertEquals(1, core.readyOperations.size)
        finish(provider, core)
    }

    @Test
    fun delayedReadinessCannotOverrideNewerUser() = runTest {
        val core = FakeCore()
        val provider = provider(core)
        initialize(provider)
        core.autoReady = false
        val first = async { runCatching { provider.onContextSet(a, b) } }
        runCurrent()
        val oldReady = core.readyOperations.last()
        val second = async { provider.onContextSet(b, c) }
        runCurrent()
        oldReady.complete(Outcome.success(ReadyResult.OFFLINE_LOCAL))
        runCurrent()
        assertFalse(second.isCompleted)
        core.readyOperations.last().complete(Outcome.success(ReadyResult.OFFLINE_LOCAL))
        runCurrent()
        second.await()
        first.await()
        assertEquals("C", provider.getStringEvaluation("x", "default", c).value)
        finish(provider, core)
    }

    @Test
    fun closeDuringFactoryPreventsCreateAndRetainsCleanupTimeout() = runTest {
        val core = FakeCore()
        lateinit var provider: FeatBitProvider
        provider =
            provider(core) { user ->
                provider.close()
                advanceTimeBy(5001)
                runCurrent()
                assertEquals(OutcomeCode.TIMED_OUT, provider.close().getResult()!!.code)
                ClientOptions.builder().user(user).offline(true).anonymousEnabled(true).build()
            }
        val initial = async { runCatching { provider.initialize(a) } }
        runCurrent()
        initial.await()
        assertEquals(0, core.createCalls)
        assertEquals(0, core.closeCalls)
        assertEquals(OutcomeCode.TIMED_OUT, provider.close().getResult()!!.code)
    }

    @Test
    fun latestContextAcceptedDuringFactoryUsesFrozenCreationThenAdopts() = runTest {
        val core = FakeCore()
        lateinit var provider: FeatBitProvider
        var switch: Deferred<Unit>? = null
        var factoryCalls = 0
        provider =
            provider(core) { user ->
                factoryCalls++
                assertEquals("A", user!!.key)
                switch = async(start = CoroutineStart.UNDISPATCHED) { provider.onContextSet(a, b) }
                ClientOptions.builder().user(user).offline(true).anonymousEnabled(true).build()
            }
        val initial = async { runCatching { provider.initialize(a) } }
        runCurrent()
        initial.await()
        switch!!.await()
        assertEquals(1, factoryCalls)
        assertEquals(1, core.createCalls)
        assertEquals("B", provider.getStringEvaluation("x", "default", b).value)
        finish(provider, core)
    }

    @Test
    fun cancelledCallerDoesNotAbandonCreation() = runTest {
        val core = FakeCore()
        core.createOperation = ProviderOperation()
        val provider = provider(core)
        val caller = async { provider.initialize(a) }
        runCurrent()
        caller.cancel()
        runCurrent()
        core.createOperation!!.complete(Outcome.success(core.client))
        runCurrent()
        assertEquals("A", provider.getStringEvaluation("x", "default", a).value)
        finish(provider, core)
    }
}
