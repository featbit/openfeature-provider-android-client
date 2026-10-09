package co.featbit.sample.java;

import static co.featbit.sample.java.CafeModel.*;
import static org.junit.Assert.*;

import org.junit.Test;

public class BusinessTest {
    @Test
    public void halfUpAndRange() {
        assertEquals("4.50", Business.price(10).toPlainString());
        assertEquals("0.01", Business.price(99.9).toPlainString());
        assertEquals("0.00", Business.price(100).toPlainString());
        for (double d : new double[] {-1, 101, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals("5.00", Business.price(d).toPlainString());
    }

    @Test
    public void exactIdsAndExtraFields() {
        String raw =
                "{\"sizes\":[{\"id\":\"regular\",\"label\":\"Regular\",\"future\":true}],\"defaultSize\":\"regular\",\"future\":1}";
        assertEquals("regular", Business.menu(raw).defaultSize);
        assertNull(
                Business.menu(
                        raw.replace("\"defaultSize\":\"regular\"", "\"defaultSize\":\"Regular\"")));
    }

    @Test
    public void invalidMenus() {
        for (String raw :
                new String[] {
                    "null",
                    "[]",
                    "{}",
                    "{",
                    "{\"sizes\":[],\"defaultSize\":\"regular\"}",
                    "{\"sizes\":[{\"id\":1,\"label\":\"Regular\"}],\"defaultSize\":\"1\"}",
                    "{\"sizes\":[{\"id\":\"r\",\"label\":\"R\"},{\"id\":\"r\",\"label\":\"R\"}],\"defaultSize\":\"r\"}",
                    "{\"sizes\":[{\"id\":\"r\",\"label\":\" \"}],\"defaultSize\":\"r\"}"
                }) assertNull(raw, Business.menu(raw));
    }

    @Test
    public void strictJson() {
        for (String raw :
                new String[] {"", "{unquoted:1}", "{'x':1}", "{\"x\":1,}", "{} garbage", "NaN"}) {
            try {
                json(raw);
                fail(raw);
            } catch (IllegalArgumentException expected) {
            }
        }
        assertTrue(json("null").isJsonNull());
        assertTrue(json("[1,true,\"x\"]").isJsonArray());
    }
}
