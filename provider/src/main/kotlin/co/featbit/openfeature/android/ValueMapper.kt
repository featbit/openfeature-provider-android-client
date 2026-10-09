package co.featbit.openfeature.android

import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.ProviderEvaluation
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

internal object ValueMapper {
    fun json(value: FbValue): Value =
        when (value.kind) {
            FbValue.Kind.NULL -> Value.Null
            FbValue.Kind.BOOLEAN -> Value.Boolean(value.asBoolean()!!)
            FbValue.Kind.NUMBER -> Value.Double(value.asNumber()!!)
            FbValue.Kind.STRING -> Value.String(value.asString()!!)
            FbValue.Kind.ARRAY -> Value.List(value.asArray()!!.map(::json))
            FbValue.Kind.OBJECT -> Value.Structure(value.asObject()!!.mapValues { json(it.value) })
        }

    fun <T> detail(detail: EvaluationDetail<T>): T =
        when (detail.reason) {
            EvaluationReason.MATCH -> detail.value
            EvaluationReason.WRONG_TYPE ->
                throw OpenFeatureError.TypeMismatchError("provider_type_mismatch")
            EvaluationReason.FLAG_NOT_FOUND ->
                throw OpenFeatureError.FlagNotFoundError(null, "provider_flag_not_found")
            EvaluationReason.CLIENT_NOT_READY ->
                throw OpenFeatureError.ProviderNotReadyError("provider_not_ready")
            EvaluationReason.ERROR ->
                throw OpenFeatureError.GeneralError("provider_evaluation_failed")
        }

    fun <T> result(value: T) = ProviderEvaluation(value = value, reason = "UNKNOWN")
}
