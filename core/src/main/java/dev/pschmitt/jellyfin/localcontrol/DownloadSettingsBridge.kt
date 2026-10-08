package dev.pschmitt.jellyfin.localcontrol

import dev.pschmitt.jellyfin.settings.domain.AppPreferences
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

/**
 * Bridges the local control API's `GET`/`PATCH /settings/downloads` to the real, typed
 * [AppPreferences] download settings (plus a couple of player toggles).
 * [AppPreferences.getValue]/[AppPreferences.setValue] are `inline reified`, so they can't be called
 * generically from a string field name at runtime - each [Field] entry below pins the concrete type
 * at its own call site instead, the same way any other direct caller of `getValue`/`setValue` in
 * this codebase already does.
 */
object DownloadSettingsBridge {
    private data class Field(
        val name: String,
        val get: (AppPreferences) -> JsonElement,
        val set: (AppPreferences, JsonElement) -> Unit,
    )

    private val fields: List<Field> =
        listOf(
            Field(
                "downloadOverMobileData",
                { JsonPrimitive(it.getValue(it.downloadOverMobileData)) },
                { ap, v -> ap.setValue(ap.downloadOverMobileData, v.jsonPrimitiveBoolean()) },
            ),
            Field(
                "downloadWhenRoaming",
                { JsonPrimitive(it.getValue(it.downloadWhenRoaming)) },
                { ap, v -> ap.setValue(ap.downloadWhenRoaming, v.jsonPrimitiveBoolean()) },
            ),
            Field(
                "downloadLocation",
                { JsonPrimitive(it.getValue(it.downloadLocation)) },
                { ap, v -> ap.setValue(ap.downloadLocation, v.jsonPrimitiveString()) },
            ),
            Field(
                "autoDeleteWatched",
                { JsonPrimitive(it.getValue(it.autoDeleteWatched)) },
                { ap, v -> ap.setValue(ap.autoDeleteWatched, v.jsonPrimitiveBoolean()) },
            ),
            Field(
                "autoDeleteWatchedHours",
                { JsonPrimitive(it.getValue(it.autoDeleteWatchedHours)) },
                { ap, v -> ap.setValue(ap.autoDeleteWatchedHours, v.jsonPrimitiveInt()) },
            ),
            Field(
                "autoDownloadCheckIntervalMinutes",
                { JsonPrimitive(it.getValue(it.autoDownloadCheckIntervalMinutes)) },
                { ap, v -> ap.setValue(ap.autoDownloadCheckIntervalMinutes, v.jsonPrimitiveInt()) },
            ),
            Field(
                "maxParallelDownloads",
                { JsonPrimitive(it.getValue(it.maxParallelDownloads)) },
                { ap, v -> ap.setValue(ap.maxParallelDownloads, v.jsonPrimitiveInt()) },
            ),
            Field(
                "pauseDownloadsOnBatterySaver",
                { JsonPrimitive(it.getValue(it.pauseDownloadsOnBatterySaver)) },
                { ap, v -> ap.setValue(ap.pauseDownloadsOnBatterySaver, v.jsonPrimitiveBoolean()) },
            ),
            Field(
                "maxDownloadSizeEnabled",
                { JsonPrimitive(it.getValue(it.maxDownloadSizeEnabled)) },
                { ap, v -> ap.setValue(ap.maxDownloadSizeEnabled, v.jsonPrimitiveBoolean()) },
            ),
            Field(
                "maxDownloadSizeGb",
                { JsonPrimitive(it.getValue(it.maxDownloadSizeGb)) },
                { ap, v -> ap.setValue(ap.maxDownloadSizeGb, v.jsonPrimitiveInt()) },
            ),
            // Not download settings, but the CLI's `settings` command is this endpoint's only
            // consumer and these are the player toggles worth scripting.
            Field(
                "playerPipOnHome",
                { JsonPrimitive(it.getValue(it.playerPipGesture)) },
                { ap, v -> ap.setValue(ap.playerPipGesture, v.jsonPrimitiveBoolean()) },
            ),
            Field(
                "playerBackgroundPlayback",
                { JsonPrimitive(it.getValue(it.playerBackgroundPlayback)) },
                { ap, v -> ap.setValue(ap.playerBackgroundPlayback, v.jsonPrimitiveBoolean()) },
            ),
        )

    fun toJson(appPreferences: AppPreferences): JsonObject = buildJsonObject {
        fields.forEach { field -> put(field.name, field.get(appPreferences)) }
    }

    /**
     * Applies every key present in [patch], returning the names actually applied. Unknown keys are
     * ignored (forward-compatible with a CLI newer than the app) rather than rejecting the whole
     * patch over one unrecognized field.
     */
    fun applyPatch(appPreferences: AppPreferences, patch: JsonObject): List<String> {
        val applied = mutableListOf<String>()
        patch.forEach { (name, value) ->
            val field = fields.find { it.name == name } ?: return@forEach
            field.set(appPreferences, value)
            applied += name
        }
        return applied
    }

    private fun JsonElement.jsonPrimitiveBoolean(): Boolean = (this as JsonPrimitive).boolean

    private fun JsonElement.jsonPrimitiveInt(): Int = (this as JsonPrimitive).int

    private fun JsonElement.jsonPrimitiveString(): String = (this as JsonPrimitive).content
}
