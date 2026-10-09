package co.featbit.openfeature.android

import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.EvaluationContext
import dev.openfeature.kotlin.sdk.Value
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError

/** Equality uses canonical text/null values; core User intentionally has identity equality. */
internal data class Identity(
    val key: String?,
    val name: String?,
    val attributes: Map<String, String?>,
) {
    fun user(): User? {
        if (key == null) return null
        val builder = User.builder(key).name(name)
        attributes.forEach { (key, value) ->
            builder.attribute(
                key,
                if (value == null) AttributeValue.nullValue()
                else AttributeValue.text(value).value!!,
            )
        }
        return builder.build().value
            ?: throw OpenFeatureError.InvalidContextError("provider_invalid_attributes")
    }

    fun matches(user: User?): Boolean =
        if (key == null) user == null
        else
            user != null &&
                key == user.key &&
                name == user.name &&
                attributes ==
                    user.attributes.mapValues {
                        if (it.value.kind == AttributeValue.Kind.NULL) null else it.value.text
                    } &&
                user.attributes.values.none { it.kind == AttributeValue.Kind.OMITTED }
}

internal object ContextMapper {
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun map(
        context: EvaluationContext?,
        policy: IdentityPolicy,
        automaticAttributes: Boolean = false,
    ): Identity {
        val key = context?.getTargetingKey().orEmpty()
        val attrs = context?.asMap().orEmpty()
        if (key.isEmpty() && policy == IdentityPolicy.PERSISTENT_ANONYMOUS) {
            if (attrs.isNotEmpty())
                throw OpenFeatureError.InvalidContextError("provider_anonymous_attributes")
            return Identity(null, null, emptyMap())
        }
        if (key.isBlank())
            throw OpenFeatureError.TargetingKeyMissingError("provider_targeting_key_missing")
        var name = key
        val values = linkedMapOf<String, String?>()
        attrs.forEach { (attribute, value) ->
            if (
                attribute.isEmpty() ||
                    attribute == "keyId" ||
                    (automaticAttributes && attribute.startsWith("featbit.sdk."))
            )
                throw OpenFeatureError.InvalidContextError("provider_reserved_attribute")
            if (attribute == "name") {
                name =
                    (value as? Value.String)?.string?.takeUnless { it.isBlank() }
                        ?: throw OpenFeatureError.InvalidContextError("provider_invalid_name")
            } else
                values[attribute] =
                    when (value) {
                        is Value.String -> value.string
                        is Value.Boolean -> value.boolean.toString()
                        is Value.Integer -> value.integer.toString()
                        is Value.Long -> value.long.toString()
                        is Value.Double ->
                            if (value.double.isFinite()) value.double.toString()
                            else
                                throw OpenFeatureError.InvalidContextError(
                                    "provider_nonfinite_attribute"
                                )
                        Value.Null -> null
                        else ->
                            throw OpenFeatureError.InvalidContextError(
                                "provider_unsupported_attribute"
                            )
                    }
        }
        return Identity(key, name, values.toMap()).also { it.user() }
    }
}
