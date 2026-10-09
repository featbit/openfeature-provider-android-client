@file:OptIn(dev.openfeature.kotlin.sdk.isolated.ExperimentalIsolatedApi::class)

package co.featbit.consumer.kotlin

import android.content.Context
import android.content.ContextWrapper
import co.featbit.android.api.Outcome
import co.featbit.android.api.User
import co.featbit.openfeature.android.FeatBitProvider
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.isolated.createOpenFeatureAPIInstance
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PublishedContractTest {
    private val context =
        object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }

    @Test
    fun invalidBudgetIsRejectedWithoutInvokingFactory() {
        val result =
            FeatBitProvider.builder(context) { error("Factory must not run") }
                .contextChangeTimeoutMillis(0)
                .build()
        assertFalse(result.isSuccess)
        assertNull(result.value)
    }

    @Test
    fun missingIdentityPreservesExactDefaultsAndReportsErrors() = runBlocking {
        var calls = 0
        val provider =
            FeatBitProvider.builder(context) {
                    calls++
                    Outcome.invalid("unexpected_factory")
                }
                .build()
                .value!!
        val api = createOpenFeatureAPIInstance()
        try {
            api.setProviderAndWait(provider, ImmutableContext())
            val client = api.getClient()
            val result = client.getLongDetails("missing", Long.MAX_VALUE)
            assertEquals(Long.MAX_VALUE, result.value)
            assertNotNull(result.errorCode)
            val fallback = Value.Structure(mapOf("id" to Value.Long(Long.MAX_VALUE)))
            assertEquals(fallback, client.getObjectValue("missing", fallback))
            assertEquals(0, calls)
        } finally {
            api.shutdown()
            provider.close()
        }
    }

    @Test
    fun initialContextReachesOptionsFactoryAsMappedUser() = runBlocking {
        var received: User? = null
        val provider =
            FeatBitProvider.builder(context) {
                    received = it
                    Outcome.invalid("intentional_configuration_failure")
                }
                .build()
                .value!!
        val api = createOpenFeatureAPIInstance()
        try {
            api.setProviderAndWait(
                provider,
                ImmutableContext(
                    "user-B",
                    mapOf("name" to Value.String("Bob"), "plan" to Value.String("pro")),
                ),
            )
            assertEquals("user-B", received?.key)
            assertEquals("Bob", received?.name)
            assertEquals("pro", received?.attributes?.get("plan")?.text)
            assertNotNull(api.getClient().getBooleanDetails("flag", false).errorCode)
        } finally {
            api.shutdown()
            provider.close()
        }
    }
}
