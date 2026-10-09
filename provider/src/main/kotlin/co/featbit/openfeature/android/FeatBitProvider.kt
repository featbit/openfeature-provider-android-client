package co.featbit.openfeature.android

import android.content.Context
import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents
import kotlinx.coroutines.flow.Flow

/** Owns one FeatBit client. Change identity through OpenFeature's evaluation context. */
public class FeatBitProvider internal constructor(private val runtime: ProviderRuntime) :
    FeatureProvider {
    override val metadata: ProviderMetadata =
        object : ProviderMetadata {
            override val name: String = "FeatBit"
        }
    override val hooks: List<Hook<*>> = emptyList()

    override suspend fun initialize(initialContext: EvaluationContext?) {
        runtime.context(initialContext, true)
    }

    override suspend fun onContextSet(
        oldContext: EvaluationContext?,
        newContext: EvaluationContext,
    ) {
        runtime.context(newContext, false)
    }

    override fun getBooleanEvaluation(
        key: String,
        defaultValue: Boolean,
        context: EvaluationContext?,
    ): ProviderEvaluation<Boolean> =
        runtime.evaluate(context) { ValueMapper.detail(it.boolVariationDetail(key, false)) }

    override fun getStringEvaluation(
        key: String,
        defaultValue: String,
        context: EvaluationContext?,
    ): ProviderEvaluation<String> =
        runtime.evaluate(context) { ValueMapper.detail(it.stringVariationDetail(key, "")) }

    override fun getIntegerEvaluation(
        key: String,
        defaultValue: Int,
        context: EvaluationContext?,
    ): ProviderEvaluation<Int> =
        runtime.evaluate(context) { ValueMapper.detail(it.numberVariationDetail(key, 0.0)).toInt() }

    override fun getLongEvaluation(
        key: String,
        defaultValue: Long,
        context: EvaluationContext?,
    ): ProviderEvaluation<Long> =
        runtime.evaluate(context) {
            ValueMapper.detail(it.numberVariationDetail(key, 0.0)).toLong()
        }

    override fun getDoubleEvaluation(
        key: String,
        defaultValue: Double,
        context: EvaluationContext?,
    ): ProviderEvaluation<Double> =
        runtime.evaluate(context) { ValueMapper.detail(it.numberVariationDetail(key, 0.0)) }

    override fun getObjectEvaluation(
        key: String,
        defaultValue: Value,
        context: EvaluationContext?,
    ): ProviderEvaluation<Value> =
        runtime.evaluate(context) {
            ValueMapper.json(ValueMapper.detail(it.jsonVariationDetail(key, FbValue.jsonNull())))
        }

    override fun track(
        trackingEventName: String,
        context: EvaluationContext?,
        details: TrackingEventDetails?,
    ) {
        runtime.track(trackingEventName, context, details)
    }

    override fun observe(): Flow<OpenFeatureProviderEvents> = runtime.events.observe()

    override fun shutdown() {
        runtime.close()
    }

    /** A diagnostic snapshot, not a guarantee that the current OpenFeature identity is ready. */
    public fun getConnectionInformation(): ConnectionInformation? = runtime.connection()

    /**
     * The budget includes provider queueing. Failure does not imply that the mode was unchanged.
     */
    public fun setOffline(timeoutMillis: Long): Operation<ModeResult> =
        runtime.mode(true, timeoutMillis)

    /** Rechecks readiness using the SDK's actual mode, including after a failed operation. */
    public fun setOnline(timeoutMillis: Long): Operation<ModeResult> =
        runtime.mode(false, timeoutMillis)

    public fun flush(): Operation<FlushResult> = runtime.flush()

    /** A timeout after submission does not undo a cache clear. */
    public fun clearCache(scope: CacheScope, timeoutMillis: Long): Operation<CacheClearResult> =
        runtime.clearCache(scope, timeoutMillis)

    /** Closes admission immediately; repeated calls observe the same retained cleanup result. */
    public fun close(): Operation<CloseResult> = runtime.close()

    public class Builder
    internal constructor(context: Context, private val factory: FeatBitOptionsFactory) {
        private val applicationContext = context.applicationContext
        private var policy = IdentityPolicy.REQUIRE_TARGETING_KEY
        private var initializationTimeout = 10_000L
        private var contextTimeout = 10_000L

        public fun identityPolicy(value: IdentityPolicy): Builder = apply { policy = value }

        public fun initializationTimeoutMillis(value: Long): Builder = apply {
            initializationTimeout = value
        }

        public fun contextChangeTimeoutMillis(value: Long): Builder = apply {
            contextTimeout = value
        }

        public fun build(): Outcome<FeatBitProvider> {
            if (initializationTimeout <= 0 || contextTimeout <= 0)
                return Outcome.invalid("provider_invalid_timeout")
            if (applicationContext == null)
                return Outcome.invalid("provider_application_context_missing")
            return Outcome.success(
                FeatBitProvider(
                    ProviderRuntime(
                        applicationContext,
                        factory,
                        policy,
                        initializationTimeout,
                        contextTimeout,
                    )
                )
            )
        }
    }

    public companion object {
        @JvmStatic
        public fun builder(
            applicationContext: Context,
            optionsFactory: FeatBitOptionsFactory,
        ): Builder = Builder(applicationContext, optionsFactory)
    }
}
