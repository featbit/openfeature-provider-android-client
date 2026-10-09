package co.featbit.openfeature.android

import android.content.Context
import android.os.SystemClock
import android.util.Log
import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.*
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The monitor protects admission and receipts, not SDK calls. Submission is serialized separately;
 * identity completion is deliberately not serialized, so a later identity can supersede
 * preparation. Owner tasks outlive callers and retain every create/close result, even after
 * external timeouts.
 */
internal class ProviderRuntime(
    private val applicationContext: Context,
    private val factory: FeatBitOptionsFactory,
    private val policy: IdentityPolicy,
    private val initializationTimeout: Long,
    private val contextTimeout: Long,
    private val clientFactory: ClientFactory = ClientFactory.getDefault(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Request(val initialization: Boolean, val deadline: Long) {
        val completion = CompletableDeferred<Unit>()
        var identity: Identity? = null
        var expired = false
        var submitted = false
        var ownsReceipt = false
        var job: Job? = null
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val submission = Mutex()
    private val bridge = OperationBridge(scope)
    private val readersChanged = Channel<Unit>(Channel.CONFLATED)
    val events = EventBridge(scope)
    private var latest: Request? = null
    private var adopted: Identity? = null
    private var client: FeatBitClient? = null
    private var options: ClientOptions? = null
    private var creation: Deferred<FeatBitClient>? = null
    private var installed = false
    private var closing = false
    private var fatal = false
    private var readable = false
    private var error: OpenFeatureError? = null
    private var readers = 0
    private var pendingIdentity = 0
    private var modeRevision = Any()
    private var modes = 0
    private var waiterCount = 0
    private var commands = 0
    private var commandTail: CompletableDeferred<Unit>? = null
    private var recovering: Request? = null
    private var hintDirty = false
    private var readyRevision = Any()
    private var cleanupFinished = false
    private var statusRegistration: Registration? = null
    private var changeRegistration: Registration? = null
    private var lastConnection: ConnectionInformation? = null
    private var warnedStructure = false
    private val closeResult = ProviderOperation<CloseResult>()
    private val pendingCommands = linkedSetOf<() -> Unit>()

    private fun deadline(timeout: Long): Long =
        now().let { if (timeout > Long.MAX_VALUE - it) Long.MAX_VALUE else it + timeout }

    private fun remaining(deadline: Long): Long = (deadline - now()).coerceAtLeast(0)

    private fun current(request: Request) =
        !closing && !fatal && latest === request && !request.expired

    private fun notReady() = OpenFeatureError.ProviderNotReadyError("provider_not_ready")

    private fun closedError() = OpenFeatureError.ProviderFatalError("provider_closed")

    private fun fail(code: OutcomeCode) = Diagnostic("provider_" + code.name.lowercase())

    suspend fun context(context: EvaluationContext?, initialization: Boolean) {
        val request =
            Request(
                initialization,
                deadline(if (initialization) initializationTimeout else contextTimeout),
            )
        val previous =
            synchronized(lock) {
                if (closing) throw closedError()
                if (fatal) throw OpenFeatureError.ProviderFatalError("provider_fatal")
                if (waiterCount >= 64)
                    throw OpenFeatureError.GeneralError("provider_waiter_capacity")
                waiterCount++
                val previous = latest
                latest = request
                readable = false
                error = null
                events.transition()
                previous
            }
        previous?.completion?.cancel(CancellationException("provider_context_superseded"))
        val obsolete = synchronized(lock) { previous?.takeUnless { it.ownsReceipt }?.job }
        obsolete?.cancel()
        try {
            val identity =
                try {
                    ContextMapper.map(
                        context,
                        policy,
                        synchronized(lock) { options?.automaticAttributes == true },
                    )
                } catch (e: OpenFeatureError) {
                    reject(request, e)
                    throw e
                } catch (_: Exception) {
                    val e = OpenFeatureError.InvalidContextError("provider_context_mapping_failed")
                    reject(request, e)
                    throw e
                }
            synchronized(lock) { if (current(request)) request.identity = identity }
            val work = scope.launch(start = CoroutineStart.LAZY) { reconcile(request) }
            val accepted =
                synchronized(lock) {
                    request.job = work
                    current(request)
                }
            if (accepted) work.start() else work.cancel()
            try {
                withTimeout(remaining(request.deadline).coerceAtLeast(1)) {
                    request.completion.await()
                }
            } catch (e: TimeoutCancellationException) {
                val pending =
                    synchronized(lock) {
                        // Stop only unsubmitted reconciliation; the creation task remains owned.
                        if (!initialization && !request.submitted) {
                            request.expired = true
                            request.job
                        } else null
                    }
                pending?.cancel()
                reject(request, notReady())
                throw notReady()
            }
        } finally {
            synchronized(lock) { waiterCount-- }
        }
    }

    private fun reject(request: Request, original: OpenFeatureError) {
        var failure = original
        synchronized(lock) {
            if (latest !== request || closing) return
            if (fatal) failure = OpenFeatureError.ProviderFatalError("provider_fatal")
            readable = false
            error = failure
            events.state(
                ProviderError(
                    EventDetails(errorCode = failure.errorCode(), message = failure.message)
                ),
                false,
            )
        }
        request.completion.completeExceptionally(failure)
    }

    private suspend fun ensureClient(request: Request): FeatBitClient {
        val task =
            synchronized(lock) {
                if (closing) throw closedError()
                creation
                    ?: run {
                        // Reserve the one creation attempt while the latest mapped context is
                        // valid.
                        // This identity is frozen before leaving the monitor; the factory runs
                        // outside it.
                        val newest = latest
                        val frozen =
                            newest?.takeIf { current(it) }?.identity
                                ?: throw CancellationException("provider_context_superseded")
                        scope
                            .async(start = CoroutineStart.LAZY) {
                                synchronized(lock) { if (closing) throw closedError() }
                                val configured =
                                    try {
                                        factory.create(frozen.user())
                                    } catch (_: Exception) {
                                        throw OpenFeatureError.GeneralError(
                                            "provider_options_factory_failed"
                                        )
                                    }
                                val built =
                                    configured.value
                                        ?: throw OpenFeatureError.GeneralError(
                                            "provider_options_invalid"
                                        )
                                if (
                                    !frozen.matches(built.initialUser) ||
                                        (policy == IdentityPolicy.PERSISTENT_ANONYMOUS &&
                                            !built.anonymousEnabled) ||
                                        (built.automaticAttributes &&
                                            frozen.attributes.keys.any {
                                                it.startsWith("featbit.sdk.")
                                            })
                                )
                                    throw OpenFeatureError.GeneralError(
                                        "provider_options_identity_mismatch"
                                    )
                                synchronized(lock) {
                                    options = built
                                    if (closing) throw closedError()
                                }
                                // No suspension between the close check and create submission. Late
                                // clients are owned.
                                val created =
                                    bridge
                                        .await(clientFactory.create(applicationContext, built))
                                        .value
                                        ?: throw OpenFeatureError.GeneralError(
                                            "provider_client_creation_failed"
                                        )
                                synchronized(lock) {
                                    client = created
                                    adopted = frozen
                                }
                                val isClosed = synchronized(lock) { closing }
                                if (!isClosed) install(created)
                                created
                            }
                            .also { creation = it }
                    }
            }
        return try {
            task.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                if (!closing) {
                    fatal = true
                    readable = false
                    error = OpenFeatureError.ProviderFatalError("provider_initialization_failed")
                    events.state(
                        ProviderError(
                            EventDetails(
                                errorCode = ErrorCode.PROVIDER_FATAL,
                                message = "provider_initialization_failed",
                            )
                        ),
                        false,
                    )
                }
            }
            throw if (e is OpenFeatureError) e
            else OpenFeatureError.GeneralError("provider_initialization_failed")
        }
    }

    private fun install(created: FeatBitClient) {
        val status = created.subscribeStatus { hint() }.value
        val changes = created.subscribeChanges { events.change(it) }.value?.registration
        val dispose =
            synchronized(lock) {
                if (closing || status == null || changes == null) true
                else {
                    statusRegistration = status
                    changeRegistration = changes
                    installed = true
                    events.created()
                    false
                }
            }
        if (dispose) {
            status?.close()
            changes?.close()
        }
        if (status == null || changes == null) {
            // The created client must remain owned even when subscription installation fails.
            close()
            throw OpenFeatureError.GeneralError("provider_subscription_failed")
        }
    }

    private suspend fun drain(request: Request? = null) {
        while (true) {
            val count =
                synchronized(lock) {
                    if (closing) throw closedError()
                    if (request != null && !current(request))
                        throw CancellationException("provider_context_superseded")
                    readers
                }
            if (count == 0) return
            readersChanged.receive()
        }
    }

    private suspend fun reconcile(request: Request) {
        try {
            if (!synchronized(lock) { current(request) && request.identity != null }) return
            val core = ensureClient(request)
            var operation: Operation<IdentityReceipt>? = null
            submission.withLock {
                drain(request)
                val target = request.identity!!
                val submit =
                    synchronized(lock) {
                        if (!current(request)) return@withLock
                        val left = remaining(request.deadline)
                        if (!request.initialization && left == 0L) {
                            request.expired = true
                            return@withLock
                        }
                        if (
                            options?.automaticAttributes == true &&
                                target.attributes.keys.any { it.startsWith("featbit.sdk.") }
                        )
                            throw OpenFeatureError.InvalidContextError(
                                "provider_reserved_attribute"
                            )
                        request.submitted = true
                        if (adopted == target && pendingIdentity == 0) false
                        else {
                            pendingIdentity++
                            request.ownsReceipt = true
                            adopted = null
                            true
                        }
                    }
                if (submit)
                    operation =
                        if (target.key == null)
                            core.identifyAnonymousContext(
                                remaining(request.deadline).coerceAtLeast(1)
                            )
                        else
                            core.identifyContext(
                                target.user()!!,
                                remaining(request.deadline).coerceAtLeast(1),
                            )
            }
            operation?.let { op ->
                val receipt = bridge.await(op)
                synchronized(lock) {
                    pendingIdentity--
                    request.ownsReceipt = false
                    if (current(request) && receipt.isSuccess) adopted = request.identity
                }
                if (!receipt.isSuccess) throw outcomeError(receipt.code)
            }
            if (
                synchronized(lock) {
                    current(request) && adopted == request.identity && request.submitted
                }
            ) {
                ready(core, request, remaining(request.deadline).coerceAtLeast(1))
            }
        } catch (e: CancellationException) {
            request.completion.cancel(e)
        } catch (e: OpenFeatureError) {
            reject(request, e)
        } catch (_: Exception) {
            reject(request, OpenFeatureError.GeneralError("provider_reconciliation_failed"))
        }
    }

    private suspend fun ready(core: FeatBitClient, request: Request, timeout: Long) {
        val attempt = Any()
        val revision =
            synchronized(lock) {
                readyRevision = attempt
                modeRevision
            }
        val outcome = bridge.await(core.awaitReady(timeout))
        val snapshot = core.getConnectionInformation()
        val failure =
            synchronized(lock) {
                lastConnection = snapshot
                if (
                    !current(request) ||
                        revision !== modeRevision ||
                        readyRevision !== attempt ||
                        modes != 0 ||
                        adopted != request.identity
                )
                    return
                if (
                    snapshot.status == SyncStatus.TERMINAL || snapshot.status == SyncStatus.CLOSED
                ) {
                    fatal = true
                    OpenFeatureError.ProviderFatalError("provider_core_terminal")
                } else if (!outcome.isSuccess) outcomeError(outcome.code)
                else {
                    readable = true
                    error = null
                    val details =
                        EventDetails(
                            eventMetadata = mapOf("featbit.readiness" to outcome.value!!.name)
                        )
                    events.state(
                        if (snapshot.status == SyncStatus.STALE) ProviderStale(details)
                        else ProviderReady(details),
                        true,
                    )
                    null
                }
            }
        if (failure != null) reject(request, failure) else request.completion.complete(Unit)
    }

    private fun hint() {
        val pair =
            synchronized(lock) {
                if (closing || fatal || !installed || modes != 0 || pendingIdentity != 0) return
                val request = latest ?: return
                if (recovering === request) {
                    hintDirty = true
                    return
                }
                if (!current(request) || request.identity == null || adopted != request.identity)
                    return
                recovering = request
                Pair(client!!, request)
            }
        scope.launch {
            try {
                ready(pair.first, pair.second, contextTimeout)
            } catch (_: Exception) {
                reject(pair.second, notReady())
            } finally {
                val again =
                    synchronized(lock) {
                        if (recovering === pair.second) {
                            recovering = null
                            hintDirty.also { hintDirty = false }
                        } else false
                    }
                if (again) hint()
            }
        }
    }

    private fun outcomeError(code: OutcomeCode): OpenFeatureError =
        when (code) {
            OutcomeCode.TIMED_OUT,
            OutcomeCode.DEFERRED -> notReady()
            OutcomeCode.CLOSED,
            OutcomeCode.TERMINAL_FAILURE ->
                OpenFeatureError.ProviderFatalError("provider_core_terminal")
            else -> OpenFeatureError.GeneralError("provider_operation_failed")
        }

    fun <T> evaluate(
        context: EvaluationContext?,
        read: (FeatBitClient) -> T,
    ): ProviderEvaluation<T> {
        val identity =
            ContextMapper.map(
                context,
                policy,
                synchronized(lock) { options?.automaticAttributes == true },
            )
        val lease =
            synchronized(lock) {
                checkRead(identity)
                readers++
                Pair(client!!, latest!!)
            }
        try {
            val value = read(lease.first)
            synchronized(lock) {
                checkRead(identity)
                if (latest !== lease.second) throw notReady()
            }
            return ValueMapper.result(value)
        } finally {
            synchronized(lock) { readers-- }
            readersChanged.trySend(Unit)
        }
    }

    private fun checkRead(identity: Identity) {
        if (closing) throw closedError()
        if (fatal) throw OpenFeatureError.ProviderFatalError("provider_fatal")
        error?.let { throw it }
        if (latest?.identity != identity)
            throw OpenFeatureError.InvalidContextError("provider_context_not_current")
        if (!readable || modes != 0 || adopted != identity) throw notReady()
    }

    fun track(name: String, context: EvaluationContext?, details: TrackingEventDetails?) {
        val numeric =
            try {
                details?.value?.toDouble()
            } catch (_: Exception) {
                log("provider_track_number_failed")
                return
            }
        if (numeric != null && !numeric.isFinite()) {
            log("provider_track_nonfinite")
            return
        }
        val hasStructure =
            try {
                details?.asMap()?.isNotEmpty() == true
            } catch (_: Exception) {
                log("provider_track_structure_failed")
                return
            }
        if (hasStructure) {
            val warn =
                synchronized(lock) {
                    if (warnedStructure) false
                    else {
                        warnedStructure = true
                        true
                    }
                }
            if (warn) log("provider_track_structure_ignored")
        }
        try {
            val result =
                evaluate(context) {
                        if (numeric == null) it.track(name) else it.track(name, numeric)
                    }
                    .value
            if (!result.isSuccess) log("provider_track_rejected")
        } catch (_: Exception) {
            log("provider_track_not_ready")
        }
    }

    private fun log(code: String) {
        val configured = synchronized(lock) { options }
        if (configured?.logLevel == LogLevel.NONE || configured?.logLevel == LogLevel.ERROR) return
        try {
            val logger = configured?.logger
            if (logger != null) logger.log(LogLevel.WARN, Diagnostic(code))
            else Log.w("FeatBitProvider", code)
        } catch (_: Exception) {
            /* Logging cannot affect lifecycle or evaluation. */
        }
    }

    fun connection(): ConnectionInformation? {
        val core = synchronized(lock) { client }
        return core?.getConnectionInformation()?.also { synchronized(lock) { lastConnection = it } }
            ?: synchronized(lock) { lastConnection }
    }

    fun mode(offline: Boolean, timeout: Long): Operation<ModeResult> =
        command(timeout, mutation = true) { core, left ->
            if (offline) core.setOffline(left) else core.setOnline(left)
        }

    fun flush(): Operation<FlushResult> =
        command(
            synchronized(lock) { options?.requestTimeoutMillis ?: 10_000 },
            mutation = false,
            allowFatal = true,
        ) { core, _ ->
            core.flush()
        }

    fun clearCache(cacheScope: CacheScope, timeout: Long): Operation<CacheClearResult> =
        command(timeout, mutation = false, stableIdentity = true) { core, left ->
            core.clearCache(cacheScope, left)
        }

    private fun <T> command(
        timeout: Long,
        mutation: Boolean,
        allowFatal: Boolean = false,
        stableIdentity: Boolean = false,
        submit: (FeatBitClient, Long) -> Operation<T>,
    ): Operation<T> {
        val result = ProviderOperation<T>()
        val finished = CompletableDeferred<Unit>()
        val predecessor: CompletableDeferred<Unit>?
        val due = deadline(timeout.coerceAtLeast(0))
        val onClose = {
            result.complete(Outcome.failure(OutcomeCode.CLOSED, fail(OutcomeCode.CLOSED)))
        }
        synchronized(lock) {
            val failure =
                when {
                    closing -> OutcomeCode.CLOSED
                    timeout <= 0 -> OutcomeCode.INVALID
                    !installed -> OutcomeCode.INVALID
                    fatal && !allowFatal -> OutcomeCode.TERMINAL_FAILURE
                    stableIdentity &&
                        (error != null ||
                            adopted != latest?.identity ||
                            pendingIdentity != 0 ||
                            !readable) -> OutcomeCode.INVALID
                    commands >= 64 -> OutcomeCode.CAPACITY_EXCEEDED
                    else -> null
                }
            if (failure != null) {
                result.complete(
                    Outcome.failure(
                        failure,
                        if (!installed && !closing) Diagnostic("provider_not_initialized")
                        else fail(failure),
                    )
                )
                return result
            }
            commands++
            predecessor = commandTail
            commandTail = finished
            pendingCommands.add(onClose)
            if (mutation) {
                modes++
                modeRevision = Any()
                readable = false
                events.transition()
            }
        }
        val timer =
            scope.launch {
                delay(remaining(due))
                result.complete(Outcome.failure(OutcomeCode.TIMED_OUT, fail(OutcomeCode.TIMED_OUT)))
            }
        scope.launch {
            var acquired = false
            try {
                withTimeout(remaining(due).coerceAtLeast(1)) {
                    predecessor?.await()
                    submission.lock()
                    acquired = true
                    if (mutation) drain()
                }
                run {
                    val admission =
                        synchronized(lock) {
                            when {
                                closing -> OutcomeCode.CLOSED
                                remaining(due) == 0L -> OutcomeCode.TIMED_OUT
                                stableIdentity &&
                                    (error != null || adopted != latest?.identity || !readable) ->
                                    OutcomeCode.INVALID
                                else -> null
                            }
                        }
                    if (admission != null) {
                        result.complete(Outcome.failure(admission, fail(admission)))
                        return@run
                    }
                    val core = synchronized(lock) { client!! }
                    // Keep ownership even if the external timer has fired after submission.
                    result.complete(bridge.await(submit(core, remaining(due).coerceAtLeast(1))))
                }
            } catch (_: TimeoutCancellationException) {
                result.complete(Outcome.failure(OutcomeCode.TIMED_OUT, fail(OutcomeCode.TIMED_OUT)))
            } catch (_: Exception) {
                result.complete(Outcome.failure(OutcomeCode.CLOSED, fail(OutcomeCode.CLOSED)))
            } finally {
                if (acquired) submission.unlock()
                timer.cancel()
                synchronized(lock) {
                    commands--
                    pendingCommands.remove(onClose)
                    if (mutation) modes--
                }
                finished.complete(Unit)
                if (mutation) hint()
                if (synchronized(lock) { cleanupFinished && commands == 0 }) scope.cancel()
            }
        }
        return result
    }

    fun close(): Operation<CloseResult> {
        val cleanup: Triple<Deferred<FeatBitClient>?, List<Registration>, List<() -> Unit>>
        val budget: Long
        val waiter: CompletableDeferred<Unit>?
        synchronized(lock) {
            if (closing) return closeResult
            closing = true
            readable = false
            waiter = latest?.completion
            budget = options?.closeTimeoutMillis ?: 5_000
            cleanup =
                Triple(
                    creation,
                    listOfNotNull(statusRegistration, changeRegistration),
                    pendingCommands.toList(),
                )
            statusRegistration = null
            changeRegistration = null
            events.close()
        }
        readersChanged.trySend(Unit)
        val timer =
            scope.launch {
                delay(budget)
                closeResult.complete(
                    Outcome.failure(OutcomeCode.TIMED_OUT, Diagnostic("provider_cleanup_pending"))
                )
            }
        scope.launch {
            waiter?.completeExceptionally(closedError())
            cleanup.second.forEach { it.close() }
            cleanup.third.forEach { it() }
            try {
                val core =
                    try {
                        cleanup.first?.await()
                    } catch (_: Exception) {
                        synchronized(lock) { client }
                    }
                val result =
                    if (core == null) Outcome.success(CloseResult(0, true))
                    else bridge.await(core.close())
                core?.let { c ->
                    val snapshot = c.getConnectionInformation()
                    synchronized(lock) { lastConnection = snapshot }
                }
                closeResult.complete(result)
            } finally {
                timer.cancel()
                events.awaitClosed()
                // Pending submitted commands retain their SDK observers until their own completion.
                if (
                    synchronized(lock) {
                        cleanupFinished = true
                        commands == 0
                    }
                )
                    scope.cancel()
            }
        }
        return closeResult
    }
}
