package co.featbit.consumer.java;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import co.featbit.android.api.Outcome;
import co.featbit.android.api.User;
import co.featbit.openfeature.android.FeatBitProvider;
import dev.openfeature.kotlin.sdk.*;
import dev.openfeature.kotlin.sdk.isolated.OpenFeatureAPIFactoryKt;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.Dispatchers;
import org.junit.Test;

public class PublishedContractTest {
    private final Context context =
            new ContextWrapper(null) {
                @Override
                public Context getApplicationContext() {
                    return this;
                }
            };

    private interface SuspendCall {
        Object invoke(Continuation<? super Unit> cont);
    }

    private void await(SuspendCall call) throws InterruptedException {
        BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, cont) -> call.invoke(cont));
    }

    @Test
    public void invalidBudgetIsRejectedWithoutInvokingFactory() {
        Outcome<FeatBitProvider> result =
                FeatBitProvider.builder(
                                context,
                                user -> {
                                    throw new AssertionError("Factory must not run");
                                })
                        .initializationTimeoutMillis(0)
                        .build();
        assertFalse(result.isSuccess());
        assertNull(result.getValue());
    }

    @Test
    public void missingIdentityPreservesExactDefaultsAndReportsErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        FeatBitProvider provider =
                FeatBitProvider.builder(
                                context,
                                user -> {
                                    calls.incrementAndGet();
                                    return Outcome.invalid("unexpected_factory", null);
                                })
                        .build()
                        .getValue();
        OpenFeatureAPIInstance api = OpenFeatureAPIFactoryKt.createOpenFeatureAPIInstance();
        try {
            await(
                    cont ->
                            api.setProviderAndWait(
                                    provider,
                                    new ImmutableContext("", Collections.emptyMap()),
                                    Dispatchers.getDefault(),
                                    cont));
            Client client = api.getClient("java-test", "1");
            FlagEvaluationDetails<Long> result = client.getLongDetails("missing", Long.MAX_VALUE);
            assertEquals(Long.valueOf(Long.MAX_VALUE), result.getValue());
            assertNotNull(result.getErrorCode());
            assertEquals("original", client.getStringValue("missing", "original"));
            assertEquals(0, calls.get());
        } finally {
            await(cont -> api.shutdown(cont));
            provider.close();
        }
    }

    @Test
    public void initialContextReachesOptionsFactoryAsMappedUser() throws Exception {
        AtomicReference<User> received = new AtomicReference<>();
        FeatBitProvider provider =
                FeatBitProvider.builder(
                                context,
                                user -> {
                                    received.set(user);
                                    return Outcome.invalid(
                                            "intentional_configuration_failure", null);
                                })
                        .build()
                        .getValue();
        OpenFeatureAPIInstance api = OpenFeatureAPIFactoryKt.createOpenFeatureAPIInstance();
        Map<String, Value> attributes = new HashMap<>();
        attributes.put("name", new Value.String("Bob"));
        attributes.put("plan", new Value.String("pro"));
        try {
            await(
                    cont ->
                            api.setProviderAndWait(
                                    provider,
                                    new ImmutableContext("user-B", attributes),
                                    Dispatchers.getDefault(),
                                    cont));
            assertEquals("user-B", received.get().getKey());
            assertEquals("Bob", received.get().getName());
            assertEquals("pro", received.get().getAttributes().get("plan").getText());
            assertNotNull(
                    api.getClient("java-test", "1")
                            .getBooleanDetails("flag", false)
                            .getErrorCode());
        } finally {
            await(cont -> api.shutdown(cont));
            provider.close();
        }
    }
}
