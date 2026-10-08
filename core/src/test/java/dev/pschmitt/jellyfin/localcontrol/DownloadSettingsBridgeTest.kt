package dev.pschmitt.jellyfin.localcontrol

import dev.pschmitt.jellyfin.security.FakeSharedPreferences
import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloadSettingsBridgeTest {
    private lateinit var appPreferences: AppPreferences

    @Before
    fun setUp() {
        appPreferences = AppPreferences(FakeSharedPreferences())
    }

    @Test
    fun `toJson reflects the real preference defaults`() {
        val json = DownloadSettingsBridge.toJson(appPreferences)

        assertEquals(false, json["downloadOverMobileData"]?.jsonPrimitive?.boolean)
        assertEquals(2, json["maxParallelDownloads"]?.jsonPrimitive?.int)
        assertEquals("ask", json["downloadLocation"]?.jsonPrimitive?.content)
    }

    @Test
    fun `applyPatch writes through to the real AppPreferences`() {
        val patch = buildJsonObject {
            put("downloadOverMobileData", true)
            put("maxParallelDownloads", 5)
        }

        DownloadSettingsBridge.applyPatch(appPreferences, patch)

        assertEquals(true, appPreferences.getValue(appPreferences.downloadOverMobileData))
        assertEquals(5, appPreferences.getValue(appPreferences.maxParallelDownloads))
    }

    @Test
    fun `applyPatch returns only the field names it actually applied`() {
        val patch = buildJsonObject {
            put("downloadOverMobileData", true)
            put("thisFieldDoesNotExist", true)
        }

        val applied = DownloadSettingsBridge.applyPatch(appPreferences, patch)

        assertEquals(listOf("downloadOverMobileData"), applied)
    }

    @Test
    fun `applyPatch ignores unknown keys without throwing`() {
        val patch = buildJsonObject { put("somethingFromANewerCli", "value") }

        val applied = DownloadSettingsBridge.applyPatch(appPreferences, patch)

        assertTrue(applied.isEmpty())
    }

    @Test
    fun `toJson after applyPatch reflects every one of the 12 fields round-tripping`() {
        val patch = buildJsonObject {
            put("downloadOverMobileData", true)
            put("downloadWhenRoaming", true)
            put("downloadLocation", "internal")
            put("autoDeleteWatched", true)
            put("autoDeleteWatchedHours", 48)
            put("autoDownloadCheckIntervalMinutes", 30)
            put("maxParallelDownloads", 4)
            put("pauseDownloadsOnBatterySaver", false)
            put("maxDownloadSizeEnabled", true)
            put("maxDownloadSizeGb", 50)
            put("playerPipOnHome", false)
            put("playerBackgroundPlayback", false)
        }

        DownloadSettingsBridge.applyPatch(appPreferences, patch)
        val json = DownloadSettingsBridge.toJson(appPreferences)

        assertEquals(patch, json)
    }
}
