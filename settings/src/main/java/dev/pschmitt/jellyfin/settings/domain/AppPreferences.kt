package dev.pschmitt.jellyfin.settings.domain

import android.content.SharedPreferences
import dev.pschmitt.jellyfin.settings.domain.models.Preference
import java.util.UUID
import javax.inject.Inject
import timber.log.Timber

class AppPreferences @Inject constructor(val sharedPreferences: SharedPreferences) {
    // Server
    val currentServer = Preference<String?>("pref_current_server", null)

    // Profiles
    val currentProfileId = Preference<String?>("pref_current_profile", null)
    val profilesMigrated = Preference("pref_profiles_migrated", false)

    // Language
    val preferredAudioLanguage = Preference<String?>("pref_audio_language", null)
    val preferredSubtitleLanguage = Preference<String?>("pref_subtitle_language", null)

    // Interface
    val theme = Preference("pref_theme", "system")
    val dynamicColors = Preference("pref_dynamic_colors", true)
    val homeSuggestions = Preference<Boolean>("home_suggestions", true)
    val homeContinueWatching = Preference<Boolean>("home_continue_watching", true)
    val homeNextUp = Preference<Boolean>("home_next_up", true)
    val homeFavorites = Preference<Boolean>("home_favorites", true)
    val homeLatest = Preference<Boolean>("home_latest", true)
    val homeDiscover = Preference<Boolean>("home_discover", true)
    val homeSectionOrder = Preference<String?>("pref_home_section_order", null)
    val homeHiddenSections = Preference<String?>("pref_home_hidden_sections", null)
    // Optional navbar destinations are hidden by default so upgrades retain the existing navbar.
    val navigationBarOrder = Preference<String?>("pref_navigation_bar_order", null)
    val navigationBarHiddenItems =
        Preference<String?>("pref_navigation_bar_hidden_items", "favorites,next_up,settings")
    val navigationBarPinnedItems = Preference<String?>("pref_navigation_bar_pinned_items", null)
    val dateFormat = Preference("pref_date_format", "system")

    // Player
    val playerBackend = Preference("pref_player_backend", "exoplayer")
    val playerBrightness = Preference("pref_player_brightness", -1.0f)

    // Player - mpv
    val playerMpvHwdec = Preference("pref_player_mpv_hwdec", "mediacodec")
    val playerMpvVo = Preference("pref_player_mpv_vo", "gpu-next")
    val playerMpvAo = Preference("pref_player_mpv_ao", "aaudio")

    // Player - gestures
    val playerGestures = Preference("pref_player_gestures", true)
    val playerGesturesVB = Preference("pref_player_gestures_vb", true)
    val playerGesturesZoom = Preference("pref_player_gestures_zoom", true)
    val playerGesturesSeek = Preference("pref_player_gestures_seek", true)
    val playerGesturesSeekTrickplay = Preference("pref_player_gestures_seek_trickplay", true)
    val playerGesturesChapterSkip = Preference("pref_player_gestures_chapter_skip", true)
    val playerGesturesBrightnessRemember = Preference("pref_player_brightness_remember", false)
    val playerGesturesStartMaximized = Preference("pref_player_start_maximized", false)

    // Player - seeking
    val playerSeekBackInc = Preference("pref_player_seek_back_inc", 5_000L)
    val playerSeekForwardInc = Preference("pref_player_seek_forward_inc", 15_000L)

    // Player - Media Segments
    val playerMediaSegmentsSkipButton
        get() = Preference("pref_player_media_segments_skip_button", true)

    val playerMediaSegmentsSkipButtonType
        get() = Preference("pref_player_media_segments_skip_button_type", setOf("INTRO", "OUTRO"))

    val playerMediaSegmentsSkipButtonDuration
        get() = Preference("pref_player_media_segments_skip_button_duration", 5L)

    val playerMediaSegmentsAutoSkip
        get() = Preference("pref_player_media_segments_auto_skip", false)

    val playerMediaSegmentsAutoSkipMode
        get() =
            Preference(
                "pref_player_media_segments_auto_skip_mode",
                Constants.PlayerMediaSegmentsAutoSkip.ALWAYS,
            )

    val playerMediaSegmentsAutoSkipType
        get() = Preference("pref_player_media_segments_auto_skip_type", setOf("INTRO", "OUTRO"))

    val playerMediaSegmentsNextEpisodeThreshold
        get() = Preference("pref_player_media_segments_next_episode_threshold", 5_000L)

