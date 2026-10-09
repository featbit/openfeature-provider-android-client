package co.featbit.sample.java;

import static org.junit.Assert.*;

import com.google.gson.JsonParser;
import dev.openfeature.kotlin.sdk.Value;
import org.junit.Test;

public final class OpenFeatureJsonTest {
    @Test
    public void nestedValuesRemainTyped() {
        String input = "{\"nested\":[true,null,\"coffee\",{\"price\":4.5}],\"empty\":{}}";
        Value value = OpenFeatureJson.parse(input);
        assertEquals(
                JsonParser.parseString(input), JsonParser.parseString(OpenFeatureJson.text(value)));
        assertTrue(value.asStructure().get("nested").asList().get(0) instanceof Value.Boolean);
        assertTrue(value.asStructure().get("nested").asList().get(1).isNull());
    }

    @Test
    public void stringIsNotSerializedTwice() {
        assertEquals("\"coffee\"", OpenFeatureJson.text(OpenFeatureJson.parse("\"coffee\"")));
    }
}
