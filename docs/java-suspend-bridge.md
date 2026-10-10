# Calling OpenFeature suspend functions from Java

This optional application helper adapts OpenFeature's Kotlin suspend functions to callbacks when completion sequencing or full API shutdown is needed. The [Java Quick Start](../README.java.md#quick-start) uses non-suspending APIs and does not require it. It is not a Provider or OpenFeature API and owns no provider or client.

Save it as `JavaSuspend.java` in your application's package, adding your package declaration. It handles immediate and suspended completion and posts callbacks to the Android main thread. It does not serialize operations or provide cancellation. Wait for each lifecycle callback before starting the next operation, and avoid retaining destroyed Activities.

```java
import android.os.Handler;
import android.os.Looper;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;

public final class JavaSuspend {
    private static final Handler main = new Handler(Looper.getMainLooper());

    private JavaSuspend() {}

    public interface Completion {
        void complete(Throwable failure);
    }

    public interface SuspendCall {
        Object invoke(Continuation<? super Unit> continuation);
    }

    public static void run(SuspendCall call, Completion completion) {
        Continuation<Unit> continuation = new Continuation<Unit>() {
            @Override public CoroutineContext getContext() {
                return EmptyCoroutineContext.INSTANCE;
            }

            @Override public void resumeWith(Object result) {
                Throwable failure = null;
                try {
                    ResultKt.throwOnFailure(result);
                } catch (Throwable error) {
                    failure = error;
                }
                Throwable deliveredFailure = failure;
                main.post(() -> completion.complete(deliveredFailure));
            }
        };
        try {
            Object result = call.invoke(continuation);
            if (result != IntrinsicsKt.getCOROUTINE_SUSPENDED()) {
                continuation.resumeWith(result);
            }
        } catch (Throwable failure) {
            main.post(() -> completion.complete(failure));
        }
    }
}
```

A callback without an exception means the lifecycle call completed; it does not prove flags are ready. Inspect evaluation details as described in the README. FeatBit Operation observers are separate and do not guarantee main-thread delivery.

## Full OpenFeature shutdown

After adding the helper above, use the same `api` and `provider` created in the Java Quick Start. Finish registration and context changes before calling this snippet; only the owner of the global API should shut it down.

```java
JavaSuspend.run(cont -> api.shutdown(cont), failure -> {
    if (failure != null) {
        android.util.Log.e("Flags", "OpenFeature shutdown failed", failure);
    }
    provider.close().observe(outcome -> {
        if (outcome.isSuccess()) {
            android.util.Log.i("Flags", "Cleanup complete: "
                    + outcome.getValue().getCleanupComplete()
                    + "; undelivered events: " + outcome.getValue().getUndeliveredEvents());
        } else {
            android.util.Log.w("Flags", "Close result: " + outcome.getCode());
        }
    });
});
```

The shutdown callback is posted to the main thread. The nested close observer has no main-thread guarantee. OpenFeature shutdown detaches the provider and initiates cleanup; the close outcome reports the cleanup result. Repeated `provider.close()` calls observe the same operation.

For cancellable UI observation, see the [Java sample bridge](../samples/java/src/main/java/co/featbit/sample/java/OpenFeatureBridge.java).
