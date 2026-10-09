package co.featbit.sample.kotlin

import android.content.Context
import co.featbit.android.api.*
import co.featbit.android.kotlin.ClientAdapters
import co.featbit.android.testing.*
import co.featbit.openfeature.android.FeatBitProvider
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Application-owned SDK integration. All public calls and state mutations run on main. */
class SampleSession(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapters = ClientAdapters.getDefault()
    private val mutable = MutableStateFlow(ScreenState())
    val state: StateFlow<ScreenState> = mutable.asStateFlow()
    private var client: FeatBitProvider? = null
    private val api = OpenFeatureAPI
    private val flags = api.getClient()
    private var data: TestData? = null
    private var generation = 0L
    private var identityAdopting = false
    private var changes: Job? = null
    private var statusJob: Job? = null
    val specs: List<FlagSpec> =
        Json.parseToJsonElement(
                context.assets.open("demo-flags.json").bufferedReader().use { it.readText() }
            )
            .jsonArray
            .map {
                val o = it.jsonObject
                FlagSpec(
                    o.getValue("key").jsonPrimitive.content,
                    ValueType.valueOf(o.getValue("type").jsonPrimitive.content),
                    o.getValue("initial").jsonPrimitive.content,
                    o.getValue("fallback").jsonPrimitive.content,
                )
            }
    val people: List<Person> =
        Json.parseToJsonElement(
                context.assets.open("users.json").bufferedReader().use { it.readText() }
            )
            .jsonArray
            .map {
                val o = it.jsonObject
                Person(
                    o.getValue("key").jsonPrimitive.content,
                    o.getValue("name").jsonPrimitive.content,
                    o.getValue("plan").jsonPrimitive.content,
                )
            }
    private val localValues = specs.associate { it.key to it.initial }.toMutableMap()
    val draft = ConnectionDraft()
    // In-process UI continuity. Credentials/editor content never enter saved-instance-state
    // bundles.
    var destination = "Demo"
    var detailKey: String? = null
    var formOpen = false
    var editorKey: String? = null
    var editorText = ""
    var userSheet = false
    var userChoice = 0
    var filter = ""
    var scrollY = 0

    private var started = false

    // Start after the first rendered frame, keeping renderer warm-up outside SDK deadlines.
    fun start() {
        if (!started) {
            started = true
            connect()
        }
    }

    private fun now() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun update(change: (ScreenState) -> ScreenState) {
        mutable.value = change(mutable.value).copy(revision = mutable.value.revision + 1)
    }

    private fun log(message: String) = update {
        it.copy(history = (it.history + ActivityEntry(now(), message)).takeLast(100))
    }

    fun dismissMessage() = update { it.copy(message = null) }

    private fun stale() = update { s ->
        s.copy(reads = s.reads.mapValues { it.value.copy(stale = true) })
    }

    fun invalidateReads() {
        if (state.value.reads.isNotEmpty()) stale()
    }

    fun providerStatus(): String =
        when (api.getStatus()) {
            is OpenFeatureStatus.Ready -> "Ready"
            is OpenFeatureStatus.NotReady -> "Not ready"
            is OpenFeatureStatus.Reconciling -> "Reconciling"
            is OpenFeatureStatus.Stale -> "Stale"
            is OpenFeatureStatus.Error -> "Error"
            is OpenFeatureStatus.Fatal -> "Fatal"
        }

    fun sdkVersion(): String = "OpenFeature 0.9.0 / FeatBit ${SdkInfo.getVersion()}"

    fun localValue(key: String): String = localValues[key] ?: specs.first { it.key == key }.initial

    private fun records(values: Map<String, String>) =
        specs
            .filter { it.key in values }
            .map { BootstrapFlag.create(it.key, values.getValue(it.key), it.type).value!! }

    fun validateValue(spec: FlagSpec, value: String): Boolean =
        when (spec.type) {
            ValueType.NUMBER -> value.toDoubleOrNull()?.isFinite() == true
            ValueType.BOOLEAN -> value == "true" || value == "false"
            ValueType.JSON -> runCatching { Json.parseToJsonElement(value) }.isSuccess
            ValueType.STRING -> true
        }

    fun validateDraft(): Map<String, String> {
        if (draft.local) return emptyMap()
        val errors = mutableMapOf<String, String>()
        if (draft.key.isBlank()) errors["sdkKey"] = "Client SDK Key is required"
        val endpoint = if (draft.mode == SyncMode.STREAMING) draft.streaming else draft.polling
        if (endpoint.isBlank())
            errors[if (draft.mode == SyncMode.STREAMING) "streamingUrl" else "pollingUrl"] =
                "Synchronization URL is required"
        if (draft.events && draft.eventsUrl.isBlank())
            errors["eventsUrl"] = "Events URL is required"
        if (draft.mode == SyncMode.STREAMING && draft.pollingFallback && draft.polling.isBlank()) {
            errors["pollingUrl"] = "Polling URL is required when fallback is enabled"
        }
        if (errors.isEmpty()) {
            val outcome = liveOptions().build()
            if (!outcome.isSuccess)
                errors[outcome.diagnostic?.field ?: "form"] =
                    "Invalid configuration: ${outcome.diagnostic?.code ?: outcome.code}"
        }
        return errors
    }

    private fun liveOptions(user: User = people[state.value.user].user()): ClientOptions.Builder =
        ClientOptions.builder()
            .user(user)
            .sdkKey(draft.key)
            .mode(draft.mode)
            .disableEvents(!draft.events)
            .apply {
                if (draft.mode == SyncMode.STREAMING) {
                    streamingUrl(draft.streaming)
                    pollingFallback(draft.pollingFallback)
                    if (draft.pollingFallback) pollingUrl(draft.polling)
                } else {
                    pollingUrl(draft.polling)
                    pollingFallback(false)
                }
                if (draft.events) eventsUrl(draft.eventsUrl)
            }

    /**
     * Adapters detach their wait on timeout; keep observing the original operation until settled.
     */
    private suspend fun <T> settled(operation: Operation<T>, budget: Long): Outcome<T> {
        while (true) {
            operation.getResult()?.let {
                return it
            }
            val result = adapters.await(operation, budget)
            operation.getResult()?.let {
                return it
            }
            if (result.code != OutcomeCode.TIMED_OUT)
                delay(100) // Registration failure is not the underlying operation result.
        }
    }

    fun connect() {
        if (state.value.busy != null || state.value.flushPending) return
        val errors = validateDraft()
        if (errors.isNotEmpty()) {
            update { it.copy(message = errors.values.first()) }
            return
        }
        val nextLocal = draft.local
        val nextEvents = !nextLocal && draft.events
        // Validate all proposed options before retiring the existing client.
        val nextData =
            if (nextLocal) TestDataFactory.getDefault().create(records(localValues)).value else null
        val options =
            if (nextLocal) nextData?.clientOptions(people[state.value.user].user())
            else liveOptions().build()
        if (options == null || !options.isSuccess) {
            update { it.copy(message = "Invalid configuration") }
            return
        }
        val nextLiveOptions = if (nextLocal) null else liveOptions()
        val revision = ++generation
        identityAdopting = false
        statusJob?.cancel()
        changes?.cancel()
        changes = null
        stale()
        update {
            it.copy(busy = "Reconnecting…", message = null, waitTimedOut = false, active = false)
        }
        scope.launch {
            val old = client
            client = null
            if (old != null) {
                val closed = settled(old.close(), 6000)
                if (
                    !closed.isSuccess ||
                        closed.value?.cleanupComplete == false ||
                        (closed.value?.undeliveredEvents ?: 0) > 0
                ) {
                    val warning =
                        "Close: ${closed.code}; undelivered=${closed.value?.undeliveredEvents ?: "unknown"}; cleanup=${closed.value?.cleanupComplete ?: "unknown"}"
                    log(warning)
                    update { it.copy(message = warning) }
                }
            }
            val c =
                FeatBitProvider.builder(context) { user ->
                        if (nextLocal) nextData!!.clientOptions(requireNotNull(user))
                        else nextLiveOptions!!.user(requireNotNull(user)).build()
                    }
                    .initializationTimeoutMillis(5000)
                    .contextChangeTimeoutMillis(5000)
                    .build()
                    .value!!
            client = c
            data = nextData
            try {
                api.setProviderAndWait(c, people[state.value.user].evaluationContext())
            } catch (failure: Exception) {
                update {
                    it.copy(
                        busy = null,
                        active = false,
                        message = "Provider registration failed: ${failure.javaClass.simpleName}",
                    )
                }
                c.close()
                return@launch
            }
            if (revision != generation) {
                c.close()
                return@launch
            }
            update {
                it.copy(
                    local = nextLocal,
                    events = nextEvents,
                    busy = null,
                    active = true,
                    status = c.getConnectionInformation(),
                    lastTrack = null,
                    lastFlush = null,
                )
            }
            changes =
                scope.launch {
                    flags.observe().collect { event ->
                        if (revision == generation) {
                            refresh(c)
                            stale()
                            log(
                                "OpenFeature: " +
                                    when (event) {
                                        is ProviderReady -> "Ready"
                                        is ProviderStale -> "Stale"
                                        is ProviderError -> "Error"
                                        is ProviderConfigurationChanged -> "Flags changed"
                                    }
                            )
                        }
                    }
                }
            // Connection diagnostics are provider extensions, separate from OpenFeature status.
            statusJob =
                scope.launch {
                    while (revision == generation) {
                        val status = c.getConnectionInformation()
                        if (status != state.value.status) update { it.copy(status = status) }
                        delay(1000)
                    }
                }
            refresh(c)
            log("OpenFeature status: ${providerStatus()}")
        }
    }

    private fun read(spec: FlagSpec): FlagEvaluationDetails<*> =
        when (spec.type) {
            ValueType.BOOLEAN -> flags.getBooleanDetails(spec.key, spec.fallback.toBoolean())
            ValueType.STRING -> flags.getStringDetails(spec.key, spec.fallback)
            ValueType.NUMBER -> flags.getDoubleDetails(spec.key, spec.fallback.toDouble())
            ValueType.JSON -> flags.getObjectDetails(spec.key, OpenFeatureJson.parse(spec.fallback))
        }

    private fun display(result: FlagEvaluationDetails<*>): String =
        (result.value as? Value)?.let { OpenFeatureJson.text(it) } ?: result.value.toString()

    private fun refresh(c: FeatBitProvider) {
        if (identityAdopting) return
        val details = specs.map(::read)
        val compact = details[0].value as Boolean
        val promo = details[1].value as String
        val discount = details[2].value as Double
        val parsedMenu = Business.menu(display(details[3]))
        val badDiscount = !discount.isFinite() || discount !in 0.0..100.0
        val business =
            Business(
                compact,
                promo,
                if (badDiscount) 0.0 else discount,
                parsedMenu ?: Business.DEFAULT_MENU,
                parsedMenu == null,
                badDiscount,
                details.any { it.errorCode != null },
            )
        update {
            it.copy(
                snapshot =
                    specs.zip(details).associate { (spec, result) ->
                        spec.key to SampleValue(display(result), result.errorCode?.name)
                    },
                business = business,
                selectedSize =
                    if (business.menu.sizes.any { size -> size.id == it.selectedSize })
                        it.selectedSize
                    else business.menu.defaultSize,
                status = c.getConnectionInformation(),
            )
        }
    }

    fun selectSize(id: String) {
        if (state.value.business.menu.sizes.any { it.id == id })
            update { it.copy(selectedSize = id) }
    }

    fun identify(index: Int) {
        val c = client ?: return
        if (!state.value.available || state.value.flushPending || index == state.value.user) return
        val revision = generation
        identityAdopting = true
        stale()
        update { it.copy(busy = "Switching to ${people[index].name}…", waitTimedOut = false) }
        scope.launch {
            try {
                api.setEvaluationContextAndWait(people[index].evaluationContext())
                if (revision != generation) return@launch
                // Display the requested OpenFeature context, even when reconciliation is not ready.
                update {
                    it.copy(user = index, message = "OpenFeature context: ${providerStatus()}")
                }
                userSheet = false
            } catch (failure: Exception) {
                update {
                    it.copy(message = "Context change failed: ${failure.javaClass.simpleName}")
                }
            } finally {
                if (revision == generation) {
                    identityAdopting = false
                    update { it.copy(busy = null) }
                    refresh(c)
                    log("OpenFeature context: ${providerStatus()}")
                }
            }
        }
    }

    fun offline(offline: Boolean) {
        val c = client ?: return
        if (!state.value.available || state.value.flushPending) return
        val revision = generation
        update { it.copy(busy = if (offline) "Going offline…" else "Going online…") }
        scope.launch {
            val result = settled(if (offline) c.setOffline(5000) else c.setOnline(5000), 6000)
            if (generation == revision) {
                refresh(c)
                update { it.copy(busy = null, message = "Mode: ${result.value ?: result.code}") }
                log("Mode: ${result.value ?: result.code}")
            }
        }
    }

    fun edit(
        key: String?,
        value: String? = null,
        restoreAll: Boolean = false,
        completed: (Boolean) -> Unit = {},
    ) {
        val d = data ?: return
        if (!state.value.local || !state.value.available) return
        val spec = specs.firstOrNull { it.key == key }
        if (spec != null && value != null && !validateValue(spec, value)) {
            completed(false)
            return
        }
        val proposed = localValues.toMutableMap()
        val operation =
            when {
                restoreAll -> {
                    proposed.clear()
                    specs.forEach { proposed[it.key] = it.initial }
                    d.replace(records(proposed))
                }
                spec != null && value != null -> {
                    proposed[spec.key] = value
                    d.update(BootstrapFlag.create(spec.key, value, spec.type).value!!)
                }
                spec != null -> {
                    proposed.remove(spec.key)
                    d.remove(spec.key)
                }
                else -> return
            }
        update { it.copy(busy = "Saving local flags…") }
        val revision = generation
        scope.launch {
            val result = settled(operation, 6000)
            if (revision == generation) {
                if (result.isSuccess) {
                    localValues.clear()
                    localValues.putAll(proposed)
                    if (editorKey == key) editorKey = null
                }
                update {
                    it.copy(
                        busy = null,
                        message =
                            when {
                                result.value == TestDataResult.COMMITTED -> "Applied locally"
                                result.value == TestDataResult.SAVED_FOR_NEXT_START ->
                                    "Saved; applies when resumed"
                                else -> "Save failed: ${result.diagnostic?.code ?: result.code}"
                            },
                    )
                }
                if (result.value == TestDataResult.COMMITTED) client?.let { refresh(it) }
                log("TestData: ${result.value ?: result.code}")
                completed(result.isSuccess)
            }
        }
    }

    fun evaluate(key: String) {
        val c = client ?: return
        if (!state.value.available) return
        val spec = specs.first { it.key == key }
        val result = read(spec)
        update {
            it.copy(
                reads =
                    it.reads +
                        (key to
                            ReadRecord(
                                display(result),
                                result.errorCode?.name ?: result.reason ?: "UNKNOWN",
                                spec.fallback,
                                people[it.user].name,
                                now(),
                            ))
            )
        }
        log("Evaluated $key: ${result.reason}")
    }

    fun order() {
        val c = client ?: return
        val s = state.value
        if (!s.available) return
        val size = s.business.menu.sizes.first { it.id == s.selectedSize }.label
        val result =
            if (s.local) "Events disabled in Local"
            else if (!s.events) "Events disabled"
            else {
                flags.track(
                    "sample-order-completed",
                    TrackingEventDetails(s.business.total.toDouble()),
                )
                "Track invoked · delivery not acknowledged"
            }
        val record = OrderRecord(size, s.business.total, people[s.user].name, result)
        update {
            it.copy(order = record, lastTrack = result, message = "Demo order created · $result")
        }
        log("Order ${record.amount}: $result")
    }

    fun flush() {
        val c = client ?: return
        if (!state.value.available || !state.value.events || state.value.flushPending) return
        val revision = generation
        update { it.copy(flushPending = true) }
        scope.launch {
            val outcome = settled(c.flush(), 11000)
            if (revision == generation) {
                val result = (outcome.value ?: outcome.code).toString()
                update {
                    it.copy(flushPending = false, lastFlush = result, message = "Flush: $result")
                }
                log("Flush: $result")
            }
        }
    }
}
