@file:OptIn(dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi::class)

package co.featbit.consumer.kotlin

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import co.featbit.android.api.*
import co.featbit.android.testing.TestDataFactory
import co.featbit.openfeature.android.*
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.exceptions.ErrorCode
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlin.coroutines.resume
import kotlinx.coroutines.*

/** Executable acceptance fixture. It never contacts a FeatBit server or sends events. */
class SmokeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var provider: FeatBitProvider? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val label = TextView(this)
        label.text = "Running provider acceptance"
        setContentView(label)
        scope.launch {
            try {
                val flags =
                    listOf(
                        BootstrapFlag.create("enabled", "true", ValueType.BOOLEAN).value!!,
                        BootstrapFlag.create("amount", "12.9", ValueType.NUMBER).value!!,
                        BootstrapFlag.create("message", "hello", ValueType.STRING).value!!,
                        BootstrapFlag.create("object", "{\"ok\":true}", ValueType.JSON).value!!,
                    )
                val data = TestDataFactory.getDefault().create(flags).value!!
                val owned =
                    FeatBitProvider.builder(applicationContext) { user ->
                            ClientOptions.builder()
                                .user(user)
                                .source(data)
                                .anonymousEnabled(true)
                                .disableEvents(true)
                                .cacheEnabled(false)
                                .build()
                        }
                        .identityPolicy(IdentityPolicy.PERSISTENT_ANONYMOUS)
                        .build()
                        .value!!
                provider = owned
                val api = createOpenFeatureAPIInstance()
                api.setProviderAndWait(owned, ImmutableContext("A"))
                val client = api.getClient()
                check(client.getBooleanValue("enabled", false))
                check(client.getIntegerValue("amount", 0) == 12)
                check(client.getLongValue("amount", 0L) == 12L)
                check(client.getDoubleValue("amount", 0.0) == 12.9)
                check(client.getStringValue("message", "fallback") == "hello")
                check(
                    client.getObjectValue("object", Value.Null) ==
                        Value.Structure(mapOf("ok" to Value.Boolean(true)))
                )
                check(
                    client.getBooleanDetails("message", false).errorCode == ErrorCode.TYPE_MISMATCH
                )
                check(client.getLongDetails("missing", Long.MAX_VALUE).value == Long.MAX_VALUE)
                api.setEvaluationContextAndWait(ImmutableContext("B"))
                check(client.getBooleanValue("enabled", false))
                api.setEvaluationContextAndWait(ImmutableContext())
                check(client.getBooleanValue("enabled", false))
                client.track("test-metric", TrackingEventDetails(1))
                api.shutdown()
                val closed =
                    suspendCancellableCoroutine<Outcome<CloseResult>> { continuation ->
                        val registration = owned.close().observe { continuation.resume(it) }.value
                        continuation.invokeOnCancellation { registration?.close() }
                    }
                check(closed.isSuccess && closed.value!!.cleanupComplete)
                label.text = "PROVIDER_SMOKE_PASS kotlin ${intent.getStringExtra("runId")}"
                Log.i("ProviderAcceptance", label.text.toString())
            } catch (failure: Exception) {
                label.text =
                    "PROVIDER_SMOKE_FAIL kotlin ${intent.getStringExtra("runId")}: ${failure.javaClass.simpleName}"
                Log.e("ProviderAcceptance", label.text.toString(), failure)
                provider?.close()
            }
        }
    }

    override fun onDestroy() {
        provider?.close()
        scope.cancel()
        super.onDestroy()
    }
}
