package dev.pschmitt.jellyfin.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import dagger.hilt.android.AndroidEntryPoint
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.Downloader
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pauses every active download when Android's battery saver turns on, and resumes the ones it
 * paused once battery saver turns back off, gated behind
 * AppPreferences.pauseDownloadsOnBatterySaver. See Downloader.pauseAllForBatterySaver /
 * resumeBatterySaverPausedDownloads. This broadcast only fires on a state change, so
 * BaseApplication.onCreate() separately checks PowerManager.isPowerSaveMode at startup to cover the
 * case where battery saver is already on when the app launches.
 */
@AndroidEntryPoint
class BatterySaverReceiver : BroadcastReceiver() {

    @Inject lateinit var downloader: Downloader

    @Inject lateinit var appPreferences: AppPreferences

    companion object {
        suspend fun reconcile(
            isPowerSaveMode: Boolean,
            downloader: Downloader,
            appPreferences: AppPreferences,
        ) {
            if (isPowerSaveMode) {
                if (appPreferences.getValue(appPreferences.pauseDownloadsOnBatterySaver)) {
                    downloader.pauseAllForBatterySaver()
                }
            } else {
                // Turning this preference off must not strand downloads that were already marked
                // as paused by battery saver.
                downloader.resumeBatterySaverPausedDownloads()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return
        val isPowerSaveMode = powerManager.isPowerSaveMode
        val pauseOnBatterySaver =
            appPreferences.getValue(appPreferences.pauseDownloadsOnBatterySaver)
        if (isPowerSaveMode && !pauseOnBatterySaver) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                reconcile(isPowerSaveMode, downloader, appPreferences)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
