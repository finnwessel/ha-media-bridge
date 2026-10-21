package de.finnwessel.mediabridge

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

const val TAG = "MediaBridge"

/**
 * Core of the app. Bound by the system as a NotificationListenerService
 * (that is what grants access to other apps' media sessions; notifications
 * themselves are ignored) and therefore runs for as long as the device does.
 *
 * Collects media session state, screen state, volume and the foreground app,
 * pushes every relevant change to the Home Assistant webhook and serves the
 * local API for commands and notifications.
 */
class MediaBridgeService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private val controllers = LinkedHashMap<MediaSession.Token, Tracked>()
    private lateinit var audio: AudioManager
    private var server: HttpServer? = null
    private var discovery: Discovery? = null

    private var screenOn = true
    private var foreground: String? = null
    private var lastUsageQuery = 0L

    // What was last pushed, for de-duplication and seek detection.
    private var lastKey: String? = null
    private var lastPosition: Long? = null
    private var lastPositionAt = 0L
    private var lastState: String? = null

    private class Tracked(val controller: MediaController, val callback: MediaController.Callback)

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> syncSessions(list ?: emptyList()) }

    private val publishRunnable = Runnable { publish(force = false) }

    /** While playing: push the position every 30 s so HA can extrapolate and compute the end time. */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (lastState == "playing") publish(force = true)
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** UsageStats has no change listener; poll cheaply while the screen is on. */
    private val foregroundPoll = object : Runnable {
        override fun run() {
            if (screenOn) updateForeground()
            handler.postDelayed(this, FOREGROUND_POLL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> screenOn = true
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                else -> return schedulePublish() // volume change
            }
            Log.i(TAG, "screen ${if (screenOn) "on" else "off"}")
            publish(force = true)
        }
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = schedulePublish()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onListenerConnected() {
        Log.i(TAG, "listener connected (v${BuildConfig.VERSION_NAME})")
        instance = this
        audio = getSystemService(AudioManager::class.java)

        val manager = getSystemService(MediaSessionManager::class.java)
        sessionManager = manager
        val component = ComponentName(this, MediaBridgeService::class.java)
        manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
        syncSessions(manager.getActiveSessions(component))

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(VOLUME_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)

        val api = Api(this)
        server = HttpServer(Api.PORT, api::handle).also {
            runCatching { it.start() }.onFailure { e -> Log.e(TAG, "API failed to start: ${e.message}") }
        }
        discovery = Discovery(this).also {
            it.register(deviceName(), Api.PORT, Prefs.deviceId(this), BuildConfig.VERSION_NAME)
        }

        handler.post(heartbeat)
        handler.post(foregroundPoll)
        publish(force = true)
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "listener disconnected")
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        runCatching { unregisterReceiver(screenReceiver) }
        contentResolver.unregisterContentObserver(volumeObserver)
        controllers.values.forEach { it.controller.unregisterCallback(it.callback) }
        controllers.clear()
        handler.removeCallbacksAndMessages(null)
        server?.stop()
        server = null
        discovery?.unregister()
        discovery = null
        if (instance === this) instance = null
    }

    // ---------------------------------------------------------------- sessions

    private fun syncSessions(active: List<MediaController>) {
        val activeTokens = active.map { it.sessionToken }.toSet()
        controllers.keys.filter { it !in activeTokens }.forEach { token ->
            controllers.remove(token)?.let { it.controller.unregisterCallback(it.callback) }
            tracks.remove(token)
        }
        // Keep the system's priority order.
        val ordered = LinkedHashMap<MediaSession.Token, Tracked>()
        for (controller in active) {
            val tracked = controllers[controller.sessionToken] ?: Tracked(controller, callbackFor(controller)).also {
                controller.playbackState?.let { state -> observe(controller.sessionToken, state) }
                controller.registerCallback(it.callback, handler)
                Log.i(TAG, "tracking session ${controller.packageName}")
            }
            ordered[controller.sessionToken] = tracked
        }
        controllers.clear()
        controllers.putAll(ordered)
        schedulePublish()
    }

    private fun callbackFor(controller: MediaController) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let { observe(controller.sessionToken, it) }
            schedulePublish()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()
        override fun onSessionDestroyed() = schedulePublish()
    }

    /** The session that matters: playing first, then paused/buffering, then whatever the system ranks first. */
    private fun primary(): MediaController? {
        val all = controllers.values.map { it.controller }
        return all.firstOrNull { stateName(it.playbackState) == "playing" }
            ?: all.firstOrNull { stateName(it.playbackState) in setOf("paused", "buffering") }
            ?: all.firstOrNull()
    }

    // ---------------------------------------------------------------- publishing

    /** Coalesce bursts (e.g. paused → buffering → playing within a few ms). */
    private fun schedulePublish() {
        handler.removeCallbacks(publishRunnable)
        handler.postDelayed(publishRunnable, DEBOUNCE_MS)
    }

    fun publish(force: Boolean) {
        val snapshot = snapshot()
        val key = snapshot.dedupKey()
        if (!force && key == lastKey && !seeked(snapshot)) return
        lastKey = key
        lastState = snapshot.state
        lastPosition = snapshot.positionMs
        lastPositionAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "push state=${snapshot.state} app=${snapshot.app} pos=${snapshot.positionMs}")
        Publisher.send(Prefs.webhookUrl(this), snapshot.toJson(meta()).toString())
    }

    /** A jump of more than 3 s against the extrapolated position counts as a seek and is pushed at once. */
    private fun seeked(snapshot: Snapshot): Boolean {
        val now = snapshot.positionMs ?: return false
        val before = lastPosition ?: return false
        val expected = if (lastState == "playing") before + (SystemClock.elapsedRealtime() - lastPositionAt) else before
        return abs(now - expected) > 3000
    }

    private fun meta() = mapOf("id" to Prefs.deviceId(this), "version" to BuildConfig.VERSION_NAME)

    fun snapshotJson(): JSONObject = snapshot().toJson(meta() + ("webhook_url" to Prefs.webhookUrl(this)))

    private fun snapshot(): Snapshot {
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / maxVolume * 100).roundToInt() / 100.0
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val base = Snapshot(
            state = if (screenOn) "idle" else "off",
            screenOn = screenOn,
            foregroundApp = if (screenOn) foreground else null,
            foregroundLabel = if (screenOn) foreground?.let(::label) else null,
            volume = volume,
            muted = muted,
        )
        if (!screenOn) return base
        val controller = primary() ?: return base
        val playback = controller.playbackState
        val meta = controller.metadata
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        return base.copy(
            state = stateName(playback),
            app = controller.packageName,
            appLabel = label(controller.packageName),
            title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = duration,
            positionMs = playback?.let { currentPosition(controller.sessionToken, it, duration) },
            canSeek = playback != null && (playback.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
        )
    }

    /**
     * Per-session correction for apps that keep a stale lastPositionUpdateTime
     * while playing (YouTube on Android TV). The system extrapolates every
     * PLAYING state from that stamp and hands it to us freshly stamped, so the
     * error can't be seen in a single report. Paused/buffering positions are not
     * extrapolated and therefore right: remember the last one, and on the switch
     * to PLAYING measure how far the system's value is ahead of it. That offset
     * is subtracted until playback stops again. Well-behaved apps get offset 0.
     */
    private class PositionTrack {
        var lastStillPosition: Long? = null
        var playing = false
        var offset = 0L
    }

    private val tracks = HashMap<MediaSession.Token, PositionTrack>()

    private fun observe(token: MediaSession.Token, state: PlaybackState) {
        val track = tracks.getOrPut(token) { PositionTrack() }
        if (stateName(state) != "playing") {
            if (state.position >= 0) track.lastStillPosition = state.position
            track.playing = false
            return
        }
        if (track.playing) return
        track.playing = true
        val still = track.lastStillPosition
        val now = systemPosition(state)
        val ahead = if (still != null && now != null) now - still else 0L
        // Up to 3 s ahead is normal (time between the reports); only correct beyond that.
        track.offset = if (ahead > 3_000) ahead else 0L
        if (track.offset > 0) Log.i(TAG, "position correction: system is ${track.offset} ms ahead")
    }

    /** Position as the system reports it, extrapolated to now. */
    private fun systemPosition(state: PlaybackState): Long? {
        if (state.position < 0) return null
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0) return state.position
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0)
        return state.position + (elapsed * state.playbackSpeed.coerceAtLeast(0f)).toLong()
    }

    private fun currentPosition(token: MediaSession.Token, state: PlaybackState, durationMs: Long?): Long? {
        val system = systemPosition(state) ?: return null
        val offset = if (stateName(state) == "playing") tracks[token]?.offset ?: 0L else 0L
        val position = (system - offset).coerceAtLeast(0)
        return if (durationMs != null) position.coerceAtMost(durationMs) else position
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

    // ---------------------------------------------------------------- foreground app

    private fun updateForeground() {
        if (!hasUsageAccess()) return
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val from = if (lastUsageQuery == 0L) now - 60 * 60 * 1000 else lastUsageQuery - 1000
        lastUsageQuery = now
        val events = usm.queryEvents(from, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName
        }
        if (latest == null && foreground == null) {
            // App opened before the window we can see events for: take the most recently used one.
            latest = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 24 * 60 * 60 * 1000, now)
                ?.filter { it.lastTimeUsed > 0 && it.packageName != packageName }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
        }
        if (latest != null && latest != foreground) {
            foreground = latest
            schedulePublish()
        }
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private val labels = HashMap<String, String>()

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }

    // ---------------------------------------------------------------- commands

    fun runCommand(name: String, args: JSONObject): Boolean {
        Log.i(TAG, "command $name")
        when (name) {
            "volume_set" -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val level = (args.optDouble("level", -1.0)).takeIf { it in 0.0..1.0 } ?: return false
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt(), 0)
                return true
            }
            "volume_up" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                return true
            }
            "volume_down" -> {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                return true
            }
            "mute" -> {
                val direction = if (args.optBoolean("muted", true)) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                return true
            }
        }
        val controller = primary() ?: return false
        val controls = controller.transportControls
        when (name) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "play_pause" ->
                if (stateName(controller.playbackState) == "playing") controls.pause() else controls.play()
            "stop" -> controls.stop()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
            "seek" -> {
                val position = args.optLong("position_ms", -1).takeIf { it >= 0 } ?: return false
                controls.seekTo(position)
            }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- misc

    fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val HEARTBEAT_MS = 30_000L
        private const val FOREGROUND_POLL_MS = 3_000L
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        @Volatile
        var instance: MediaBridgeService? = null
            private set
    }
}
