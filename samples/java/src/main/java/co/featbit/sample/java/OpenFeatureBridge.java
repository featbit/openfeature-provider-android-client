package co.featbit.sample.java;

import android.os.Handler;
import android.os.Looper;
import co.featbit.android.api.Registration;
import java.util.concurrent.CancellationException;
import kotlin.ResultKt;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.intrinsics.IntrinsicsKt;
import kotlinx.coroutines.*;
import kotlinx.coroutines.flow.Flow;

/** Java-only bridge for the OpenFeature Kotlin suspend/Flow API; never blocks the UI thread. */
final class OpenFeatureBridge {
    interface SuspendCall {
        Object invoke(Continuation<? super Unit> continuation);
    }

    interface Callback<T> {
        void accept(T value);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static Registration run(SuspendCall call, Callback<Throwable> completion) {
        CompletableJob job = JobKt.Job((Job) null);
        Continuation<Unit> continuation =
                new Continuation<Unit>() {
                    public CoroutineContext getContext() {
                        return Dispatchers.getMain().getImmediate().plus(job);
                    }

                    public void resumeWith(Object result) {
                        Throwable failure = null;
                        try {
                            ResultKt.throwOnFailure(result);
                        } catch (Throwable error) {
                            failure = error;
                        }
                        final Throwable outcome = failure;
                        job.complete();
                        MAIN.post(() -> completion.accept(outcome));
                    }
                };
        try {
            Object result = call.invoke(continuation);
            if (result != IntrinsicsKt.getCOROUTINE_SUSPENDED()) continuation.resumeWith(result);
        } catch (Throwable failure) {
            job.complete();
            MAIN.post(() -> completion.accept(failure));
        }
        return () -> job.cancel((CancellationException) null);
    }

    static <T> Registration observe(Flow<T> flow, Callback<T> observer) {
        return run(
                cont ->
                        flow.collect(
                                (value, emitContinuation) -> {
                                    observer.accept(value);
                                    return Unit.INSTANCE;
                                },
                                cont),
                failure -> {
                    if (failure != null && !(failure instanceof CancellationException))
                        android.util.Log.w("OpenFeatureCafe", "Event observation stopped", failure);
                });
    }
}
