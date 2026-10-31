package dev.pschmitt.jellyfin

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * CI's software-rendered emulator can get slow enough for unrelated system apps to ANR mid-test,
 * covering the screen with a blocking "isn't responding" dialog unrelated to this app. Dismiss it
 * by clicking "Wait" whenever it appears so a transient system hiccup doesn't fail the whole
 * capture. Mirrors the same rule in the sibling nyetbox project's own screenshot test.
 */
class AnrDismissRule : TestWatcher() {
    private var watcherThread: Thread? = null

    override fun starting(description: Description) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        watcherThread =
            Thread {
                    while (!Thread.currentThread().isInterrupted) {
                        runCatching {
                            if (device.findObject(By.textContains("isn't responding")) != null) {
                                device.findObject(By.text("Wait"))?.click()
                            }
                        }
                        Thread.sleep(1_000)
                    }
                }
                .apply {
                    isDaemon = true
                    start()
                }
    }

    override fun finished(description: Description) {
        watcherThread?.interrupt()
        watcherThread = null
    }
}
