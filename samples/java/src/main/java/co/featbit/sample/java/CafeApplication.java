package co.featbit.sample.java;

import android.app.Application;

public final class CafeApplication extends Application {
    SampleSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new SampleSession(this);
    }
}
