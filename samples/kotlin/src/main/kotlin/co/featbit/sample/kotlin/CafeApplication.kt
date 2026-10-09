package co.featbit.sample.kotlin

import android.app.Application

class CafeApplication : Application() {
    lateinit var session: SampleSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = SampleSession(this)
    }
}
