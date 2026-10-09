package co.featbit.sample.kotlin

import dev.openfeature.kotlin.sdk.Value
import kotlinx.serialization.json.*

/** JSON stays a typed OpenFeature Value throughout evaluation. */
internal object OpenFeatureJson {
    fun parse(text: String): Value = value(Json.parseToJsonElement(text))

    private fun value(json: JsonElement): Value =
        when (json) {
            JsonNull -> Value.Null
            is JsonObject -> Value.Structure(json.mapValues { value(it.value) })
            is JsonArray -> Value.List(json.map(::value))
            is JsonPrimitive ->
                when {
                    json.isString -> Value.String(json.content)
                    json.booleanOrNull != null -> Value.Boolean(json.boolean)
                    else -> Value.Double(json.double)
                }
        }

    fun text(value: Value): String = json(value).toString()

    private fun json(value: Value): JsonElement =
        when {
            value.isNull() -> JsonNull
            value.asStructure() != null ->
                JsonObject(value.asStructure()!!.mapValues { json(it.value) })
            value.asList() != null -> JsonArray(value.asList()!!.map(::json))
            value.asBoolean() != null -> JsonPrimitive(value.asBoolean())
            value.asString() != null -> JsonPrimitive(value.asString())
            value.asDouble() != null -> JsonPrimitive(value.asDouble())
            value.asInteger() != null -> JsonPrimitive(value.asInteger())
            value.asLong() != null -> JsonPrimitive(value.asLong())
            else -> error("Unsupported JSON value")
        }
}
