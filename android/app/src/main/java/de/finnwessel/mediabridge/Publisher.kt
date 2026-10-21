package de.finnwessel.mediabridge

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Pushes state to the Home Assistant webhook on a single background thread.
 * Only the newest pending payload is kept, so a slow network never replays
 * stale states.
 */
object Publisher {
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicReference<Pair<String, String>?>(null)

    fun send(url: String?, json: String) {
        if (url.isNullOrBlank()) {
            Log.w(TAG, "no webhook configured, not pushing")
            return
        }
        if (pending.getAndSet(url to json) == null) {
            executor.execute { drain() }
        }
    }

    private fun drain() {
        while (true) {
            val (url, body) = pending.getAndSet(null) ?: return
            post(url, body.toByteArray())
        }
    }

    private fun post(url: String, body: ByteArray) {
        for (attempt in 1..3) {
            // A newer state arrived meanwhile: don't retry the old one.
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
                Log.w(TAG, "webhook answered $code (attempt $attempt)")
            } catch (e: Exception) {
                Log.w(TAG, "webhook failed (attempt $attempt): ${e.message}")
            }
            if (attempt < 3) Thread.sleep(500L * attempt)
        }
    }
}
