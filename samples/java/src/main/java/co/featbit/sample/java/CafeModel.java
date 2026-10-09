package co.featbit.sample.java;

import co.featbit.android.api.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.math.*;
import java.util.*;

/** Language-local models; the canonical flags and users live in shared/assets. */
final class CafeModel {
    interface Consumer<T> {
        void accept(T value);
    }

    static JsonElement json(String raw) {
        try {
            JsonReader reader = new JsonReader(new StringReader(raw));
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT || raw.trim().isEmpty())
                throw new IllegalArgumentException("Invalid JSON");
            return value;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid JSON", ex);
        }
    }

    static final class FlagSpec {
        final String key, initial, fallback;
        final ValueType type;

        FlagSpec(JsonObject o) {
            key = o.get("key").getAsString();
            type = ValueType.valueOf(o.get("type").getAsString());
            initial = o.get("initial").getAsString();
            fallback = o.get("fallback").getAsString();
        }
    }

    static final class Person {
        final String key, name, plan;

        Person(JsonObject o) {
            key = o.get("key").getAsString();
            name = o.get("name").getAsString();
            plan = o.get("plan").getAsString();
        }

        User user() {
            return User.builder(key)
                    .name(name)
                    .attribute("plan", AttributeValue.text(plan).getValue())
                    .build()
                    .getValue();
        }
    }

    static final class CupSize {
        final String id, label;

        CupSize(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    static final class Menu {
        final List<CupSize> sizes;
        final String defaultSize;

        Menu(List<CupSize> sizes, String defaultSize) {
            this.sizes = Collections.unmodifiableList(sizes);
            this.defaultSize = defaultSize;
        }

        boolean contains(String id) {
            for (CupSize s : sizes) if (s.id.equals(id)) return true;
            return false;
        }
    }

    static final class Business {
        static final Menu DEFAULT_MENU =
                new Menu(
                        Arrays.asList(
                                new CupSize("small", "Small"),
                                new CupSize("regular", "Regular"),
                                new CupSize("large", "Large")),
                        "regular");
        boolean compact, menuInvalid, discountInvalid, usingFallback = true;
        String promo = "Fresh coffee, made for you.";
        double discount;
        Menu menu = DEFAULT_MENU;

        BigDecimal total() {
            return price(discount);
        }

        static BigDecimal price(double discount) {
            double valid =
                    Double.isFinite(discount) && discount >= 0 && discount <= 100 ? discount : 0;
            return new BigDecimal("5.00")
                    .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(valid).movePointLeft(2)))
                    .setScale(2, RoundingMode.HALF_UP);
        }

        static Menu menu(String raw) {
            try {
                JsonObject o = json(raw).getAsJsonObject();
                List<CupSize> sizes = new ArrayList<>();
                Set<String> ids = new HashSet<>();
                for (JsonElement e : o.getAsJsonArray("sizes")) {
                    JsonObject s = e.getAsJsonObject();
                    JsonPrimitive id = s.getAsJsonPrimitive("id"),
                            label = s.getAsJsonPrimitive("label");
                    if (!id.isString()
                            || !label.isString()
                            || id.getAsString().trim().isEmpty()
                            || label.getAsString().trim().isEmpty()
                            || !ids.add(id.getAsString())) return null;
                    sizes.add(new CupSize(id.getAsString(), label.getAsString()));
                }
                JsonPrimitive d = o.getAsJsonPrimitive("defaultSize");
                if (!d.isString() || sizes.isEmpty() || !ids.contains(d.getAsString())) return null;
                return new Menu(sizes, d.getAsString());
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    static final class ConnectionDraft {
        boolean local = true, pollingFallback, events = true;
        String key = "", streaming = "", polling = "", eventsUrl = "";
        SyncMode mode = SyncMode.STREAMING;
    }

    static final class ReadRecord {
        final String value, reason, fallback, user, time;
        boolean stale;

        ReadRecord(String value, String reason, String fallback, String user, String time) {
            this.value = value;
            this.reason = reason;
            this.fallback = fallback;
            this.user = user;
            this.time = time;
        }
    }

    static final class ActivityEntry {
        final String time, message;

        ActivityEntry(String time, String message) {
            this.time = time;
            this.message = message;
        }
    }

    static final class OrderRecord {
        final String size, user, result;
        final BigDecimal amount;

        OrderRecord(String size, BigDecimal amount, String user, String result) {
            this.size = size;
            this.amount = amount;
            this.user = user;
            this.result = result;
        }
    }

    /** Main-thread-owned state. Views observe invalidations and never mutate business state. */
    static final class ScreenState {
        long revision;
        boolean local = true, active, flushPending, events, waitTimedOut;
        int user;
        String busy, message, lastTrack, lastFlush, selectedSize = "regular";
        ConnectionInformation status;
        Map<String, SampleValue> snapshot = Collections.emptyMap();
        Business business = new Business();
        final List<ActivityEntry> history = new ArrayList<>();
        final Map<String, ReadRecord> reads = new LinkedHashMap<>();
        OrderRecord order;

        boolean available() {
            return active && busy == null;
        }
    }

    static final class SampleValue {
        private final String value;

        final String error;

        SampleValue(String value, String error) {
            this.value = value;
            this.error = error;
        }

        String getValue() {
            return value;
        }
    }
}
