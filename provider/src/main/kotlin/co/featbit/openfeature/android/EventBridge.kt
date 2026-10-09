package co.featbit.openfeature.android

import co.featbit.android.api.FlagChange
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Slow collectors retain one lifecycle state and a bounded union of invalidated keys. */
internal class EventBridge(scope: CoroutineScope? = null) {
    private class Slot {
        val wake = Channel<Unit>(Channel.CONFLATED)
        var lifecycle: OpenFeatureProviderEvents? = null
        val keys = linkedSetOf<String>()
        var all = false
    }

    private val lock = Any()
    private val slots = linkedSetOf<Slot>()
    private var lifecycle: OpenFeatureProviderEvents? = null
    private var readable = false
    private var created = false
    private var closed = false
    private val notifications = Channel<Unit>(Channel.CONFLATED)
    private val notifier =
        scope?.launch(start = CoroutineStart.LAZY) {
            for (ignored in notifications) {
                if (notifySlots()) break
            }
        }

    private fun notifySlots(): Boolean {
        val (targets, ended) =
            synchronized(lock) { Pair(slots.toList(), closed).also { if (closed) slots.clear() } }
        targets.forEach { if (ended) it.wake.close() else it.wake.trySend(Unit) }
        return ended
    }

    private fun notifyCollectors() {
        if (notifier == null) notifySlots()
        else {
            notifier.start()
            notifications.trySend(Unit)
        }
    }

    suspend fun awaitClosed() {
        notifier?.join()
    }

    fun created() {
        synchronized(lock) {
            created = true
            slots.toList().also { list ->
                list.forEach {
                    it.all = true
                    it.keys.clear()
                }
            }
        }
        notifyCollectors()
    }

    fun transition() =
        synchronized(lock) {
            readable = false
            lifecycle = null
            slots.forEach {
                it.lifecycle = null
                it.all = true
                it.keys.clear()
            }
        }

    fun state(event: OpenFeatureProviderEvents, canRead: Boolean) {
        synchronized(lock) {
            if (closed) return
            readable = canRead
            if (lifecycle == event) return
            lifecycle = event
            slots.toList().also { list -> list.forEach { it.lifecycle = event } }
        }
        notifyCollectors()
    }

    fun change(change: FlagChange) {
        synchronized(lock) {
            if (closed) return
            slots.toList().also { list ->
                list.forEach {
                    if (change.allFlagsChanged) {
                        it.all = true
                        it.keys.clear()
                    } else if (!it.all) {
                        // Iterate without copying an unbounded incoming set into each slot.
                        for (key in change.keys) {
                            it.keys.add(key)
                            if (it.keys.size > 1024) {
                                it.all = true
                                it.keys.clear()
                                break
                            }
                        }
                    }
                }
            }
        }
        notifyCollectors()
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lifecycle = null
            slots.toList().also { list ->
                list.forEach {
                    it.lifecycle = null
                    it.keys.clear()
                    it.all = false
                }
            }
        }
        notifyCollectors()
    }

    fun observe(): Flow<OpenFeatureProviderEvents> = flow {
        val slot = Slot()
        val ended =
            synchronized(lock) {
                if (closed) true
                else {
                    slot.lifecycle = lifecycle
                    slot.all = created
                    slots.add(slot)
                    false
                }
            }
        if (ended) slot.wake.close() else slot.wake.trySend(Unit)
        try {
            for (ignored in slot.wake) {
                while (true) {
                    val event =
                        synchronized(lock) {
                            val state = slot.lifecycle
                            if (state != null) {
                                slot.lifecycle = null
                                state
                            } else if (readable && (slot.all || slot.keys.isNotEmpty())) {
                                ProviderConfigurationChanged(
                                        EventDetails(
                                            flagsChanged =
                                                if (slot.all) emptySet() else slot.keys.toSet(),
                                            eventMetadata =
                                                if (slot.all)
                                                    mapOf("featbit.allFlagsChanged" to true)
                                                else emptyMap(),
                                        )
                                    )
                                    .also {
                                        slot.all = false
                                        slot.keys.clear()
                                    }
                            } else null
                        } ?: break
                    emit(event)
                }
            }
        } finally {
            synchronized(lock) { slots.remove(slot) }
            slot.wake.close()
        }
    }
}
