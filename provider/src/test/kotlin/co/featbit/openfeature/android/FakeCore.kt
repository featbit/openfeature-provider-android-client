package co.featbit.openfeature.android

import android.content.Context
import android.content.ContextWrapper
import co.featbit.android.api.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.*

internal class TestContext : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
}

internal fun <T : Any> completed(value: T) =
    ProviderOperation<T>().also { it.complete(Outcome.success(value)) }

internal class FakeCore {
    var identity = "A"
    var offline = true
    var status = SyncStatus.READY
    var closeCalls = 0
    var registrations = 0
    var statusListener: StatusListener? = null
    var changeListener: ChangeListener? = null
    val identifies = mutableListOf<Pair<String, ProviderOperation<IdentityReceipt>>>()
    val readyOperations = mutableListOf<ProviderOperation<ReadyResult>>()
    val modes = mutableListOf<ProviderOperation<ModeResult>>()
    val tracks = mutableListOf<Pair<String, Double>>()
    var autoReady = true
    var autoIdentify = true
    var autoMode = true
    var modeFailure: OutcomeCode? = null
    var createCalls = 0
    var read: (() -> Unit)? = null
    var reason = EvaluationReason.MATCH
    var number = 12.9
    var json: FbValue = FbValue.jsonNull()
    var closeOperation = completed(CloseResult(0, true))
    var createOperation: ProviderOperation<FeatBitClient>? = null
    val client =
        Proxy.newProxyInstance(
            FeatBitClient::class.java.classLoader,
            arrayOf(FeatBitClient::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getConnectionInformation" -> snapshot()
                "isOffline" -> offline
                "getVersion" -> "fake"
                "awaitReady" ->
                    ProviderOperation<ReadyResult>().also {
                        readyOperations.add(it)
                        if (autoReady)
                            it.complete(
                                Outcome.success(
                                    if (offline) ReadyResult.OFFLINE_LOCAL
                                    else ReadyResult.REMOTE_CONFIRMED
                                )
                            )
                    }
                "identifyContext",
                "identifyAnonymousContext" ->
                    ProviderOperation<IdentityReceipt>().also {
                        val key =
                            if (method.name == "identifyAnonymousContext") "anonymous"
                            else (args[0] as User).key
                        identifies.add(key to it)
                        if (autoIdentify) {
                            identity = key
                            it.complete(receipt())
                        }
                    }
                "setOffline",
                "setOnline" ->
                    ProviderOperation<ModeResult>().also {
                        modes.add(it)
                        if (autoMode) {
                            if (modeFailure == null) {
                                offline = method.name == "setOffline"
                                it.complete(
                                    Outcome.success(
                                        if (offline) ModeResult.OFFLINE else ModeResult.ONLINE
                                    )
                                )
                            } else
                                it.complete(
                                    Outcome.failure(modeFailure!!, Diagnostic("fake_mode_failure"))
                                )
                        }
                    }
                "subscribeStatus" -> {
                    statusListener = args[0] as StatusListener
                    Outcome.success(registration())
                }
                "subscribeChanges" -> {
                    changeListener = args[0] as ChangeListener
                    Outcome.success(ChangeSubscription(registration(), emptyMap()))
                }
                "boolVariationDetail" -> {
                    read?.invoke()
                    EvaluationDetail(args[0] as String, true, reason)
                }
                "stringVariationDetail" -> {
                    read?.invoke()
                    EvaluationDetail(args[0] as String, identity, reason)
                }
                "numberVariationDetail" -> EvaluationDetail(args[0] as String, number, reason)
                "jsonVariationDetail" -> EvaluationDetail(args[0] as String, json, reason)
                "track" -> {
                    tracks.add(identity to (if (args.size == 1) 1.0 else args[1] as Double))
                    Outcome.success(TrackResult.ACCEPTED)
                }
                "flush" -> completed(FlushResult.EMPTY)
                "clearCache" -> completed(CacheClearResult.CLEARED)
                "close" -> {
                    closeCalls++
                    status = SyncStatus.CLOSED
                    closeOperation
                }
                "toString" -> "FakeCore"
                else -> error("Unexpected SDK method: ${method.name}")
            }
        } as FeatBitClient
    val factory =
        object : ClientFactory {
            override fun create(
                applicationContext: Context,
                options: ClientOptions,
            ): Operation<FeatBitClient> {
                createCalls++
                identity = options.initialUser?.key ?: "anonymous"
                return createOperation ?: completed(client)
            }
        }

    fun snapshot() =
        ConnectionInformation(
            SyncMode.STREAMING,
            SyncMode.STREAMING,
            status,
            if (offline) setOf(PauseReason.OFFLINE) else emptySet(),
            true,
            !offline,
            null,
            null,
            null,
            RecoveryStatus.NONE,
            null,
        )

    fun signal() {
        statusListener?.onStatus(snapshot())
    }

    private fun registration(): Registration {
        registrations++
        return object : Registration {
            var closed = false

            override fun close() {
                if (!closed) {
                    closed = true
                    registrations--
                }
            }
        }
    }

    fun receipt(): Outcome<IdentityReceipt> {
        // The receipt is only an opaque success signal to the provider. Its SDK constructor is
        // internal.
        val constructor =
            IdentityReceipt::class.java.declaredConstructors.first {
                it.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType))
            }
        constructor.isAccessible = true
        return Outcome.success(constructor.newInstance(1L) as IdentityReceipt)
    }
}
