package co.featbit.sample.java;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.openfeature.kotlin.sdk.OpenFeatureAPI;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class OpenFeatureSampleTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private SampleSession session;

    interface Condition {
        boolean check();
    }

    private void waitFor(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> ready[0] = condition.check());
            if (ready[0]) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(80);
        }
        fail("OpenFeature sample did not settle");
    }

    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        Thread.sleep(500);
        android.os.ParcelFileDescriptor descriptor =
                instrumentation
                        .getUiAutomation()
                        .executeShellCommand(
                                "screencap -p /sdcard/Download/openfeature-cafe-java-"
                                        + name
                                        + ".png");
        try (InputStream input =
                new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[1024];
            while (input.read(buffer) != -1) {}
        }
    }

    @Test
    public void providerCafeFlow() throws Exception {
        try (ActivityScenario<MainActivity> scenario =
                ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> session = activity.session);
            waitFor(() -> session.state.available());
            scenario.onActivity(
                    activity -> {
                        assertFalse(session.state.business.usingFallback);
                        assertEquals("4.50", session.state.business.total().toPlainString());
                        assertEquals(4, session.state.snapshot.size());
                        assertTrue(
                                OpenFeatureAPI.OpenFeatureAPI.getClient("test", "1")
                                        .getBooleanValue(session.specs.get(0).key, false));
                        session.dismissMessage();
                    });
            capture("demo");
            scenario.onActivity(activity -> session.edit(session.specs.get(0).key, "false"));
            waitFor(() -> session.state.available() && !session.state.business.compact);
            scenario.onActivity(activity -> session.edit(session.specs.get(2).key, "101"));
            waitFor(() -> session.state.available() && session.state.business.discountInvalid);
            scenario.onActivity(activity -> session.edit(session.specs.get(3).key, "null"));
            waitFor(() -> session.state.available() && session.state.business.menuInvalid);
            scenario.onActivity(activity -> session.edit(session.specs.get(0).key, null));
            waitFor(
                    () ->
                            session.state.available()
                                    && "FLAG_NOT_FOUND"
                                            .equals(
                                                    session.state.snapshot.get(
                                                                    session.specs.get(0).key)
                                                            .error));
            scenario.onActivity(
                    activity -> {
                        session.evaluate(session.specs.get(0).key);
                        assertEquals(
                                "FLAG_NOT_FOUND",
                                session.state.reads.get(session.specs.get(0).key).reason);
                        session.edit(null, null, true, ok -> {});
                    });
            waitFor(
                    () ->
                            session.state.available()
                                    && !session.state.business.usingFallback
                                    && session.state.business.compact);
            scenario.onActivity(activity -> session.identify(1));
            waitFor(() -> session.state.available() && session.state.user == 1);
            scenario.onActivity(
                    activity -> {
                        assertEquals(
                                session.people.get(1).key,
                                OpenFeatureAPI.OpenFeatureAPI.getEvaluationContext()
                                        .getTargetingKey());
                        assertFalse(session.state.business.usingFallback);
                        session.order();
                        assertEquals("Events disabled in Local", session.state.lastTrack);
                        session.connect();
                    });
            waitFor(() -> session.state.available());
            scenario.onActivity(
                    activity -> {
                        assertFalse(session.state.business.usingFallback);
                        session.draft.local = false;
                        session.draft.key = "test";
                        session.draft.streaming = "ws://localhost:5100";
                        session.draft.events = false;
                        session.draft.pollingFallback = true;
                        assertTrue(session.validateDraft().containsKey("pollingUrl"));
                        session.draft.polling = "http://localhost:5100";
                        assertTrue(session.validateDraft().isEmpty());
                        session.draft.local = true;
                        session.dismissMessage();
                        session.destination = "Flags";
                        activity.navigate();
                    });
            capture("flags");
            scenario.onActivity(
                    activity -> {
                        activity.openFlag(session.specs.get(0).key);
                        session.evaluate(session.specs.get(0).key);
                    });
            capture("detail");
            androidx.test.espresso.Espresso.pressBack();
            scenario.onActivity(
                    activity -> {
                        session.destination = "Inspect";
                        activity.navigate();
                    });
            capture("inspect");
            scenario.onActivity(
                    activity -> {
                        session.formOpen = true;
                        activity.navigate();
                    });
            capture("connection");
        }
    }
}