    // Player - trickplay
    val playerTrickplay = Preference("pref_player_trickplay", true)

    // Player - PiP
    val playerPipGesture = Preference("pref_player_picture_in_picture_gesture", false)

    // Downloads
    val downloadOverMobileData = Preference("pref_downloads_mobile_data", false)
    val downloadWhenRoaming = Preference("pref_downloads_roaming", false)
    val downloadLocation = Preference("pref_downloads_location", "ask")
    val autoDeleteWatched = Preference("pref_downloads_auto_delete_watched", false)
    val autoDeleteWatchedHours = Preference("pref_downloads_auto_delete_watched_hours", 24)
    val autoDownloadCheckIntervalMinutes =
        Preference("pref_downloads_auto_check_interval_minutes", 2 * 60)
    val maxParallelDownloads = Preference("pref_downloads_max_parallel", 2)
    val pauseDownloadsOnBatterySaver = Preference("pref_downloads_pause_on_battery_saver", true)
    // Optional overall cap on total downloaded content, in GiB - off by default. Only gates
    // automatic downloads (AutoDownloadRuleEvaluator/PendingDownloadFulfiller); manual downloads
    // started from the app are never blocked by this.
    val maxDownloadSizeEnabled = Preference("pref_downloads_max_size_enabled", false)
    val maxDownloadSizeGb = Preference("pref_downloads_max_size_gb", 20)

    // Notifications - new items (movies/episodes) added to the Jellyfin library. Off by default:
    // unlike auto-download (an explicit opt-in rule the user configures per show), this checks
    // the whole library unconditionally, so it shouldn't start posting notifications for anyone
    // who hasn't deliberately turned it on.
    val newItemNotificationsEnabled = Preference("pref_new_item_notifications_enabled", false)
    val newItemNotificationsCheckIntervalMinutes =
        Preference("pref_new_item_notifications_check_interval_minutes", 60)
    // Bookkeeping for NewItemNotificationWorker's diff, deliberately kept here rather than in a
    // new Room table/column - see that worker's kdoc for why.
    val newItemNotificationsLastCheckMillis =
        Preference("pref_new_item_notifications_last_check_millis", 0L)
    val newItemNotificationsSeenItemIds =
        Preference<String?>("pref_new_item_notifications_seen_item_ids", null)

    // Backup
    val autoBackupEnabled = Preference("pref_backup_auto_enabled", false)
    val autoBackupIntervalMinutes = Preference("pref_backup_auto_interval_minutes", 24 * 60)
    val autoBackupFolderUri = Preference<String?>("pref_backup_auto_folder_uri", null)
    // Used to encrypt scheduled auto-backups, same as the optional password typed for a manual
    // "Back up now" export. Left blank, auto-backups are unencrypted.
    val autoBackupPassword = Preference<String?>("pref_backup_auto_password", null)
    val lastBackupTimestamp = Preference("pref_backup_last_timestamp", 0L)
    // Short human-readable reason the most recent scheduled auto-backup failed, set by
    // AutoBackupWorker on every failure path and cleared (null) on success. Manual "Back up now"
    // failures already surface through a snackbar and don't touch this - it exists specifically
    // to make background-job failures visible in the Backup & Restore screen, since those would
    // otherwise fail silently forever (WorkManager just retries on the next period).
    val autoBackupLastError = Preference<String?>("pref_backup_last_error", null)
    val pendingRestoreDownloads = Preference<String?>("pref_backup_pending_restore_downloads", null)

    // Network
    val requestTimeout =
        Preference("pref_network_request_timeout", Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT)
    val connectTimeout =
        Preference("pref_network_connect_timeout", Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT)
    val socketTimeout =
        Preference("pref_network_socket_timeout", Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT)
    val pvrSearchTimeout =
        Preference("pref_network_pvr_search_timeout", Constants.NETWORK_DEFAULT_PVR_SEARCH_TIMEOUT)

    // Cache
    val imageCache = Preference("pref_image_cache", true)
    val imageCacheSize = Preference("pref_image_cache_size", Constants.DEFAULT_IMAGE_CACHE_SIZE_MB)

    // Sorting
    val sortBy = Preference("pref_sort_by", "SortName")
    val sortOrder = Preference("pref_sort_order", "Ascending")

    // Offline mode
    val offlineMode = Preference("pref_offline_mode", false)

