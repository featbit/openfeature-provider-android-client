package co.featbit.sample.kotlin

import dev.openfeature.kotlin.sdk.Value
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OpenFeatureJsonTest {
    @Test
    fun nestedValuesRemainTyped() {
        val input = """{"nested":[true,null,"coffee",{"price":4.5}],"empty":{}}"""
        val value = OpenFeatureJson.parse(input)
        assertEquals(
            Json.parseToJsonElement(input),
            Json.parseToJsonElement(OpenFeatureJson.text(value)),
        )
        assertTrue(value.asStructure()!!.getValue("nested").asList()!![0] is Value.Boolean)
        assertTrue(value.asStructure()!!.getValue("nested").asList()!![1].isNull())
    }

    @Test
    fun stringIsNotSerializedTwice() {
        assertEquals("\"coffee\"", OpenFeatureJson.text(OpenFeatureJson.parse("\"coffee\"")))
    }
}
