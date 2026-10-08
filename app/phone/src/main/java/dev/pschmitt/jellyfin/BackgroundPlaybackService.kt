package dev.pschmitt.jellyfin

import android.content.Context
import android.content.Intent
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.pschmitt.jellyfin.core.R as CoreR

/**
 * Hosts the player's [MediaSession] while [PlayerActivity] is open, so playback can keep going with
 * the screen off (see
 * [dev.pschmitt.jellyfin.settings.domain.AppPreferences.playerBackgroundPlayback]).
 *
 * The session and its player still belong to the activity/its ViewModel - this service owns
 * neither. Hosting the session here is what lets media3 post the playback notification (with
 * lock-screen controls) and hold a `mediaPlayback` foreground service while the activity is
 * stopped; without that, a backgrounded app gets frozen/killed shortly after the screen turns off
 * and playback dies with it.
 */
class BackgroundPlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        // The app's own (monochrome) logo in the status bar instead of media3's generic one.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(CoreR.drawable.ic_launcher_foreground)
            }
        )
        session?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.let { removeSession(it) }
        super.onDestroy()
    }

    companion object {
        @Volatile private var session: MediaSession? = null

        /** Must be called while the activity is in the foreground (FGS start restrictions). */
        fun start(context: Context, mediaSession: MediaSession) {
            session = mediaSession
            context.startService(Intent(context, BackgroundPlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BackgroundPlaybackService::class.java))
            session = null
        }
    }
}
