package co.featbit.openfeature.android

import co.featbit.android.api.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/** Retains completion independently of observers. Callbacks never run under its monitor. */
internal class ProviderOperation<T> : Operation<T> {
    private val lock = Any()
    private var result: Outcome<T>? = null
    private val observers = linkedMapOf<Any, Completion<T>>()

    override fun getResult(): Outcome<T>? = synchronized(lock) { result }

    override fun observe(callback: Completion<T>): Outcome<Registration> {
        val token = Any()
        val done =
            synchronized(lock) { result.also { if (it == null) observers[token] = callback } }
        if (done != null) deliver(callback, done)
        return Outcome.success(
            object : Registration {
                override fun close() {
                    synchronized(lock) { observers.remove(token) }
                }
            }
        )
    }

    fun complete(value: Outcome<T>) {
        val callbacks =
            synchronized(lock) {
                if (result != null) return
                result = value
                observers.values.toList().also { observers.clear() }
            }
        callbacks.forEach { deliver(it, value) }
    }

    private fun deliver(callback: Completion<T>, value: Outcome<T>) {
        try {
            callback.onComplete(value)
        } catch (_: Exception) {
            /* Isolate application callbacks. */
        }
    }
}

/** One exceptional probe loop per provider, only for core operations that refuse observation. */
internal class OperationBridge(private val scope: CoroutineScope) {
    private val lock = Any()
    private val probes = linkedMapOf<Any, () -> Boolean>()
    private var probing = false

    fun <T> watch(operation: Operation<T>, complete: (Outcome<T>) -> Unit): Registration {
        val settled = AtomicBoolean()
        val registration = AtomicReference<Registration?>()
        val token = Any()
        fun accept(result: Outcome<T>) {
            if (settled.compareAndSet(false, true)) {
                synchronized(lock) { probes.remove(token) }
                registration.get()?.close()
                complete(result)
            }
        }
        operation.getResult()?.let(::accept)
        if (!settled.get()) {
            val observed = operation.observe { accept(it) }
            registration.set(observed.value)
            if (settled.get()) registration.get()?.close()
            operation.getResult()?.let(::accept)
            if (!observed.isSuccess && !settled.get()) {
                val launch =
                    synchronized(lock) {
                        probes[token] = {
                            operation.getResult()?.let {
                                accept(it)
                                true
                            } ?: false
                        }
                        if (probing) false
                        else {
                            probing = true
                            true
                        }
                    }
                if (launch)
                    scope.launch {
                        while (true) {
                            delay(50)
                            val work =
                                synchronized(lock) {
                                    if (probes.isEmpty()) {
                                        probing = false
                                        return@launch
                                    }
                                    probes.values.toList()
                                }
                            work.forEach { it() }
                        }
                    }
            }
        }
        return object : Registration {
            override fun close() {
                settled.set(true)
                registration.get()?.close()
                synchronized(lock) { probes.remove(token) }
            }
        }
    }

    suspend fun <T> await(operation: Operation<T>): Outcome<T> =
        suspendCancellableCoroutine { continuation ->
            val registration =
                watch(operation) { if (continuation.isActive) continuation.resume(it) }
            continuation.invokeOnCancellation { registration.close() }
        }
}
