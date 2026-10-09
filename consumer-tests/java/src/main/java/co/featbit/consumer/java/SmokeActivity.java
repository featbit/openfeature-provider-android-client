package co.featbit.consumer.java;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import co.featbit.android.api.*;
import co.featbit.android.testing.TestData;
import co.featbit.android.testing.TestDataFactory;
import co.featbit.openfeature.android.*;
import dev.openfeature.kotlin.sdk.ImmutableContext;
import java.util.Collections;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

/** Java source compatibility and minified runtime acceptance against the published test AAR. */
public final class SmokeActivity extends Activity {
    private FeatBitProvider provider;
    private TextView label;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        label = new TextView(this);
        label.setText("Running provider acceptance");
        setContentView(label);
        TestData data =
                TestDataFactory.getDefault()
                        .create(
                                Collections.singletonList(
                                        BootstrapFlag.create("enabled", "true", ValueType.BOOLEAN)
                                                .getValue()))
                        .getValue();
        provider =
                FeatBitProvider.builder(getApplicationContext(), user -> data.clientOptions(user))
                        .build()
                        .getValue();
        ImmutableContext context = new ImmutableContext("java-user", Collections.emptyMap());
        Continuation<Unit> continuation =
                new Continuation<Unit>() {
                    @Override
                    public CoroutineContext getContext() {
                        return EmptyCoroutineContext.INSTANCE;
                    }

                    @Override
                    public void resumeWith(Object result) {
                        try {
                            ResultKt.throwOnFailure(result);
                            verify(context);
                        } catch (Throwable failure) {
                            report(false);
                        }
                    }
                };
        Object result = provider.initialize(context, continuation);
        if (result != IntrinsicsKt.getCOROUTINE_SUSPENDED()) continuation.resumeWith(result);
    }

    private void verify(ImmutableContext context) {
        if (!provider.getBooleanEvaluation("enabled", false, context).getValue())
            throw new AssertionError();
        provider.close()
                .observe(
                        result ->
                                report(
                                        result.isSuccess()
                                                && result.getValue().getCleanupComplete()));
    }

    private void report(boolean success) {
        String message = success ? "PROVIDER_SMOKE_PASS java" : "PROVIDER_SMOKE_FAIL java";
        message += " " + getIntent().getStringExtra("runId");
        Log.i("ProviderAcceptance", message);
        String displayMessage = message;
        runOnUiThread(() -> label.setText(displayMessage));
    }

    @Override
    public void onDestroy() {
        if (provider != null) provider.close();
        super.onDestroy();
    }
}
