package co.featbit.sample.kotlin

import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.ImmutableContext
import dev.openfeature.kotlin.sdk.Value
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.json.*

data class FlagSpec(
    val key: String,
    val type: ValueType,
    val initial: String,
    val fallback: String,
)

data class Person(val key: String, val name: String, val plan: String) {
    fun evaluationContext() =
        ImmutableContext(key, mapOf("name" to Value.String(name), "plan" to Value.String(plan)))

    fun user(): User =
        User.builder(key)
            .name(name)
            .attribute("plan", AttributeValue.text(plan).value!!)
            .build()
            .value!!
}

data class CupSize(val id: String, val label: String)

data class Menu(val sizes: List<CupSize>, val defaultSize: String)

data class Business(
    val compact: Boolean = false,
    val promo: String = "Fresh coffee, made for you.",
    val discount: Double = 0.0,
    val menu: Menu = DEFAULT_MENU,
    val menuInvalid: Boolean = false,
    val discountInvalid: Boolean = false,
    val usingFallback: Boolean = true,
) {
    val total: BigDecimal
        get() = price(discount)

    companion object {
        val DEFAULT_MENU =
            Menu(
                listOf(
                    CupSize("small", "Small"),
                    CupSize("regular", "Regular"),
                    CupSize("large", "Large"),
                ),
                "regular",
            )

        fun price(discount: Double): BigDecimal {
            val valid = if (discount.isFinite() && discount in 0.0..100.0) discount else 0.0
            return BigDecimal("5.00")
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(valid).movePointLeft(2)))
                .setScale(2, RoundingMode.HALF_UP)
        }

        fun menu(raw: String): Menu? =
            runCatching {
                    val obj = Json.parseToJsonElement(raw) as? JsonObject ?: return null
                    val array = obj["sizes"] as? JsonArray ?: return null
                    val sizes =
                        array.map { element ->
                            val size = element as? JsonObject ?: return null
                            val id = size["id"] as? JsonPrimitive ?: return null
                            val label = size["label"] as? JsonPrimitive ?: return null
                            if (
                                !id.isString ||
                                    !label.isString ||
                                    id.content.isBlank() ||
                                    label.content.isBlank()
                            )
                                return null
                            CupSize(id.content, label.content)
                        }
                    val default = obj["defaultSize"] as? JsonPrimitive ?: return null
                    if (
                        !default.isString ||
                            sizes.isEmpty() ||
                            sizes.map { it.id }.distinct().size != sizes.size ||
                            sizes.none { it.id == default.content }
                    )
                        return null
                    Menu(sizes, default.content)
                }
                .getOrNull()
    }
}

data class ConnectionDraft(
    var local: Boolean = true,
    var key: String = "",
    var mode: SyncMode = SyncMode.STREAMING,
    var pollingFallback: Boolean = false,
    var streaming: String = "",
    var polling: String = "",
    var events: Boolean = true,
    var eventsUrl: String = "",
)

data class ReadRecord(
    val value: String,
    val reason: String,
    val fallback: String,
    val user: String,
    val time: String,
    val stale: Boolean = false,
)

data class ActivityEntry(val time: String, val message: String)

data class OrderRecord(
    val size: String,
    val amount: BigDecimal,
    val user: String,
    val result: String,
)

data class ScreenState(
    val revision: Long = 0,
    val local: Boolean = true,
    val user: Int = 0,
    val active: Boolean = false,
    val busy: String? = null,
    val flushPending: Boolean = false,
    val status: ConnectionInformation? = null,
    val snapshot: Map<String, SampleValue> = emptyMap(),
    val business: Business = Business(),
    val selectedSize: String = "regular",
    val events: Boolean = false,
    val message: String? = null,
    val waitTimedOut: Boolean = false,
    val lastTrack: String? = null,
    val lastFlush: String? = null,
    val history: List<ActivityEntry> = emptyList(),
    val reads: Map<String, ReadRecord> = emptyMap(),
    val order: OrderRecord? = null,
) {
    val available: Boolean
        get() = active && busy == null
}

data class SampleValue(val value: String, val error: String? = null)
