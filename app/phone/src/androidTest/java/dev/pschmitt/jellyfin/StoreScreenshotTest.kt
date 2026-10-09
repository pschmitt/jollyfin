package dev.pschmitt.jellyfin

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.locale.LocaleTestRule

/**
 * Captures Play Store listing screenshots (en-US only, see fastlane/Screengrabfile) against the
 * disposable, pre-baked Jellyfin fixture (`ci/jellyfin/docker-compose.yml`, see
 * `ci/jellyfin/README.md`) - a Home screen and a movie detail screen, light and dark. Modeled on
 * the sibling `netbox-and-chill` project's own `StoreScreenshotTest`. Never point this test at a
 * real Jellyfin server - the screenshots it produces show whatever library the connected server
 * has.
 *
 * Scope is intentionally narrow for now, per direct instruction: Jellyfin only, Home + one movie
 * detail screen, no PVR/Sonarr/Radarr/Seerr screens and no attempt to also cover `app/tv`.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshotTest {
    companion object {
        @get:ClassRule @JvmStatic val localeTestRule = LocaleTestRule()

        // The fixture's baked admin account (see ci/jellyfin/README.md) - fixed, disposable
        // credentials, not a secret.
        private const val FIXTURE_USERNAME = "admin"
        private const val FIXTURE_PASSWORD = "adminpass123"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:8096"

        // Navigation pops crossfade for 300ms; a bit over twice that covers slow emulators.
        private const val TRANSITION_SETTLE_MS = 700L
    }

    @get:Rule val anrDismissRule = AnrDismissRule()

    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val arguments
        get() = InstrumentationRegistry.getArguments()

    @Test
    fun captureStoreScreenshots() {
        try {
            val baseUrl = arguments.getString("e2e_base_url") ?: DEFAULT_BASE_URL
            val username = arguments.getString("e2e_username") ?: FIXTURE_USERNAME
            val password = arguments.getString("e2e_password") ?: FIXTURE_PASSWORD

            connectAndLogIn(baseUrl, username, password)
            captureJourney(suffix = "")

            // captureJourney("") always ends back on Home; switch the color scheme there for
            // real, through the same Settings UI a user would use, then repeat the journey with a
            // "_dark" suffix so the store listing gets both variants from one test run.
            switchToDarkModeAndReturnToHome()
            captureJourney(suffix = "_dark")
        } catch (t: Throwable) {
            // The emulator is gone by the time a later CI step could pull a screencap/logcat -
            // android-emulator-runner tears it down synchronously as part of its own failed step.
            // screengrab itself skips pulling any Screengrab.screenshot() captures at all once the
            // test class reports a failure - captureE2eScreenshot writes straight to the app's
            // external files dir instead, which the workflow can adb pull independently.
            captureE2eScreenshot("FAILURE_debug")
            throw t
        }
    }

    /**
     * Walks the full first-run setup flow: Welcome -> Servers (empty, "Add server" FAB) ->
     * AddServer -> Users (empty, "Add user" FAB) -> Login -> Home. There is no way to skip straight
     * to AddServer/Login - a fresh install always starts with no servers and no users saved locally
     * (see NavigationRoot.kt's WelcomeRoute/ServersRoute/UsersRoute wiring).
     */
    private fun connectAndLogIn(baseUrl: String, username: String, password: String) {
        clickUntil("e2e-add-server-fab") { composeRule.onNodeWithText("Continue") }
        clickUntil("e2e-server-url") { composeRule.onNodeWithTag("e2e-add-server-fab") }

        composeRule.onNodeWithTag("e2e-server-url").performTextInput(baseUrl)
        dismissKeyboard()
        clickUntil("e2e-add-user-fab", attemptTimeoutMillis = 30_000) {
            composeRule.onNodeWithTag("e2e-connect-button")
        }
        clickUntil("e2e-username") { composeRule.onNodeWithTag("e2e-add-user-fab") }
        composeRule.onNodeWithTag("e2e-username").performTextInput(username)
        composeRule.onNodeWithTag("e2e-password").performTextInput(password)
        dismissKeyboard()
        clickUntil("e2e-home-screen", attemptTimeoutMillis = 60_000) {
            composeRule.onNodeWithTag("e2e-login-button")
        }

        waitForHomeLoaded()
    }

    private fun switchToDarkModeAndReturnToHome() {
        clickUntil("e2e-settings-screen") { composeRule.onNodeWithTag("e2e-settings-button") }
        // The root Settings screen's lone "Appearance" section has both its own section header
        // and its single category row titled "Appearance" (SettingsViewModel.kt reuses
        // settings_category_appearance for both the PreferenceGroup name and the PreferenceCategory
        // inside it), so onNodeWithText("Appearance") is ambiguous - and text-matching around it
        // isn't reliably fixable at all: real CI runs showed "found 2 nodes" on phone but "found 0
        // nodes" for the same query on the sevenInch/tenInch NavigationRail layout, and even the
        // supposedly-unique description text vanished the same way there. Uses a dedicated testTag
        // on the card itself instead (SettingsGroupCard.kt) - the one thing that doesn't depend on
        // text-matching semantics differing by device/layout.
        waitForTag("e2e-settings-appearance-category", 30_000)
        clickWithRetry { composeRule.onNodeWithTag("e2e-settings-appearance-category") }

        waitForText("Theme", 30_000)
        clickWithRetry { composeRule.onNodeWithText("Theme").performScrollTo() }

        waitForText("Dark", 30_000)
        clickWithRetry { composeRule.onNodeWithText("Dark") }

        // SettingsScreen's UpdateTheme event applies immediately (uiMode is in MainActivity's
        // configChanges, so this doesn't recreate the Activity) - two back presses unwind the two
        // nested Settings destinations pushed above (root Settings, then the Appearance category)
        // back to Home. Firing both immediately back-to-back isn't safe though: a real CI run's
        // failure screenshot showed only the first press had landed (correctly dark-themed root
        // Settings screen, not Home) - the second one raced the first pop's 300ms crossfade
        // transition and got lost. Press, wait for the pop to actually finish, then press again.
        pressBackUntilHome(leavingTag = "e2e-settings-screen")
    }

    /**
     * Press Back until Home is really the current screen again, starting from a screen tagged
     * [leavingTag]. Two ways a single pressBack() + "wait for Home" failed on real CI runs, both on
     * the slower tenInch emulator:
     * - the press was swallowed by a still-running transition, leaving the app on the movie detail
     *   screen (or root Settings) until the wait timed out;
     * - "an e2e-home-screen node exists" passed mid-transition while Settings was still the top of
     *   the back stack, so the next journey opened a movie *on top of Settings* and its own Back
     *   later landed on Settings. So: let each pop settle, accept Home only once it's displayed and
     *   nothing tagged [leavingTag] is left in the tree, and press again only while that screen is
     *   still around - never on Home itself, which would leave the app.
     */
    private fun pressBackUntilHome(leavingTag: String, maxPresses: Int = 4) {
        repeat(maxPresses) {
            if (tagPresent(leavingTag)) device.pressBack()
            // Let the pop's crossfade finish before judging where we are.
            Thread.sleep(TRANSITION_SETTLE_MS)
            composeRule.waitForIdle()
            if (!tagPresent(leavingTag) && homeScreenDisplayed()) {
                waitForHomeLoaded()
                return
            }
        }
        composeRule.waitUntil(30_000) { !tagPresent(leavingTag) && homeScreenDisplayed() }
        waitForHomeLoaded()
    }

    private fun tagPresent(tag: String): Boolean =
        composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun homeScreenDisplayed(): Boolean = runCatching {
        composeRule.onAllNodesWithTag("e2e-home-screen").onFirst().isDisplayed()
    }
        .getOrDefault(false)

    private fun captureJourney(suffix: String) {
        captureScreenshot("01_home$suffix")

        clickUntil("e2e-movie-title", attemptTimeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag("e2e-item-card").onFirst()
        }
        waitForContentDescription("Play", 30_000)
        waitForTag("e2e-movie-title", 30_000)
        captureScreenshot("02_movie_detail$suffix")

        pressBackUntilHome(leavingTag = "e2e-movie-title")
    }

    /**
     * Home has real data once at least one item card has rendered - section titles are
     * data-dependent (library name, whether a resume point exists), so a fixed title/loading text
     * isn't a reliable wait target here. 30s wasn't enough for the tenInch emulator profile to get
     * from login to Home rendering at all (confirmed via a real CI run - a plain
     * ComposeTimeoutException, not a real bug), so both waits here are generous.
     *
     * A rendered item card isn't necessarily an on-screen one though: the first real CI run's own
     * "01_home" capture showed only the "Pending downloads" section (still mid-load/collapse) with
     * no movie rows at all, even though the item card the test went on to click was already present
     * in the tree - it just hadn't scrolled into the loaded viewport yet. The later "01_home_dark"
     * capture in that same run, past that same code path a second time, correctly showed the
     * library. Wait for the card to actually be displayed, not merely present, before treating Home
     * as ready to screenshot.
     */
    private fun waitForHomeLoaded() {
        waitForTag("e2e-home-screen", 60_000)
        waitForTag("e2e-item-card", 60_000)
        composeRule.waitUntil(30_000) {
            runCatching { composeRule.onAllNodesWithTag("e2e-item-card").onFirst().isDisplayed() }
                .getOrDefault(false)
        }
    }

    /**
     * The semantics-tree waits above (tags, content descriptions, isDisplayed) only confirm the
     * layout has the right nodes - they say nothing about whether Coil has actually finished
     * decoding and painting poster/backdrop images yet, or whether Home's pull-to-refresh spinner
     * has fully settled. Confirmed via real captures on the merged PR: a movie detail screenshot
     * with a plain gray backdrop (image request still in flight) and a Home screenshot still
     * showing only the "Pending downloads" section with the refresh spinner, no library rows
     * visible at all, despite every wait above having already passed. A flat settle delay before
     * every capture is cruder than waiting on a real signal, but there isn't one exposed to grab
     * onto here (no "images loaded"/"refresh settled" semantics anywhere in the app).
     */
    private fun captureScreenshot(name: String) {
        Thread.sleep(1_500)
        Screengrab.screenshot(name)
    }

    /**
     * performTextInput leaves the field focused, raising the on-screen keyboard - the IME is a
     * system overlay that sits on top of the app and intercepts touches for whatever it covers, so
     * a button left underneath it never actually receives a click even though Compose reports the
     * click as having "succeeded" (confirmed via a real CI run's failure screenshot: stuck on Login
     * with both fields correctly filled in, keyboard still open, "logging in" was a no-op). A
     * back-press with the IME actually visible only dismisses it (standard Android behavior)
     * without navigating away - but pressing back too early, before the IME's own show animation
     * has actually finished, performs real back navigation instead (confirmed the hard way: this
     * exact sequence, without the delay below, once popped AddServer back to an empty Servers
     * screen instead of just closing the keyboard). Give it a moment to actually show first.
     */
    private fun dismissKeyboard() {
        Thread.sleep(700)
        device.pressBack()
    }

    /**
     * `AssertionError: Failed to inject touch input` showed up in real CI runs, both times right
     * after a window/screen was freshly created - neither android-emulator-runner's own
     * emulator-ready wait nor this repo's `ci/android-e2e-wait.sh` (full
     * `boot_completed`/`device_provisioned`/package-service readiness) eliminated it, which points
     * to a transient window-focus race rather than something a longer upfront wait reliably avoids.
     * Retry the click itself instead - re-querying the node each attempt, since a
     * `SemanticsNodeInteraction` can go stale across recompositions.
     */
    private fun clickWithRetry(attempts: Int = 5, node: () -> SemanticsNodeInteraction) {
        repeat(attempts - 1) {
            try {
                node().performClick()
                settleAfterClick()
                return
            } catch (e: AssertionError) {
                Thread.sleep(1_000)
            }
        }
        node().performClick()
        settleAfterClick()
    }

    /**
     * A click that navigates, retried until the destination (a node tagged [expectedTag]) shows up.
     * [clickWithRetry] only retries when the click itself throws - but real CI runs also had clicks
     * that "succeeded" without registering (e.g. Welcome's "Continue" on the sevenInch emulator: no
     * exception, still on Welcome, so the next step's node never appeared). If an earlier attempt
     * did land after all, the source node is gone by the next attempt and that click failing is
     * expected, not an error.
     */
    private fun clickUntil(
        expectedTag: String,
        attempts: Int = 4,
        attemptTimeoutMillis: Long = 10_000,
        node: () -> SemanticsNodeInteraction,
    ) {
        repeat(attempts) {
            if (tagPresent(expectedTag)) return
            runCatching { node().performClick() }
            settleAfterClick()
            val arrived = runCatching {
                composeRule.waitUntil(attemptTimeoutMillis) { tagPresent(expectedTag) }
            }
                .isSuccess
            if (arrived) return
        }
        waitForTag(expectedTag, attemptTimeoutMillis)
    }

    /**
     * `NavigationRoot.kt`'s `NavHost` crossfades every destination change over 300ms
     * (`fadeIn(tween(300))`/`fadeOut(tween(300))`). A click that successfully found its target node
     * has still, in a real CI run, gone on to have that exact node vanish again moments later (a
     * fresh `waitForText` immediately followed by a `ComposeTimeoutException`-free but
     * node-not-found click) - querying the destination screen before the crossfade actually
     * finishes can land in that gap. A short settle after every click is cheaper than chasing that
     * race at each individual call site.
     */
    private fun settleAfterClick() {
        Thread.sleep(350)
    }

    private fun waitForText(text: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule
                .onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForContentDescription(description: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule
                .onAllNodesWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForTag(tag: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
