package dev.pschmitt.jellyfin

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import dev.pschmitt.jellyfin.api.pvr.PvrAdvancedConfig
import dev.pschmitt.jellyfin.api.pvr.PvrAdvancedSettings
import dev.pschmitt.jellyfin.localcontrol.LocalControlServer
import dev.pschmitt.jellyfin.profile.ProfileMigrationRunner
import dev.pschmitt.jellyfin.pvr.PvrConfigResolver
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import dev.pschmitt.jellyfin.utils.Downloader
import dev.pschmitt.jellyfin.work.AutoBackupScheduler
import dev.pschmitt.jellyfin.work.AutoDeleteWatchedWorker
import dev.pschmitt.jellyfin.work.AutoDownloadWorker
import dev.pschmitt.jellyfin.work.BatterySaverReceiver
import dev.pschmitt.jellyfin.work.ForegroundDownloadResumer
import dev.pschmitt.jellyfin.work.MpvCleanupWorker
import dev.pschmitt.jellyfin.work.NewItemNotificationWorker
import dev.pschmitt.jellyfin.work.PendingDownloadWorker
import dev.pschmitt.jellyfin.work.PreloadCalendarWorker
import dev.pschmitt.jellyfin.work.QueueStatusScheduler
import dev.pschmitt.jellyfin.work.RemoteConfigScheduler
import dev.pschmitt.jellyfin.work.SyncWorker
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@HiltAndroidApp
class BaseApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences

    @Inject lateinit var pvrConfigResolver: PvrConfigResolver

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var downloader: Downloader

    @Inject lateinit var localControlServer: LocalControlServer

    @Inject lateinit var profileMigrationRunner: ProfileMigrationRunner

    private val batterySaverReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                if (intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val powerManager = context.getSystemService(PowerManager::class.java)
                        if (powerManager != null) {
                            BatterySaverReceiver.reconcile(
                                powerManager.isPowerSaveMode,
                                downloader,
                                appPreferences,
                            )
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        // Planted unconditionally, not just for BuildConfig.DEBUG - otherwise Timber is silent on
        // the release builds users actually run, which leaves no trace in logcat when diagnosing
        // e.g. a Sonarr search failure after the fact (see
        // PvrHttpClient/SonarrSearchRepositoryImpl).
        Timber.plant(Timber.DebugTree())

        // Must run before anything below that reads Profile/PVR config (PvrAdvancedSettings
        // provider, the scheduleX() calls, localControlServer) - a fresh install is a no-op here
        // since there are no User rows yet.
        // migrateLegacySeerrKeyNames() must run first: run() reads AppPreferences.seerrEnabled/
        // seerrBaseUrl, which only see a pre-rename install's value once this has copied it over.
        profileMigrationRunner.migrateLegacySeerrKeyNames()
        profileMigrationRunner.run()

        PvrAdvancedSettings.provider = { service ->
            val resolved = pvrConfigResolver.resolveConfig(service)
            PvrAdvancedConfig(
                headers = PvrAdvancedConfig.parseHeaders(resolved?.httpHeaders),
                basicAuthUsername = resolved?.basicAuthUsername,
                basicAuthPassword = resolved?.basicAuthPassword,
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val mode =
                when (appPreferences.getValue(appPreferences.theme)) {
                    "system" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        if (appPreferences.getValue(appPreferences.dynamicColors)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        val workManager = WorkManager.getInstance(applicationContext)

        scheduleUserDataSync(workManager)
        scheduleMpvCleanup(workManager)
        scheduleAutoDownload(workManager)
        schedulePendingDownloads(workManager)
        scheduleAutoDeleteWatched(workManager)
        scheduleNewItemNotifications(workManager)
        schedulePreloadCalendar(workManager)
        AutoBackupScheduler.schedule(applicationContext, appPreferences)
        QueueStatusScheduler.schedule(applicationContext, appPreferences, pvrConfigResolver)
        RemoteConfigScheduler.schedule(applicationContext)
        localControlServer.startIfEnabled()
        ContextCompat.registerReceiver(
            this,
            batterySaverReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        synchronizeBatterySaverDownloads()
        ForegroundDownloadResumer(downloader).start()
    }

    // BatterySaverReceiver only reacts to power-save mode changes. Reconcile both directions at
    // startup as the process may have been dead when the broadcast was sent.
    private fun synchronizeBatterySaverDownloads() {
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            BatterySaverReceiver.reconcile(
                powerManager.isPowerSaveMode,
                downloader,
                appPreferences,
            )
        }
    }

    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(cacheStrategy = { CacheControlCacheStrategy() }))
                add(SvgDecoder.Factory())
            }
            .diskCachePolicy(
                if (appPreferences.getValue(appPreferences.imageCache)) CachePolicy.ENABLED
                else CachePolicy.DISABLED
            )
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize) * 1024L * 1024
                    )
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private fun scheduleUserDataSync(workManager: WorkManager) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val syncWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "syncUserData",
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = syncWorkRequest,
        )
    }

    private fun checkIntervalMinutes(): Long =
        appPreferences
            .getValue(appPreferences.autoDownloadCheckIntervalMinutes)
            .coerceIn(15, 24 * 60)
            .toLong()

    private fun scheduleAutoDownload(workManager: WorkManager) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val periodicRequest =
            PeriodicWorkRequestBuilder<AutoDownloadWorker>(checkIntervalMinutes(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName = "autoDownloadRules",
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
            request = periodicRequest,
        )

        // Also evaluate once at startup, standing in for "after library refresh/sync" - there is
        // no dedicated library-refresh worker in this codebase to hook into.
        val startupRequest =
            OneTimeWorkRequestBuilder<AutoDownloadWorker>().setConstraints(constraints).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "autoDownloadRulesStartup",
            existingWorkPolicy = ExistingWorkPolicy.REPLACE,
            request = startupRequest,
        )
    }

    // Fixed 12h TTL, not user-configurable like the download-check interval - see
    // CalendarCache.DEFAULT_TTL_MILLIS, which PreloadCalendarWorker itself checks against.
    private fun schedulePreloadCalendar(workManager: WorkManager) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val periodicRequest =
            PeriodicWorkRequestBuilder<PreloadCalendarWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

        // KEEP rather than UPDATE - the interval is fixed, so there's no reason to reset the
        // periodic timer on every app start the way autoDownloadRules does for its
        // user-configurable interval.
        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName = "preloadCalendar",
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
            request = periodicRequest,
        )

        // Also fetch once at startup so a cold app open already has warm data by the time the
        // user opens the Calendar tab or a show's "next airing" - the worker itself skips the
        // actual fetch if the cache is still within its 12h TTL (e.g. a quick app restart).
        val startupRequest =
            OneTimeWorkRequestBuilder<PreloadCalendarWorker>().setConstraints(constraints).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "preloadCalendarStartup",
            existingWorkPolicy = ExistingWorkPolicy.REPLACE,
            request = startupRequest,
        )
    }

    private fun schedulePendingDownloads(workManager: WorkManager) {
        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val periodicRequest =
            PeriodicWorkRequestBuilder<PendingDownloadWorker>(
                    checkIntervalMinutes(),
                    TimeUnit.MINUTES,
                )
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName = "pendingDownloadRequests",
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
            request = periodicRequest,
        )

        // Also evaluate once at startup, standing in for "after library refresh/sync" - same
        // rationale as scheduleAutoDownload's startup request.
        val startupRequest =
            OneTimeWorkRequestBuilder<PendingDownloadWorker>().setConstraints(constraints).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "pendingDownloadRequestsStartup",
            existingWorkPolicy = ExistingWorkPolicy.REPLACE,
            request = startupRequest,
        )
    }

    private fun scheduleAutoDeleteWatched(workManager: WorkManager) {
        // Only keep this job scheduled while the feature is actually on - otherwise WorkManager
        // still wakes the process every interval just to run a worker that immediately no-ops.
        if (!appPreferences.getValue(appPreferences.autoDeleteWatched)) {
            workManager.cancelUniqueWork("autoDeleteWatched")
            return
        }

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val periodicRequest =
            PeriodicWorkRequestBuilder<AutoDeleteWatchedWorker>(
                    checkIntervalMinutes(),
                    TimeUnit.MINUTES,
                )
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName = "autoDeleteWatched",
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
            request = periodicRequest,
        )
    }

    private fun newItemNotificationsIntervalMinutes(): Long =
        appPreferences
            .getValue(appPreferences.newItemNotificationsCheckIntervalMinutes)
            .coerceIn(15, 24 * 60)
            .toLong()

    private fun scheduleNewItemNotifications(workManager: WorkManager) {
        // Only keep this job scheduled while the feature is actually on - otherwise WorkManager
        // still wakes the process every interval just to run a worker that immediately no-ops,
        // same reasoning as scheduleAutoDeleteWatched().
        if (!appPreferences.getValue(appPreferences.newItemNotificationsEnabled)) {
            workManager.cancelUniqueWork("newItemNotifications")
            return
        }

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        val periodicRequest =
            PeriodicWorkRequestBuilder<NewItemNotificationWorker>(
                    newItemNotificationsIntervalMinutes(),
                    TimeUnit.MINUTES,
                )
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName = "newItemNotifications",
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.UPDATE,
            request = periodicRequest,
        )
    }

    private fun scheduleMpvCleanup(workManager: WorkManager) {
        val constraints =
            Constraints.Builder().setRequiresDeviceIdle(true).setRequiresBatteryNotLow(true).build()

        val cleanupRequest =
            OneTimeWorkRequestBuilder<MpvCleanupWorker>().setConstraints(constraints).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "mpv_cleanup",
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = cleanupRequest,
        )
    }
}
