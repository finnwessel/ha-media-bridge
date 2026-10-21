package de.finnwessel.mediabridge

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

data class Snapshot(
    val state: String,
    val app: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
) {
    /** Position ticks are not a change worth sending. */
    fun dedupKey() = listOf(state, app, title, artist).joinToString("|")

    fun toJson(): String = JSONObject().apply {
        put("state", state)
        put("app", app ?: JSONObject.NULL)
        put("title", title ?: JSONObject.NULL)
        put("artist", artist ?: JSONObject.NULL)
        put("duration_ms", durationMs ?: JSONObject.NULL)
        put("position_ms", positionMs ?: JSONObject.NULL)
        put("device", Build.MODEL)
        put("sent_at", System.currentTimeMillis())
    }.toString()
}

/**
 * Sends snapshots to the configured webhook on a single background thread.
 * Only the newest pending snapshot is kept, so a slow network never replays
 * stale states.
 */
object Publisher {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<Snapshot?>(null)

    fun send(context: Context, snapshot: Snapshot) {
        val url = Prefs.webhookUrl(context)
        if (url.isNullOrBlank()) {
            Log.w(MediaBridgeService.TAG, "no webhook URL configured, dropping ${snapshot.state}")
            return
        }
        if (pending.getAndSet(snapshot) == null) {
            executor.execute { drain(url) }
        }
    }

    private fun drain(url: String) {
        while (true) {
            val next = pending.getAndSet(null) ?: return
            post(url, next)
        }
    }

    private fun post(url: String, snapshot: Snapshot) {
        val body = snapshot.toJson().toByteArray()
        for (attempt in 1..3) {
            // A newer state arrived meanwhile: skip retrying the old one.
            if (attempt > 1 && pending.get() != null) return
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 3000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return
                Log.w(MediaBridgeService.TAG, "webhook answered $code (attempt $attempt)")
            } catch (e: Exception) {
                Log.w(MediaBridgeService.TAG, "webhook failed (attempt $attempt): ${e.message}")
            }
            Thread.sleep(500L * attempt)
        }
    }
}
