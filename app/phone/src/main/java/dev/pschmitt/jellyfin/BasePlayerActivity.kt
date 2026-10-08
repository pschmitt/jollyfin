package dev.pschmitt.jellyfin

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import dev.pschmitt.jellyfin.player.local.R as PlayerR
import dev.pschmitt.jellyfin.player.local.presentation.PlayerViewModel

abstract class BasePlayerActivity : AppCompatActivity() {

    abstract val viewModel: PlayerViewModel

    private var mediaSession: MediaSession? = null
    private var wasPip: Boolean = false

    // Set while the screen is off and playback deliberately carries on (see
    // isBackgroundPlaybackEnabled) - the lifecycle callbacks below then leave the player and its
    // session alone instead of pausing/releasing them as they normally would.
    private var playingInBackground: Boolean = false

    /** Whether playback should carry on once the screen turns off. */
    protected open fun isBackgroundPlaybackEnabled(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    override fun onStart() {
        super.onStart()

        if (playingInBackground) {
            // Screen's back on. Whatever the playback state is now (it may have been paused from
            // the lock screen meanwhile) is what onResume should restore. Cleared here rather than
            // in onResume, since a PiP window comes back with onStart only.
            viewModel.playWhenReady = viewModel.player.playWhenReady
            playingInBackground = false
        }
        // Still alive from before the screen went off - nothing to rebuild.
        if (mediaSession != null) return

        val session =
            MediaSession.Builder(this, viewModel.player)
                // Tapping the playback notification returns to this (singleTask) activity.
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, javaClass),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                // Rewind/fast-forward next to play/pause and prev/next - for video, jumping back
                // a few seconds is what you reach for most from the lock screen.
                .setMediaButtonPreferences(
                    listOf(
                        CommandButton.Builder(CommandButton.ICON_REWIND)
                            .setDisplayName(getString(PlayerR.string.player_controls_rewind))
                            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                        CommandButton.Builder(CommandButton.ICON_FAST_FORWARD)
                            .setDisplayName(getString(PlayerR.string.player_controls_fast_forward))
                            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                    )
                )
                .build()
        mediaSession = session
        // Started here, while the activity is in the foreground - a foreground service can't
        // be started from the background, which is exactly where we'd be once the screen is off.
        if (isBackgroundPlaybackEnabled()) BackgroundPlaybackService.start(this, session)
    }

    override fun onResume() {
        super.onResume()

        if (wasPip) {
            wasPip = false
        } else {
            viewModel.player.playWhenReady = viewModel.playWhenReady
        }
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()

        when {
            isInPictureInPictureMode -> wasPip = true
            shouldKeepPlaying() -> {
                playingInBackground = true
                viewModel.updatePlaybackProgress()
            }
            else -> {
                viewModel.playWhenReady = viewModel.player.playWhenReady
                viewModel.player.playWhenReady = false
                viewModel.updatePlaybackProgress()
            }
        }
    }

    override fun onStop() {
        super.onStop()

        // The screen turning off while in PiP lands here without a preceding onPause - same
        // decision as there, so it keeps playing rather than closing the PiP window.
        if (wasPip && shouldKeepPlaying()) playingInBackground = true
        if (playingInBackground) return

        releaseSession()

        if (wasPip) {
            finish()
        }
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    /**
     * Screen just turned off (not Home/back/another app) while playing, and the user wants that.
     */
    private fun shouldKeepPlaying(): Boolean =
        isBackgroundPlaybackEnabled() &&
            viewModel.player.isPlaying &&
            !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    private fun releaseSession() {
        BackgroundPlaybackService.stop(this)
        mediaSession?.release()
        mediaSession = null
    }

    protected fun hideSystemUI() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    protected fun configureInsets(playerControls: View) {
        playerControls.setOnApplyWindowInsetsListener { _, windowInsets ->
            val cutout = windowInsets.displayCutout
            playerControls.updatePadding(
                left = cutout?.safeInsetLeft ?: 0,
                top = cutout?.safeInsetTop ?: 0,
                right = cutout?.safeInsetRight ?: 0,
                bottom = cutout?.safeInsetBottom ?: 0,
            )
            return@setOnApplyWindowInsetsListener windowInsets
        }
    }
}
