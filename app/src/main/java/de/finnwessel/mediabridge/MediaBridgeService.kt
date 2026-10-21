package de.finnwessel.mediabridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Listens to Android media sessions and pushes every playback change to a
 * Home Assistant webhook. Runs as a NotificationListenerService only because
 * that is what grants access to other apps' media sessions; notifications
 * themselves are ignored.
 */
class MediaBridgeService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val controllers = LinkedHashMap<MediaSession.Token, Tracked>()
    private var screenOn = true
    private var lastKey: String? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> syncSessions(list ?: emptyList()) }

    private val publishRunnable = Runnable { publish(force = false) }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            Log.i(TAG, "screen ${if (screenOn) "on" else "off"}")
            publish(force = true)
        }
    }

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected")
        instance = this
        val manager = getSystemService(MediaSessionManager::class.java)
        sessionManager = manager
        val component = ComponentName(this, MediaBridgeService::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        syncSessions(manager.getActiveSessions(component))

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        publish(force = true)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "listener disconnected")
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        runCatching { unregisterReceiver(screenReceiver) }
        controllers.values.forEach { it.controller.unregisterCallback(it.callback) }
        controllers.clear()
        handler.removeCallbacks(publishRunnable)
        if (instance === this) instance = null
    }

    private fun syncSessions(active: List<MediaController>) {
        val activeTokens = active.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in activeTokens }.forEach { token ->
            controllers.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
        }
        // Rebuild in priority order as reported by the system.
        val ordered = LinkedHashMap<MediaSession.Token, Tracked>()
        for (controller in active) {
            val tracked = controllers[controller.sessionToken] ?: Tracked(controller, callbackFor()).also {
                controller.registerCallback(it.callback, handler)
                Log.i(TAG, "tracking session ${controller.packageName}")
            }
            ordered[controller.sessionToken] = tracked
        }
        controllers.clear()
        controllers.putAll(ordered)
        schedulePublish()
    }

    private fun callbackFor() = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = schedulePublish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()
        override fun onSessionDestroyed() = schedulePublish()
    }

    /** Coalesce bursts (e.g. paused → buffering → playing within a few ms). */
    private fun schedulePublish() {
        handler.removeCallbacks(publishRunnable)
        handler.postDelayed(publishRunnable, DEBOUNCE_MS)
    }

    fun publish(force: Boolean) {
        val snapshot = currentSnapshot()
        val key = snapshot.dedupKey()
        if (!force && key == lastKey) return
        lastKey = key
        Log.i(TAG, "state=${snapshot.state} app=${snapshot.app} title=${snapshot.title}")
        Publisher.send(this, snapshot)
    }

    private fun currentSnapshot(): Snapshot {
        if (!screenOn) return Snapshot(state = "off")
        val all = controllers.values.map { it.controller }
        val primary = all.firstOrNull { stateName(it.playbackState) == "playing" }
            ?: all.firstOrNull { stateName(it.playbackState) in setOf("paused", "buffering") }
            ?: all.firstOrNull()
            ?: return Snapshot(state = "idle")

        val playback = primary.playbackState
        val meta = primary.metadata
        return Snapshot(
            state = stateName(playback),
            app = primary.packageName,
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            positionMs = playback?.position?.takeIf { it >= 0 },
        )
    }

    private fun stateName(state: PlaybackState?): String = when (state?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "playing"
        PlaybackState.STATE_PAUSED -> "paused"
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING -> "buffering"
        else -> "stopped"
    }

    companion object {
        const val TAG = "MediaBridge"
        private const val DEBOUNCE_MS = 250L

        @Volatile
        var instance: MediaBridgeService? = null
            private set
    }
}
