package co.featbit.sample.kotlin

import org.junit.Assert.*
import org.junit.Test

class BusinessTest {
    @Test
    fun halfUpAndBusinessRange() {
        assertEquals("4.50", Business.price(10.0).toPlainString())
        assertEquals("0.01", Business.price(99.9).toPlainString())
        assertEquals("0.00", Business.price(100.0).toPlainString())
        listOf(-1.0, 101.0, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertEquals("5.00", Business.price(it).toPlainString())
        }
    }

    @Test
    fun menuUsesExactIdsAndToleratesExtraFields() {
        val raw =
            """{"sizes":[{"id":"regular","label":"Regular","future":true}],"defaultSize":"regular","future":1}"""
        assertEquals("regular", Business.menu(raw)?.defaultSize)
        assertNull(
            Business.menu(raw.replace("\"defaultSize\":\"regular\"", "\"defaultSize\":\"Regular\""))
        )
    }

    @Test
    fun rejectMalformedMenuShapes() {
        listOf(
                "null",
                "[]",
                "{}",
                "{",
                """{"sizes":[],"defaultSize":"regular"}""",
                """{"sizes":[{"id":"regular","label":"Regular"},{"id":"regular","label":"Duplicate"}],"defaultSize":"regular"}""",
                """{"sizes":[{"id":1,"label":"Regular"}],"defaultSize":"1"}""",
                """{"sizes":[{"id":"regular","label":" "}],"defaultSize":"regular"}""",
            )
            .forEach { assertNull(it, Business.menu(it)) }
    }
}
