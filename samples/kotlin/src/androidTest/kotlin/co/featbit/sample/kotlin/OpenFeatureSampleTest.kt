package co.featbit.sample.kotlin

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openfeature.kotlin.sdk.OpenFeatureAPI
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenFeatureSampleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun waitFor(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            var result = false
            instrumentation.runOnMainSync { result = check() }
            if (result) {
                instrumentation.waitForIdleSync()
                return
            }
            Thread.sleep(80)
        }
        fail("OpenFeature sample did not settle")
    }

    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        val descriptor =
            instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /sdcard/Download/openfeature-cafe-kotlin-$name.png"
            )
        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    @Test
    fun providerCafeFlow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var session: SampleSession
            scenario.onActivity { session = it.session }
            waitFor { session.state.value.available }
            scenario.onActivity {
                assertFalse(session.state.value.business.usingFallback)
                assertEquals("4.50", session.state.value.business.total.toPlainString())
                assertEquals(4, session.state.value.snapshot.size)
                assertTrue(OpenFeatureAPI.getClient().getBooleanValue(session.specs[0].key, false))
                session.dismissMessage()
            }
            capture("demo")
            scenario.onActivity { session.edit(session.specs[0].key, "false") }
            waitFor { session.state.value.available && !session.state.value.business.compact }
            scenario.onActivity { session.edit(session.specs[2].key, "101") }
            waitFor {
                session.state.value.available && session.state.value.business.discountInvalid
            }
            scenario.onActivity { session.edit(session.specs[3].key, "null") }
            waitFor { session.state.value.available && session.state.value.business.menuInvalid }
            scenario.onActivity { session.edit(session.specs[0].key) }
            waitFor {
                session.state.value.available &&
                    session.state.value.snapshot[session.specs[0].key]?.error == "FLAG_NOT_FOUND"
            }
            scenario.onActivity {
                session.evaluate(session.specs[0].key)
                assertEquals(
                    "FLAG_NOT_FOUND",
                    session.state.value.reads[session.specs[0].key]?.reason,
                )
                session.edit(null, restoreAll = true)
            }
            waitFor {
                session.state.value.available &&
                    !session.state.value.business.usingFallback &&
                    session.state.value.business.compact
            }
            scenario.onActivity { session.identify(1) }
            waitFor { session.state.value.available && session.state.value.user == 1 }
            scenario.onActivity {
                assertEquals(
                    session.people[1].key,
                    OpenFeatureAPI.getEvaluationContext()?.getTargetingKey(),
                )
                assertFalse(session.state.value.business.usingFallback)
                session.order()
                assertEquals("Events disabled in Local", session.state.value.lastTrack)
                session.connect()
            }
            waitFor { session.state.value.available }
            scenario.onActivity {
                assertFalse(session.state.value.business.usingFallback)
                session.draft.local = false
                session.draft.key = "test"
                session.draft.streaming = "ws://localhost:5100"
                session.draft.events = false
                session.draft.pollingFallback = true
                assertTrue(session.validateDraft().containsKey("pollingUrl"))
                session.draft.polling = "http://localhost:5100"
                assertTrue(session.validateDraft().isEmpty())
                session.draft.local = true
                session.dismissMessage()
                session.destination = "Flags"
                it.navigate()
            }
            capture("flags")
            scenario.onActivity {
                it.openFlag(session.specs[0].key)
                session.evaluate(session.specs[0].key)
            }
            capture("detail")
            androidx.test.espresso.Espresso.pressBack()
            scenario.onActivity {
                session.destination = "Inspect"
                it.navigate()
            }
            capture("inspect")
            scenario.onActivity {
                session.formOpen = true
                it.navigate()
            }
            capture("connection")
        }
    }
}
