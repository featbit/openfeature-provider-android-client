package co.featbit.sample.java;

import static co.featbit.sample.java.CafeModel.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import co.featbit.android.api.*;
import co.featbit.android.testing.*;
import co.featbit.openfeature.android.FeatBitProvider;
import com.google.gson.*;
import dev.openfeature.kotlin.sdk.*;
import dev.openfeature.kotlin.sdk.events.OpenFeatureProviderEvents.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import kotlinx.coroutines.Dispatchers;

/** Application-owned Java integration. SDK completions marshal to main; no blocking waits. */
final class SampleSession {
    final ScreenState state = new ScreenState();
    final List<FlagSpec> specs = new ArrayList<>();
    final List<Person> people = new ArrayList<>();
    final ConnectionDraft draft = new ConnectionDraft();
    String destination = "Demo", detailKey, editorKey, editorText = "", filter = "";
    boolean formOpen, userSheet;
    int userChoice, scrollY;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> localValues = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private FeatBitProvider client;
    private final OpenFeatureAPIInstance api = OpenFeatureAPI.OpenFeatureAPI;
    private final Client flags = api.getClient("cafe-java", "1.0");
    private TestData data;
    private Registration changes, statusRegistration;
    private long generation;
    private boolean started;
    private boolean identityAdopting;

    SampleSession(Context context) {
        this.context = context.getApplicationContext();
        for (JsonElement e : asset("demo-flags.json")) {
            FlagSpec f = new FlagSpec(e.getAsJsonObject());
            specs.add(f);
            localValues.put(f.key, f.initial);
        }
        for (JsonElement e : asset("users.json")) people.add(new Person(e.getAsJsonObject()));
    }

