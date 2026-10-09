@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package co.featbit.openfeature.android

import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class EventAndOperationTest {
    @Test
    fun seventyCollectorsShareLifecycleAndLateCollectorGetsAll() = runTest {
        val bridge = EventBridge()
        val received = List(70) { mutableListOf<OpenFeatureProviderEvents>() }
        val jobs =
            received.map { list ->
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    bridge.observe().collect { list.add(it) }
                }
            }
        bridge.created()
        bridge.state(ProviderReady(), true)
        runCurrent()
        received.forEach {
            assertTrue(it.first() is ProviderReady)
            assertTrue(
                (it.last() as ProviderConfigurationChanged)
                    .eventDetails!!
                    .eventMetadata["featbit.allFlagsChanged"] == true
            )
        }
        val late = mutableListOf<OpenFeatureProviderEvents>()
        val collector =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                bridge.observe().collect { late.add(it) }
            }
        assertEquals(received.first(), late)
        bridge.close()
        runCurrent()
        jobs.forEach { assertTrue(it.isCompleted) }
        assertTrue(collector.isCompleted)
    }

    @Test
    fun slowCollectorRetainsLatestStateAndBoundedAllInvalidation() = runTest {
        val bridge = EventBridge()
        val release = CompletableDeferred<Unit>()
        val received = mutableListOf<OpenFeatureProviderEvents>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                bridge.observe().collect {
                    received.add(it)
                    if (received.size == 1) release.await()
                }
            }
        bridge.state(ProviderReady(), true)
        repeat(1100) { bridge.change(FlagChange(setOf("flag-$it"), false)) }
        bridge.state(ProviderStale(), true)
        bridge.state(ProviderReady(), true)
        release.complete(Unit)
        runCurrent()
        assertEquals(3, received.size)
        assertTrue(received[1] is ProviderReady)
        assertEquals(true, received[2].eventDetails!!.eventMetadata["featbit.allFlagsChanged"])
        bridge.close()
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun contextTransitionDefersConfigurationUntilReady() = runTest {
        val bridge = EventBridge()
        val events = mutableListOf<OpenFeatureProviderEvents>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            bridge.observe().collect { events.add(it) }
        }
        bridge.created()
        bridge.state(ProviderReady(), true)
        events.clear()
        bridge.transition()
        bridge.change(FlagChange(setOf("x"), false))
        runCurrent()
        assertTrue(events.isEmpty())
        bridge.state(ProviderStale(), true)
        runCurrent()
        assertTrue(events[0] is ProviderStale)
        assertTrue(events[1] is ProviderConfigurationChanged)
        bridge.close()
    }

    @Test
    fun retainedResultClosesObserveRaceAndCompletesOnce() = runTest {
        var detached = 0
        var callbacks = 0
        val result = Outcome.success(ReadyResult.CUSTOM_LOCAL)
        val racing =
            object : Operation<ReadyResult> {
                var retained: Outcome<ReadyResult>? = null

                override fun getResult() = retained

                override fun observe(callback: Completion<ReadyResult>): Outcome<Registration> {
                    retained = result
                    callback.onComplete(result)
                    callback.onComplete(result)
                    return Outcome.success(
                        object : Registration {
                            override fun close() {
                                detached++
                            }
                        }
                    )
                }
            }
        val bridge = OperationBridge(backgroundScope)
        bridge.watch(racing) { callbacks++ }
        assertEquals(1, callbacks)
        assertEquals(1, detached)
    }

    @Test
    fun rejectedObservationUsesSharedProbeAndCancellationDetaches() = runTest {
        var result: Outcome<ReadyResult>? = null
        val operation =
            object : Operation<ReadyResult> {
                override fun getResult() = result

                override fun observe(callback: Completion<ReadyResult>): Outcome<Registration> =
                    Outcome.failure(OutcomeCode.CAPACITY_EXCEEDED, null)
            }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val bridge = OperationBridge(scope)
        val first = async { bridge.await(operation) }
        val second = async { bridge.await(operation) }
        runCurrent()
        first.cancel()
        result = Outcome.success(ReadyResult.CUSTOM_LOCAL)
        advanceTimeBy(51)
        runCurrent()
        assertEquals(ReadyResult.CUSTOM_LOCAL, second.await().value)
        assertTrue(first.isCancelled)
        scope.cancel()
    }
}
