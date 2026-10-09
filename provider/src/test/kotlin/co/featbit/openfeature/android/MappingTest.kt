package co.featbit.openfeature.android

import co.featbit.android.api.*
import dev.openfeature.kotlin.sdk.*
import dev.openfeature.kotlin.sdk.exceptions.OpenFeatureError
import org.junit.Assert.*
import org.junit.Test

class MappingTest {
    private fun map(context: EvaluationContext?) =
        ContextMapper.map(context, IdentityPolicy.REQUIRE_TARGETING_KEY)

    @Test
    fun preservesExactStringsAndIntegerPrecision() {
        val result =
            map(
                ImmutableContext(
                    " A ",
                    mapOf(
                        "name" to Value.String(" Alice "),
                        "long" to Value.Long(Long.MAX_VALUE),
                        "null" to Value.Null,
                        "boolean" to Value.Boolean(true),
                    ),
                )
            )
        assertEquals(" A ", result.key)
        assertEquals(" Alice ", result.name)
        assertEquals(Long.MAX_VALUE.toString(), result.attributes["long"])
        assertTrue(result.attributes.containsKey("null"))
        assertNull(result.attributes["null"])
        assertTrue(result.matches(result.user()))
    }

    @Test
    fun anonymousRequiresEmptyAttributesAndExplicitPolicy() {
        assertNull(ContextMapper.map(null, IdentityPolicy.PERSISTENT_ANONYMOUS).user())
        assertThrows(OpenFeatureError.TargetingKeyMissingError::class.java) { map(null) }
        assertThrows(OpenFeatureError.TargetingKeyMissingError::class.java) {
            ContextMapper.map(ImmutableContext(" "), IdentityPolicy.PERSISTENT_ANONYMOUS)
        }
        assertThrows(OpenFeatureError.InvalidContextError::class.java) {
            ContextMapper.map(
                ImmutableContext(attributes = mapOf("x" to Value.Null)),
                IdentityPolicy.PERSISTENT_ANONYMOUS,
            )
        }
    }

    @Test
    fun rejectsUnsupportedAndReservedAttributes() {
        for ((key, value) in
            listOf(
                "name" to Value.Null,
                "keyId" to Value.String("B"),
                "" to Value.Null,
                "x" to Value.Double(Double.NaN),
                "x" to Value.List(emptyList()),
                "x" to Value.Structure(emptyMap()),
            )) {
            assertThrows(OpenFeatureError.InvalidContextError::class.java) {
                map(ImmutableContext("A", mapOf(key to value)))
            }
        }
        assertThrows(OpenFeatureError.InvalidContextError::class.java) {
            ContextMapper.map(
                ImmutableContext("A", mapOf("featbit.sdk.x" to Value.Null)),
                IdentityPolicy.REQUIRE_TARGETING_KEY,
                true,
            )
        }
    }

    @Test
    fun missingAndNullAreDifferentButAttributeOrderIsNot() {
        assertNotEquals(
            map(ImmutableContext("A")),
            map(ImmutableContext("A", mapOf("x" to Value.Null))),
        )
        val a = linkedMapOf("x" to Value.Null, "y" to Value.String(""))
        assertEquals(
            map(ImmutableContext("A", a)),
            map(ImmutableContext("A", a.toList().reversed().toMap())),
        )
    }

    @Test
    fun typedErrorsAreExceptionsRatherThanFallbackEquality() {
        assertFalse(ValueMapper.detail(EvaluationDetail("x", false, EvaluationReason.MATCH)))
        assertThrows(OpenFeatureError.TypeMismatchError::class.java) {
            ValueMapper.detail(EvaluationDetail("x", false, EvaluationReason.WRONG_TYPE))
        }
        assertThrows(OpenFeatureError.FlagNotFoundError::class.java) {
            ValueMapper.detail(EvaluationDetail("x", false, EvaluationReason.FLAG_NOT_FOUND))
        }
        assertThrows(OpenFeatureError.ProviderNotReadyError::class.java) {
            ValueMapper.detail(EvaluationDetail("x", false, EvaluationReason.CLIENT_NOT_READY))
        }
        assertThrows(OpenFeatureError.GeneralError::class.java) {
            ValueMapper.detail(EvaluationDetail("x", false, EvaluationReason.ERROR))
        }
    }

    @Test
    fun recursivelyMapsJsonIncludingNullAndScalars() {
        val value =
            FbValue.ofObject(
                    mapOf(
                        "x" to
                            FbValue.ofArray(
                                    listOf(
                                        FbValue.jsonNull(),
                                        FbValue.ofBoolean(true),
                                        FbValue.ofNumber(2.0).value!!,
                                    )
                                )
                                .value!!
                    )
                )
                .value!!
        assertEquals(
            Value.Structure(
                mapOf("x" to Value.List(listOf(Value.Null, Value.Boolean(true), Value.Double(2.0))))
            ),
            ValueMapper.json(value),
        )
    }
}