    private JsonArray asset(String name) {
        try (Reader reader =
                new InputStreamReader(context.getAssets().open(name), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Missing shared sample asset: " + name, ex);
        }
    }

    Registration observe(Runnable listener) {
        listeners.add(listener);
        listener.run();
        return () -> listeners.remove(listener);
    }

    private void changed() {
        state.revision++;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    void start() {
        if (!started) {
            started = true;
            connect();
        }
    }

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private void log(String message) {
        state.history.add(new ActivityEntry(now(), message));
        while (state.history.size() > 100) state.history.remove(0);
        changed();
    }

    void dismissMessage() {
        state.message = null;
        changed();
    }

    void invalidateReads() {
        for (ReadRecord r : state.reads.values()) r.stale = true;
    }

    String providerStatus() {
        OpenFeatureStatus status = api.getStatus();
        if (status instanceof OpenFeatureStatus.Ready) return "Ready";
        if (status instanceof OpenFeatureStatus.NotReady) return "Not ready";
        if (status instanceof OpenFeatureStatus.Reconciling) return "Reconciling";
        if (status instanceof OpenFeatureStatus.Stale) return "Stale";
        if (status instanceof OpenFeatureStatus.Fatal) return "Fatal";
        return "Error";
    }

    String sdkVersion() {
        return "OpenFeature 0.9.0 / FeatBit " + SdkInfo.getVersion();
    }

    FlagSpec spec(String key) {
        for (FlagSpec s : specs) if (s.key.equals(key)) return s;
        return null;
    }

    String localValue(String key) {
        return localValues.containsKey(key) ? localValues.get(key) : spec(key).initial;
    }

    private List<BootstrapFlag> records(Map<String, String> values) {
        List<BootstrapFlag> result = new ArrayList<>();
        for (FlagSpec f : specs)
            if (values.containsKey(f.key))
                result.add(BootstrapFlag.create(f.key, values.get(f.key), f.type).getValue());
        return result;
    }

    boolean validateValue(FlagSpec spec, String value) {
        try {
            switch (spec.type) {
                case NUMBER:
                    return Double.isFinite(Double.parseDouble(value));
                case BOOLEAN:
                    return value.equals("true") || value.equals("false");
                case JSON:
                    json(value);
                    break;
                case STRING:
                    break;
            }
            return BootstrapFlag.create(spec.key, value, spec.type).isSuccess();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    Map<String, String> validateDraft() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (draft.local) return errors;
        if (draft.key.trim().isEmpty()) errors.put("sdkKey", "Client SDK Key is required");
        boolean streaming = draft.mode == SyncMode.STREAMING;
        if ((streaming ? draft.streaming : draft.polling).trim().isEmpty())
            errors.put(
                    streaming ? "streamingUrl" : "pollingUrl", "Synchronization URL is required");
        if (draft.events && draft.eventsUrl.trim().isEmpty())
            errors.put("eventsUrl", "Events URL is required");
        if (streaming && draft.pollingFallback && draft.polling.trim().isEmpty())
            errors.put("pollingUrl", "Polling URL is required when fallback is enabled");
        if (errors.isEmpty()) {
            Outcome<ClientOptions> o = liveOptions().build();
            if (!o.isSuccess())
                errors.put(
                        o.getDiagnostic() != null && o.getDiagnostic().getField() != null
                                ? o.getDiagnostic().getField()
                                : "form",
                        "Invalid configuration: " + diagnostic(o));
        }
        return errors;
    }

    private ClientOptions.Builder liveOptions() {
        return liveOptions(people.get(state.user).user());
    }

    private ClientOptions.Builder liveOptions(User user) {
        ClientOptions.Builder b =
                ClientOptions.builder()
                        .user(user)
                        .sdkKey(draft.key)
                        .mode(draft.mode)
                        .disableEvents(!draft.events);
        if (draft.mode == SyncMode.STREAMING) {
            b.streamingUrl(draft.streaming).pollingFallback(draft.pollingFallback);
            if (draft.pollingFallback) b.pollingUrl(draft.polling);
        } else b.pollingUrl(draft.polling).pollingFallback(false);
        if (draft.events) b.eventsUrl(draft.eventsUrl);
        return b;
    }

    static String result(Outcome<?> o) {
        return String.valueOf(o.isSuccess() && o.getValue() != null ? o.getValue() : o.getCode());
    }

    private static String diagnostic(Outcome<?> o) {
        return o.getDiagnostic() == null ? o.getCode().toString() : o.getDiagnostic().getCode();
    }

    /**
     * Detaching an observer never cancels SDK work. On registration rejection retry observation of
     * the same operation, checking its settled result; never submit a duplicate operation.
     */
    private <T> void settled(Operation<T> op, Consumer<Outcome<T>> callback) {
        class Wait implements Runnable {
            Registration registration;
            boolean done;

            void complete(Outcome<T> outcome) {
                if (done) return;
                done = true;
                main.removeCallbacks(this);
                if (registration != null) registration.close();
                callback.accept(outcome);
            }

            @Override
            public void run() {
                Outcome<T> existing = op.getResult();
                if (existing != null) {
                    complete(existing);
                    return;
                }
                Outcome<Registration> observing = op.observe(o -> main.post(() -> complete(o)));
                if (observing.isSuccess()) registration = observing.getValue();
                else {
                    state.message = "Operation observation unavailable: " + observing.getCode();
                    changed();
                    main.postDelayed(this, 100);
                }
            }
        }
        new Wait().run();
    }

    void connect() {
        if (state.busy != null || state.flushPending) return;
        Map<String, String> errors = validateDraft();
        if (!errors.isEmpty()) {
            state.message = errors.values().iterator().next();
            changed();
            return;
        }
        boolean nextLocal = draft.local, nextEvents = !nextLocal && draft.events;
        TestData nextData =
                nextLocal
                        ? TestDataFactory.getDefault().create(records(localValues)).getValue()
                        : null;
        Outcome<ClientOptions> options =
                nextLocal
                        ? (nextData == null
                                ? null
                                : nextData.clientOptions(people.get(state.user).user()))
                        : liveOptions().build();
        if (options == null || !options.isSuccess()) {
            state.message = "Invalid configuration";
            changed();
            return;
        }
        ClientOptions.Builder nextLiveOptions = nextLocal ? null : liveOptions();
        long revision = ++generation;
        identityAdopting = false;
        if (changes != null) changes.close();
        if (statusRegistration != null) statusRegistration.close();
        changes = null;
        statusRegistration = null;
        invalidateReads();
        state.busy = "Reconnecting…";
        state.message = null;
        state.waitTimedOut = false;
        state.active = false;
        changed();
        FeatBitProvider old = client;
        client = null;
        Runnable create =
                () -> {
                    FeatBitProvider c =
                            FeatBitProvider.builder(
                                            context,
                                            user ->
                                                    nextLocal
                                                            ? nextData.clientOptions(user)
                                                            : nextLiveOptions.user(user).build())
                                    .initializationTimeoutMillis(5000)
                                    .contextChangeTimeoutMillis(5000)
                                    .build()
                                    .getValue();
                    client = c;
                    data = nextData;
                    OpenFeatureBridge.run(
                            cont ->
                                    api.setProviderAndWait(
                                            c,
                                            evaluationContext(state.user),
                                            Dispatchers.getIO(),
                                            cont),
                            failure -> {
                                if (revision != generation) {
                                    c.close();
                                    return;
                                }
                                state.busy = null;
                                if (failure != null) {
                                    state.active = false;
                                    state.message =
                                            "Provider registration failed: "
                                                    + failure.getClass().getSimpleName();
                                    c.close();
                                    changed();
                                    return;
                                }
                                state.local = nextLocal;
                                state.events = nextEvents;
                                state.active = true;
                                state.lastTrack = null;
                                state.lastFlush = null;
                                changes =
                                        OpenFeatureBridge.observe(
                                                flags.observe(),
                                                event -> {
                                                    if (revision == generation) {
                                                        refresh(c);
                                                        invalidateReads();
                                                        log(
                                                                "OpenFeature: "
                                                                        + (event
                                                                                        instanceof
                                                                                        ProviderReady
                                                                                ? "Ready"
                                                                                : event
                                                                                                instanceof
                                                                                                ProviderStale
                                                                                        ? "Stale"
                                                                                        : event
                                                                                                        instanceof
                                                                                                        ProviderError
                                                                                                ? "Error"
                                                                                                : "Flags changed"));
                                                    }
                                                });
                                // Read diagnostic snapshots without performing extra flag
                                // evaluations.
                                Runnable poll =
                                        new Runnable() {
                                            public void run() {
                                                if (revision != generation) return;
                                                ConnectionInformation info =
                                                        c.getConnectionInformation();
                                                if (!Objects.equals(state.status, info)) {
                                                    state.status = info;
                                                    changed();
                                                }
                                                main.postDelayed(this, 1000);
                                            }
                                        };
                                main.post(poll);
                                statusRegistration = () -> main.removeCallbacks(poll);
                                refresh(c);
                                log("OpenFeature status: " + providerStatus());
                            });
                };
        if (old == null) create.run();
        else
            settled(
                    old.close(),
                    closed -> {
                        if (!closed.isSuccess()
                                || closed.getValue() == null
                                || !closed.getValue().getCleanupComplete()
                                || closed.getValue().getUndeliveredEvents() > 0) {
                            String warning =
                                    "Close: "
                                            + closed.getCode()
                                            + "; undelivered="
                                            + (closed.getValue() == null
                                                    ? "unknown"
                                                    : closed.getValue().getUndeliveredEvents())
                                            + "; cleanup="
                                            + (closed.getValue() == null
                                                    ? "unknown"
                                                    : closed.getValue().getCleanupComplete());
                            state.message = warning;
                            log(warning);
                        }
                        create.run();
                    });
    }

    private ImmutableContext evaluationContext(int index) {
        Person person = people.get(index);
        Map<String, Value> attributes = new LinkedHashMap<>();
        attributes.put("name", new Value.String(person.name));
        attributes.put("plan", new Value.String(person.plan));
        return new ImmutableContext(person.key, attributes);
    }

    private FlagEvaluationDetails<?> read(FlagSpec spec) {
        switch (spec.type) {
            case BOOLEAN:
                return flags.getBooleanDetails(spec.key, Boolean.parseBoolean(spec.fallback));
            case NUMBER:
                return flags.getDoubleDetails(spec.key, Double.parseDouble(spec.fallback));
            case JSON:
                return flags.getObjectDetails(spec.key, OpenFeatureJson.parse(spec.fallback));
            default:
                return flags.getStringDetails(spec.key, spec.fallback);
        }
    }

    private String display(FlagEvaluationDetails<?> detail) {
        Object value = detail.getValue();
        return value instanceof Value ? OpenFeatureJson.text((Value) value) : String.valueOf(value);
    }

    private void refresh(FeatBitProvider c) {
        if (identityAdopting) return;
        List<FlagEvaluationDetails<?>> results = new ArrayList<>();
        Map<String, SampleValue> snapshot = new LinkedHashMap<>();
        for (FlagSpec spec : specs) {
            FlagEvaluationDetails<?> result = read(spec);
            results.add(result);
            snapshot.put(
                    spec.key,
                    new SampleValue(
                            display(result),
                            result.getErrorCode() == null ? null : result.getErrorCode().name()));
        }
        state.snapshot = snapshot;
        Business b = new Business();
        b.compact = (Boolean) results.get(0).getValue();
        b.promo = (String) results.get(1).getValue();
        double discount = (Double) results.get(2).getValue();
        b.discountInvalid = !Double.isFinite(discount) || discount < 0 || discount > 100;
        b.discount = b.discountInvalid ? 0 : discount;
        Menu parsed = Business.menu(display(results.get(3)));
        b.menuInvalid = parsed == null;
        b.menu = parsed == null ? Business.DEFAULT_MENU : parsed;
        b.usingFallback = false;
        for (FlagEvaluationDetails<?> result : results)
            b.usingFallback |= result.getErrorCode() != null;
        state.business = b;
        if (!b.menu.contains(state.selectedSize)) state.selectedSize = b.menu.defaultSize;
        state.status = c.getConnectionInformation();
        changed();
    }

    void selectSize(String id) {
        if (state.business.menu.contains(id)) {
            state.selectedSize = id;
            changed();
        }
    }

    void identify(int index) {
        FeatBitProvider c = client;
        if (c == null || !state.available() || state.flushPending || index == state.user) return;
        long revision = generation;
        identityAdopting = true;
        invalidateReads();
        state.busy = "Switching to " + people.get(index).name + "…";
        state.waitTimedOut = false;
        changed();
        OpenFeatureBridge.run(
                cont -> api.setEvaluationContextAndWait(evaluationContext(index), cont),
                failure -> {
                    if (revision != generation) return;
                    identityAdopting = false;
                    state.busy = null;
                    if (failure == null) {
                        state.user = index;
                        userSheet = false;
                        state.message = "OpenFeature context: " + providerStatus();
                    } else
                        state.message =
                                "Context change failed: " + failure.getClass().getSimpleName();
                    refresh(c);
                    log(state.message);
                });
    }

    void offline(boolean offline) {
        FeatBitProvider c = client;
        if (c == null || !state.available() || state.flushPending) return;
        long revision = generation;
        state.busy = offline ? "Going offline…" : "Going online…";
        changed();
        settled(
                offline ? c.setOffline(5000) : c.setOnline(5000),
                o -> {
                    if (generation == revision) {
                        state.busy = null;
                        state.message = "Mode: " + result(o);
                        refresh(c);
                        log(state.message);
                    }
                });
    }

    void edit(String key, String value) {
        edit(key, value, false, ok -> {});
    }

    void edit(String key, String value, boolean restoreAll, Consumer<Boolean> completed) {
        if (data == null || !state.local || !state.available()) return;
        FlagSpec spec = spec(key);
        if (spec != null && value != null && !validateValue(spec, value)) {
            completed.accept(false);
            return;
        }
        Map<String, String> proposed = new LinkedHashMap<>(localValues);
        Operation<TestDataResult> operation;
        if (restoreAll) {
            proposed.clear();
            for (FlagSpec f : specs) proposed.put(f.key, f.initial);
            operation = data.replace(records(proposed));
        } else if (spec != null && value != null) {
            proposed.put(key, value);
            operation = data.update(BootstrapFlag.create(key, value, spec.type).getValue());
        } else if (spec != null) {
            proposed.remove(key);
            operation = data.remove(key);
        } else return;
        state.busy = "Saving local flags…";
        changed();
        long revision = generation;
        settled(
                operation,
                o -> {
                    if (revision == generation) {
                        if (o.isSuccess()) {
                            localValues.clear();
                            localValues.putAll(proposed);
                            if (Objects.equals(editorKey, key)) editorKey = null;
                        }
                        state.busy = null;
                        state.message =
                                o.getValue() == TestDataResult.COMMITTED
                                        ? "Applied locally"
                                        : o.getValue() == TestDataResult.SAVED_FOR_NEXT_START
                                                ? "Saved; applies when resumed"
                                                : "Save failed: " + diagnostic(o);
                        if (o.getValue() == TestDataResult.COMMITTED && client != null)
                            refresh(client);
                        log("TestData: " + result(o));
                        completed.accept(o.isSuccess());
                    }
                });
    }

    void evaluate(String key) {
        if (client == null || !state.available()) return;
        FlagSpec f = spec(key);
        FlagEvaluationDetails<?> r = read(f);
        state.reads.put(
                key,
                new ReadRecord(
                        display(r),
                        r.getErrorCode() != null
                                ? r.getErrorCode().name()
                                : String.valueOf(r.getReason()),
                        f.fallback,
                        people.get(state.user).name,
                        now()));
        log("Evaluated " + key + ": " + r.getReason());
    }

    void order() {
        if (client == null || !state.available()) return;
        String size = "";
        for (CupSize s : state.business.menu.sizes)
            if (s.id.equals(state.selectedSize)) size = s.label;
        String r;
        if (state.local) r = "Events disabled in Local";
        else if (!state.events) r = "Events disabled";
        else {
            flags.track(
                    "sample-order-completed",
                    new TrackingEventDetails(
                            state.business.total().doubleValue(),
                            new ImmutableStructure(Collections.emptyMap())));
            r = "Track invoked · delivery not acknowledged";
        }
        state.order = new OrderRecord(size, state.business.total(), people.get(state.user).name, r);
        state.lastTrack = r;
        state.message = "Demo order created · " + r;
        log("Order " + state.order.amount + ": " + r);
    }

    void flush() {
        if (client == null || !state.available() || !state.events || state.flushPending) return;
        long revision = generation;
        state.flushPending = true;
        changed();
        settled(
                client.flush(),
                o -> {
                    if (revision == generation) {
                        state.flushPending = false;
                        state.lastFlush = result(o);
                        state.message = "Flush: " + state.lastFlush;
                        log(state.message);
                    }
                });
    }
}
