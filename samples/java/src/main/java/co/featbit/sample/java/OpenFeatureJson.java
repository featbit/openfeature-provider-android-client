package co.featbit.sample.java;

import com.google.gson.*;
import dev.openfeature.kotlin.sdk.Value;
import java.util.*;

/** Explicit JSON/Value mapping preserves arrays, objects, primitives and null. */
final class OpenFeatureJson {
    static Value parse(String text) {
        return value(JsonParser.parseString(text));
    }

    private static Value value(JsonElement json) {
        if (json.isJsonNull()) return Value.Null.INSTANCE;
        if (json.isJsonObject()) {
            Map<String, Value> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> field : json.getAsJsonObject().entrySet())
                fields.put(field.getKey(), value(field.getValue()));
            return Value.Structure.Companion.invoke(fields);
        }
        if (json.isJsonArray()) {
            List<Value> items = new ArrayList<>();
            for (JsonElement item : json.getAsJsonArray()) items.add(value(item));
            return Value.List.Companion.invoke(items);
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) return new Value.Boolean(primitive.getAsBoolean());
        if (primitive.isString()) return new Value.String(primitive.getAsString());
        return new Value.Double(primitive.getAsDouble());
    }

    static String text(Value value) {
        return json(value).toString();
    }

    private static JsonElement json(Value value) {
        if (value.isNull()) return JsonNull.INSTANCE;
        if (value.asStructure() != null) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, Value> field : value.asStructure().entrySet())
                result.add(field.getKey(), json(field.getValue()));
            return result;
        }
        if (value.asList() != null) {
            JsonArray result = new JsonArray();
            for (Value item : value.asList()) result.add(json(item));
            return result;
        }
        if (value.asBoolean() != null) return new JsonPrimitive(value.asBoolean());
        if (value.asString() != null) return new JsonPrimitive(value.asString());
        if (value.asDouble() != null) return new JsonPrimitive(value.asDouble());
        if (value.asInteger() != null) return new JsonPrimitive(value.asInteger());
        if (value.asLong() != null) return new JsonPrimitive(value.asLong());
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