    // PVR (Sonarr/Radarr) - the API keys are secrets and are stored separately, through
    // SecureCredentialStore, not here.
    val sonarrEnabled = Preference("pref_pvr_sonarr_enabled", false)
    val sonarrBaseUrl = Preference<String?>("pref_pvr_sonarr_base_url", null)
    val radarrEnabled = Preference("pref_pvr_radarr_enabled", false)
    val radarrBaseUrl = Preference<String?>("pref_pvr_radarr_base_url", null)
    val pvrPollIntervalMinutes = Preference("pref_pvr_poll_interval_minutes", 15)
    val pvrReleaseCacheMinutes = Preference("pref_pvr_release_cache_minutes", 15)

    // Seerr (media requests, formerly Jellyseerr) - same secret-handling split as Sonarr/Radarr
    // above. Renamed from the pre-rebrand "pref_pvr_jellyseerr_*" keys - see
    // ProfileMigrationRunner.migrateLegacySeerrKeyNames() for the one-time copy that preserves
    // already-persisted values (on-device and inside older backups/QR exports) under these names.
    val seerrEnabled = Preference("pref_pvr_seerr_enabled", false)
    val seerrBaseUrl = Preference<String?>("pref_pvr_seerr_base_url", null)

    // Remote config (cross-device auto-download rule push, see RemoteConfigRepository) - an
    // opaque per-install identifier, generated lazily on first use rather than at install time so
    // no migration/onCreate hook is needed for it.
    val thisDeviceId = Preference<String?>("pref_this_device_id", null)

    fun getOrCreateThisDeviceId(): String {
        getValue(thisDeviceId)?.let {
            return it
        }
        val id = UUID.randomUUID().toString()
        setValue(thisDeviceId, id)
        return id
    }

    // Per-device opt-out: whether this device allows *other* devices on the same account to
    // manage it (push rules/downloads to it, list it as a target, publish its active rules to the
    // shared registry). Does not affect this device's own ability to push to others - that's this
    // device's own action, not something done to it without consent. Defaults on to match the
    // feature's existing default-enabled posture; RemoteConfigRepository.setRemoteManagementEnabled
    // is what actually acts on a change (self-removal from the shared registry), not this pref
    // alone.
    val remoteManagementEnabled = Preference("pref_remote_management_enabled", true)

    // Local control API (see core/.../localcontrol/LocalControlServer) - lets a paired local CLI
    // (e.g. jollyfin-cli in Termux) configure this actual running app instance: read/write real
    // download settings, trigger real downloads, and debug-proxy to Jellyfin/Sonarr/Radarr/Seerr.
    // Off by default - unlike remoteManagementEnabled (an existing cross-device feature getting an
    // opt-out), this is a brand new local capability that should stay dormant until deliberately
    // turned on.
    val localControlEnabled = Preference("pref_local_control_enabled", false)

    inline fun <reified T> getValue(preference: Preference<T>): T {
        return try {
            @Suppress("UNCHECKED_CAST")
            when (preference.defaultValue) {
                is Boolean ->
                    sharedPreferences.getBoolean(preference.backendName, preference.defaultValue)
                        as T
                is Int ->
                    sharedPreferences.getInt(preference.backendName, preference.defaultValue) as T
                is Long ->
                    sharedPreferences.getLong(preference.backendName, preference.defaultValue) as T
                is Float ->
                    sharedPreferences.getFloat(preference.backendName, preference.defaultValue) as T
                is String? ->
                    sharedPreferences.getString(preference.backendName, preference.defaultValue)
                        as T
                is Set<*> ->
                    sharedPreferences.getStringSet(
                        preference.backendName,
                        preference.defaultValue as Set<String>,
                    ) as T
                else -> preference.defaultValue
            }
        } catch (_: Exception) {
            Timber.w(
                "Failed to load ${preference.backendName} preference. Resetting to default value..."
            )
            setValue(preference, preference.defaultValue)
            preference.defaultValue
        }
    }

    inline fun <reified T> setValue(preference: Preference<T>, value: T) {
        val editor = sharedPreferences.edit()
        @Suppress("UNCHECKED_CAST")
        when (preference.defaultValue) {
            is Boolean -> editor.putBoolean(preference.backendName, value as Boolean)
            is Int -> editor.putInt(preference.backendName, value as Int)
            is Long -> editor.putLong(preference.backendName, value as Long)
            is Float -> editor.putFloat(preference.backendName, value as Float)
            is String? -> editor.putString(preference.backendName, value as String?)
            is Set<*> -> editor.putStringSet(preference.backendName, value as Set<String>)
            else -> throw Exception()
        }
        editor.apply()
    }
}
