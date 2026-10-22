package de.finnwessel.mediabridge

import org.json.JSONObject

/** Everything Home Assistant gets to know about the device at one moment. */
data class Snapshot(
    val state: String, // off | idle | stopped | buffering | paused | playing
    val screenOn: Boolean,
    val app: String? = null,
    val appLabel: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val canSeek: Boolean = false,
    val foregroundApp: String? = null,
    val foregroundLabel: String? = null,
    val volume: Double? = null,
    val muted: Boolean? = null,
    val artworkId: String? = null,
    val artworkUrl: String? = null,
    val nextTitle: String? = null,
    val audioOutput: String? = null,
    val audioOutputName: String? = null,
    val dreaming: Boolean = false,
    val pictureMode: Int? = null,
    val eyeProtector: Int? = null,
    val memc: Int? = null,
    val soundMode: Int? = null,
    val distanceMm: Int? = null,
    val canSleep: Boolean = false,
) {
    /** Changes worth an immediate push. Position is handled separately (seek detection + heartbeat). */
    fun dedupKey() = listOf(
        state, screenOn, app, title, artist, album, durationMs, canSeek, foregroundApp, volume, muted, artworkId,
        nextTitle, audioOutput, audioOutputName, dreaming, pictureMode, eyeProtector, memc, soundMode, canSleep,
    ).joinToString("|")

    fun toJson(extra: Map<String, Any?> = emptyMap()): JSONObject = JSONObject().apply {
        put("state", state)
        put("screen_on", screenOn)
        put("app", app.orNull())
        put("app_label", appLabel.orNull())
        put("title", title.orNull())
        put("artist", artist.orNull())
        put("album", album.orNull())
        put("duration_ms", durationMs.orNull())
        put("position_ms", positionMs.orNull())
        put("can_seek", canSeek)
        put("foreground_app", foregroundApp.orNull())
        put("foreground_label", foregroundLabel.orNull())
        put("volume", volume.orNull())
        put("muted", muted.orNull())
        put("artwork_id", artworkId.orNull())
        put("artwork_url", artworkUrl.orNull())
        put("next_title", nextTitle.orNull())
        put("audio_output", audioOutput.orNull())
        put("audio_output_name", audioOutputName.orNull())
        put("dreaming", dreaming)
        put("picture_mode", pictureMode.orNull())
        put("eye_protector", eyeProtector.orNull())
        put("memc", memc.orNull())
        put("sound_mode", soundMode.orNull())
        put("distance_mm", distanceMm.orNull())
        put("can_sleep", canSleep)
        put("sent_at", System.currentTimeMillis())
        extra.forEach { (k, v) -> put(k, v.orNull()) }
    }

    private fun Any?.orNull(): Any = this ?: JSONObject.NULL
}
