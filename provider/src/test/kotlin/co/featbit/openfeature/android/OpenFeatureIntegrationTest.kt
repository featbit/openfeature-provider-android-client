@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi::class,
)

package co.featbit.openfeature.android

import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.*
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class OpenFeatureIntegrationTest {
    @Test
    fun realClientPreservesFallbackAndRunsErrorHookOnce() = runTest {
        val core = FakeCore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val provider =
            FeatBitProvider(
                ProviderRuntime(
                    TestContext(),
                    { ClientOptions.builder().user(it).offline(true).build() },
                    IdentityPolicy.REQUIRE_TARGETING_KEY,
                    100,
                    100,
                    core.factory,
                    dispatcher,
                ) {
                    testScheduler.currentTime
                }
            )
        val api = createOpenFeatureAPIInstance()
        val registration = async {
            api.setProviderAndWait(provider, ImmutableContext("A"), dispatcher)
        }
        runCurrent()
        registration.await()
        var errors = 0
        api.addHooks(
            listOf(
                object : Hook<Any> {
                    override fun error(
                        ctx: HookContext<Any>,
                        error: Exception,
                        hints: Map<String, Any>,
                    ) {
                        errors++
                    }
                }
            )
        )
        core.reason = EvaluationReason.WRONG_TYPE
        val result = api.getClient().getLongDetails("flag", Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, result.value)
        assertEquals(ErrorCode.TYPE_MISMATCH, result.errorCode)
        assertEquals(1, errors)
        val fallback = Value.List(listOf(Value.Long(Long.MAX_VALUE), Value.Null))
        assertEquals(fallback, api.getClient().getObjectValue("flag", fallback))
        api.shutdown()
        runCurrent()
        assertEquals(1, core.closeCalls)
    }

    /** A passing reproduction asserts the defect, not compliance with release gate G02. */
    @Test
    fun upstream090OverwritesStaleAndFatalDuringInitialization() = runTest {
        for (event in
            listOf(
                ProviderStale(),
                ProviderError(EventDetails(errorCode = ErrorCode.PROVIDER_FATAL)),
            )) {
            val gate = CompletableDeferred<Unit>()
            val stream = MutableSharedFlow<OpenFeatureProviderEvents>(extraBufferCapacity = 1)
            val provider =
                object : FeatureProvider by NoOpProvider() {
                    override fun observe(): Flow<OpenFeatureProviderEvents> = stream

                    override suspend fun initialize(initialContext: EvaluationContext?) {
                        gate.await()
                    }
                }
            val api = createOpenFeatureAPIInstance()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val init = async { api.setProviderAndWait(provider, dispatcher = dispatcher) }
            runCurrent()
            stream.emit(event)
            runCurrent()
            assertTrue(
                api.getStatus() is OpenFeatureStatus.Stale ||
                    api.getStatus() is OpenFeatureStatus.Fatal
            )
            gate.complete(Unit)
            runCurrent()
            init.await()
            assertEquals(OpenFeatureStatus.Ready, api.getStatus()) // Known upstream defect.
            api.shutdown()
        }
    }

    @Test
    fun upstream090OverwritesStaleOnContextReturn() = runTest {
        val gate = CompletableDeferred<Unit>()
        val stream = MutableSharedFlow<OpenFeatureProviderEvents>(extraBufferCapacity = 1)
        val provider =
            object : FeatureProvider by NoOpProvider() {
                override fun observe(): Flow<OpenFeatureProviderEvents> = stream

                override suspend fun onContextSet(
                    oldContext: EvaluationContext?,
                    newContext: EvaluationContext,
                ) {
                    gate.await()
                }
            }
        val api = createOpenFeatureAPIInstance()
        val dispatcher = StandardTestDispatcher(testScheduler)
        api.setProviderAndWait(provider, dispatcher = dispatcher)
        runCurrent()
        val changing = async { api.setEvaluationContextAndWait(ImmutableContext("B")) }
        runCurrent()
        stream.emit(ProviderStale())
        runCurrent()
        assertEquals(OpenFeatureStatus.Stale, api.getStatus())
        gate.complete(Unit)
        runCurrent()
        changing.await()
        assertEquals(OpenFeatureStatus.Ready, api.getStatus()) // Known upstream defect.
        api.shutdown()
    }

    @Test
    fun upstream090OldInitializerOverwritesReplacementFailure() = runTest {
        val release = CompletableDeferred<Unit>()
        val old =
            object : FeatureProvider by NoOpProvider() {
                override suspend fun initialize(initialContext: EvaluationContext?) {
                    release.await()
                }
            }
        val replacement =
            object : FeatureProvider by NoOpProvider() {
                override suspend fun initialize(initialContext: EvaluationContext?) {
                    throw dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError.GeneralError(
                        "expected_failure"
                    )
                }
            }
        val api = createOpenFeatureAPIInstance()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = async { api.setProviderAndWait(old, dispatcher = dispatcher) }
        runCurrent()
        api.setProviderAndWait(replacement, dispatcher = dispatcher)
        assertTrue(api.getStatus() is OpenFeatureStatus.Error)
        release.complete(Unit)
        runCurrent()
        first.await()
        assertSame(replacement, api.getProvider())
        assertEquals(OpenFeatureStatus.Ready, api.getStatus()) // Known upstream defect.
        api.shutdown()
    }
}
