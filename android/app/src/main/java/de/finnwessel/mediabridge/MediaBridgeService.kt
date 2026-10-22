package de.finnwessel.mediabridge

import android.app.AppOpsManager
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageEvents
import android.bluetooth.BluetoothDevice
import android.hardware.display.DisplayManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
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
import android.net.Uri
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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

    /**
     * Sessions that dropped out of the active list but still exist. YouTube
     * marks its paused session inactive when the screen goes off and doesn't
     * reactivate it on wake, although the video is still open and paused.
     */
    private val retained = LinkedHashMap<MediaSession.Token, Tracked>()
    private lateinit var audio: AudioManager
    private var server: HttpServer? = null
    private var discovery: Discovery? = null

    private var screenOn = true
    private var dreaming = false
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
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    dreaming = false
                }
                Intent.ACTION_DREAMING_STARTED -> dreaming = true
                Intent.ACTION_DREAMING_STOPPED -> dreaming = false
                else -> return schedulePublish() // volume or Bluetooth change
            }
            Log.i(TAG, "screen ${if (screenOn) "on" else "off"}")
            publish(force = true)
        }
    }

    private val volumeObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = schedulePublish()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = schedulePublish()
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = schedulePublish()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = schedulePublish()
    }

    // ---------------------------------------------------------------- media notifications

    /**
     * Title/text of each app's media notification. Fallback for apps that
     * publish a session without metadata but show what's playing in their
     * notification.
     */
    private val mediaNotifications = HashMap<String, Pair<String?, String?>>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::rememberNotification)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && mediaNotifications.remove(sbn.packageName) != null) schedulePublish()
    }

    private fun rememberNotification(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val extras = n.extras ?: return
        val isMedia = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true ||
            n.category == Notification.CATEGORY_TRANSPORT
        if (!isMedia) return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        if (mediaNotifications.put(sbn.packageName, title to text) != (title to text)) schedulePublish()
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
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(VOLUME_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BATTERY_LEVEL_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        Projector.WATCHED.forEach {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(it), false, volumeObserver)
        }
        audio.registerAudioDeviceCallback(audioDeviceCallback, handler)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        runCatching { activeNotifications?.forEach(::rememberNotification) }

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
        audio.unregisterAudioDeviceCallback(audioDeviceCallback)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        (controllers.values + retained.values).forEach { it.controller.unregisterCallback(it.callback) }
        controllers.clear()
        retained.clear()
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
            controllers.remove(token)?.let { retained[token] = it }
        }
        while (retained.size > MAX_RETAINED) {
            val oldest = retained.keys.first()
            forget(oldest)
        }
        // Keep the system's priority order.
        val ordered = LinkedHashMap<MediaSession.Token, Tracked>()
        for (controller in active) {
            val tracked = controllers[controller.sessionToken] ?: retained.remove(controller.sessionToken)
                ?: Tracked(controller, callbackFor(controller)).also {
                controller.playbackState?.let { state -> observe(controller, state) }
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
            state?.let { observe(controller, it) }
            schedulePublish()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = schedulePublish()
        override fun onSessionDestroyed() {
            forget(controller.sessionToken)
            schedulePublish()
        }
    }

    private fun forget(token: MediaSession.Token) {
        (controllers.remove(token) ?: retained.remove(token))?.let { it.controller.unregisterCallback(it.callback) }
        tracks.remove(token)
    }

    /**
     * The session that matters: playing first, then paused/buffering, then
     * whatever the system ranks first. Inactive (retained) sessions count when
     * playing, or when paused in the app that is in the foreground.
     */
    private fun primary(): MediaController? {
        val active = controllers.values.map { it.controller }
        val inactive = retained.values.map { it.controller }.reversed() // newest first
        val still = setOf("paused", "buffering")
        return active.firstOrNull { stateName(it.playbackState) == "playing" }
            ?: inactive.firstOrNull { stateName(it.playbackState) == "playing" }
            ?: active.firstOrNull { stateName(it.playbackState) in still }
            ?: inactive.firstOrNull {
                stateName(it.playbackState) in still && (foreground == null || it.packageName == foreground)
            }
            ?: active.firstOrNull()
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
        Publisher.send(Prefs.webhookUrl(this), snapshot.toJson(meta() + diagnostics()).toString())
    }

    /** A jump of more than 3 s against the extrapolated position counts as a seek and is pushed at once. */
    private fun seeked(snapshot: Snapshot): Boolean {
        val now = snapshot.positionMs ?: return false
        val before = lastPosition ?: return false
        val expected = if (lastState == "playing") before + (SystemClock.elapsedRealtime() - lastPositionAt) else before
        return abs(now - expected) > 3000
    }

    /** Push a one-off event (key press, notification action); never coalesced with state. */
    fun pushEvent(fields: Map<String, Any?>) {
        val json = JSONObject(meta() + fields)
        Publisher.sendEvent(Prefs.webhookUrl(this), json.toString())
    }

    private fun meta() = mapOf("id" to Prefs.deviceId(this), "version" to BuildConfig.VERSION_NAME)

    fun snapshotJson(): JSONObject =
        snapshot().toJson(meta() + diagnostics() + ("webhook_url" to Prefs.webhookUrl(this)))

    private fun diagnostics(): Map<String, Any?> =
        runCatching { mapOf<String, Any?>("diagnostics" to DeviceInfo.diagnostics(this)) }
            .onFailure { Log.w(TAG, "diagnostics: ${it.message}") }
            .getOrDefault(emptyMap())

    private fun snapshot(): Snapshot {
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / maxVolume * 100).roundToInt() / 100.0
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val output = audioOutput()
        val base = Snapshot(
            state = if (screenOn) "idle" else "off",
            screenOn = screenOn,
            audioOutput = output.first,
            audioOutputName = output.second,
            dreaming = screenOn && dreaming,
            pictureMode = Projector.read(this, Projector.PICTURE_MODE),
            eyeProtector = Projector.read(this, Projector.EYE_PROTECTOR),
            memc = Projector.read(this, Projector.MEMC),
            soundMode = Projector.read(this, Projector.SOUND_MODE),
            canSleep = canSleep(),
            display = DeviceInfo.display(this),
            bluetooth = DeviceInfo.bluetooth(this),
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
        val art = artwork(meta)
        val fromNotification = mediaNotifications[controller.packageName]
        val metaTitle = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        return base.copy(
            nextTitle = nextTitle(controller),
            artworkId = art?.first,
            artworkUrl = art?.second,
            state = stateName(playback),
            app = controller.packageName,
            appLabel = label(controller.packageName),
            title = metaTitle?.takeIf { it.isNotBlank() } ?: fromNotification?.first,
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: if (metaTitle.isNullOrBlank()) fromNotification?.second else null,
            album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = duration,
            positionMs = playback?.let { currentPosition(controller, it, duration) },
            canSeek = playback != null && (playback.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
        )
    }

    /**
     * Position tracking that survives apps stamping with the wrong clock.
     *
     * PlaybackState.lastPositionUpdateTime must be SystemClock.elapsedRealtime().
     * Some apps (YouTube on Android TV) use uptimeMillis(), which stops during
     * standby. For PLAYING states the system extrapolates from that stamp before
     * handing the state to us (and re-stamps it), so the position is ahead by all
     * standby time since boot, and clamped at the duration near the end.
     *
     * Non-playing states are passed through untouched: their fresh stamp shows
     * which clock an app uses (remembered per package). For such apps we take the
     * position of each PLAYING report minus the standby time, and extrapolate
     * ourselves from there; if the system clamped it, we continue from the last
     * still position instead.
     */
    private class PositionTrack {
        var stillPosition: Long? = null
        var basePosition: Long? = null
        var baseTime = 0L
        var speed = 1f
        var playing = false
    }

    private val tracks = HashMap<MediaSession.Token, PositionTrack>()

    private fun usesUptime(pkg: String) = Prefs.usesUptimeClock(this, pkg)

    private fun observe(controller: MediaController, state: PlaybackState) {
        val token = controller.sessionToken
        val track = tracks.getOrPut(token) { PositionTrack() }
        val now = SystemClock.elapsedRealtime()
        if (stateName(state) != "playing") {
            learnClock(controller.packageName, state)
            if (state.position >= 0) track.stillPosition = state.position
            track.playing = false
            track.basePosition = null
            return
        }
        val reported = systemPosition(state) ?: return
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }
        val speed = state.playbackSpeed.coerceAtLeast(0f)
        val position = if (usesUptime(controller.packageName)) {
            val standby = now - SystemClock.uptimeMillis()
            val clamped = duration != null && reported >= duration - 1_000
            if (clamped) {
                // The system capped the inflated value at the end: real position unknown.
                // Keep running from what we know.
                if (track.playing) track.basePosition?.let { it + ((now - track.baseTime) * track.speed).toLong() }
                else track.stillPosition
            } else {
                reported - (standby * speed).toLong()
            }
        } else {
            reported
        }
        track.basePosition = position?.coerceAtLeast(0)
        track.baseTime = now
        track.speed = speed
        track.playing = true
    }

    /** A fresh non-playing stamp tells which clock the app uses. */
    private fun learnClock(pkg: String, state: PlaybackState) {
        val stamp = state.lastPositionUpdateTime
        if (stamp <= 0) return
        val elapsed = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        if (elapsed - uptime < 5_000) return // no standby since boot: can't tell
        val uptimeClock = when {
            abs(uptime - stamp) < 2_000 -> true
            abs(elapsed - stamp) < 2_000 -> false
            else -> return // old stamp, not conclusive
        }
        if (uptimeClock != usesUptime(pkg)) {
            Log.i(TAG, "$pkg stamps positions with ${if (uptimeClock) "uptimeMillis" else "elapsedRealtime"}")
            Prefs.setUsesUptimeClock(this, pkg, uptimeClock)
        }
    }

    /** Position as the system reports it, extrapolated to now. */
    private fun systemPosition(state: PlaybackState): Long? {
        if (state.position < 0) return null
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0) return state.position
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0)
        return state.position + (elapsed * state.playbackSpeed.coerceAtLeast(0f)).toLong()
    }

    private fun currentPosition(controller: MediaController, state: PlaybackState, durationMs: Long?): Long? {
        val track = tracks[controller.sessionToken]
        val position = if (stateName(state) == "playing") {
            if (track == null || !track.playing) observe(controller, state)
            val t = tracks[controller.sessionToken]
            val base = t?.basePosition ?: return systemPosition(state)
            base + ((SystemClock.elapsedRealtime() - t.baseTime) * t.speed).toLong()
        } else {
            state.position.takeIf { it >= 0 } ?: return null
        }
        return if (durationMs != null) position.coerceIn(0, durationMs) else position.coerceAtLeast(0)
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

    /** Called by the accessibility service on every window change. */
    fun onForegroundWindow(pkg: String) {
        handler.post {
            // Only apps that can be launched (or the launcher itself) count as "the app in front".
            if (!isApp(pkg) || pkg == foreground) return@post
            foreground = pkg
            schedulePublish()
        }
    }

    private val appCache = HashMap<String, Boolean>()

    private fun isApp(pkg: String): Boolean = appCache.getOrPut(pkg) {
        val pm = packageManager
        pm.getLeanbackLaunchIntentForPackage(pkg) != null ||
            pm.getLaunchIntentForPackage(pkg) != null ||
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .any { it.activityInfo.packageName == pkg }
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

    // ---------------------------------------------------------------- artwork

    private var artworkKey: String? = null
    private var artworkResult: Pair<String, String?>? = null

    /** JPEG of the current cover, served at /artwork. */
    @Volatile
    var artworkBytes: ByteArray? = null
        private set

    /**
     * (id, url) for the current cover: a web URL if the app provides one,
     * otherwise a bitmap that is compressed once and served by the API.
     */
    private fun artwork(meta: MediaMetadata?): Pair<String, String?>? {
        if (meta == null) return null
        val url = listOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        ).firstNotNullOfOrNull { key -> meta.getString(key)?.takeIf { it.startsWith("http") } }
        val bitmap = listOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).firstNotNullOfOrNull { meta.getBitmap(it) }
        // Every getMetadata() unparcels a new Bitmap, so compare by content markers, not identity.
        val key: String = url ?: bitmap?.let {
            "${meta.getString(MediaMetadata.METADATA_KEY_TITLE)}|${it.width}x${it.height}|${it.byteCount}"
        } ?: return null
        if (key == artworkKey) return artworkResult
        artworkKey = key
        artworkResult = if (url != null) {
            artworkBytes = null
            Integer.toHexString(url.hashCode()) to url
        } else {
            val bytes = Artwork.toJpeg(bitmap!!)
            artworkBytes = bytes
            Artwork.id(bytes) to null
        }
        return artworkResult
    }

    // ---------------------------------------------------------------- queue

    /** Title of the item after the current one, if the app publishes a queue. */
    private fun nextTitle(controller: MediaController): String? {
        val queue = controller.queue ?: return null
        if (queue.isEmpty()) return null
        val activeId = controller.playbackState?.activeQueueItemId ?: return null
        val index = queue.indexOfFirst { it.queueId == activeId }
        if (index < 0 || index + 1 >= queue.size) return null
        return queue[index + 1].description?.title?.toString()?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------- audio output

    /** Where media audio goes right now: (kind, device name). */
    private fun audioOutput(): Pair<String, String?> {
        val routed = if (Build.VERSION.SDK_INT >= 33) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            runCatching { audio.getAudioDevicesForAttributes(media) }.getOrNull()?.firstOrNull()
        } else {
            null
        }
        // Before Android 13 there is no API for the active route: take the device
        // media would go to by Android's usual priority.
        val device = routed ?: audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { outputKind(it.type) != null }
            .minByOrNull { OUTPUT_PRIORITY.indexOf(outputKind(it.type)) }
        val kind = device?.let { outputKind(it.type) } ?: "speaker"
        val name = device?.productName?.toString()?.takeIf { kind != "speaker" && it.isNotBlank() }
        return kind to name
    }

    private fun outputKind(type: Int): String? = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> "bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb"
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmi_arc"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE -> "line_out"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        else -> null
    }

    // ---------------------------------------------------------------- links

    /**
     * Opens a link in the app that handles it. YouTube and Netflix links go to
     * their TV apps explicitly, otherwise Android may pick a browser.
     */
    private fun openUrl(raw: String): Boolean {
        var uri = Uri.parse(raw.trim())
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: ""
        if (host == "youtu.be") {
            uri = Uri.parse("https://www.youtube.com/watch?v=${uri.lastPathSegment}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = when {
            host == "youtu.be" || host.endsWith("youtube.com") -> YOUTUBE_TV
            host.endsWith("netflix.com") -> NETFLIX_TV
            else -> null
        }
        if (target != null && isInstalled(target)) {
            intent.setPackage(target)
            if (target == NETFLIX_TV) intent.putExtra("source", "30") // Netflix TV deep-link convention
        }
        if (intent.resolveActivity(packageManager) == null) {
            Log.w(TAG, "no app opens $uri")
            return false
        }
        startActivity(intent)
        return true
    }

    private fun isInstalled(pkg: String) =
        runCatching { packageManager.getApplicationInfo(pkg, 0) }.isSuccess

    // ---------------------------------------------------------------- apps

    /** Launchable apps (TV launcher first), sorted by name. */
    fun launchableApps(): List<Pair<String, String>> {
        val pm = packageManager
        val found = LinkedHashMap<String, String>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg != packageName && pkg !in found) found[pkg] = info.loadLabel(pm).toString()
            }
        }
        return found.entries.map { it.key to it.value }.sortedBy { it.second.lowercase() }
    }

    /**
     * Starting activities from the background is allowed because the app holds
     * the "display over other apps" permission (granted via ADB).
     */
    private fun launch(pkg: String): Boolean {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    // ---------------------------------------------------------------- commands

    fun runCommand(name: String, args: JSONObject): Boolean {
        Log.i(TAG, "command $name")
        when (name) {
            "volume_set" -> {
                val level = (args.optDouble("level", -1.0)).takeIf { it in 0.0..1.0 } ?: return false
                setVolume(level)
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
            "launch" -> {
                val pkg = args.optString("package")
                if (!isApp(pkg)) return false
                afterWaking { runCatching { launch(pkg) }.onFailure { Log.w(TAG, "launch failed: ${it.message}") } }
                return true
            }
            "open_url" -> {
                val url = args.optString("url").takeIf { it.isNotBlank() } ?: return false
                afterWaking { runCatching { openUrl(url) }.onFailure { Log.w(TAG, "open_url failed: ${it.message}") } }
                return true
            }
            "sleep" -> return sleep()
            "install_update" -> return Updater.install(this, args.optString("url"))
            "set_setting" -> {
                val key = args.optString("key").takeIf { it in Projector.WATCHED } ?: return false
                val value = args.optInt("value", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } ?: return false
                return Projector.write(this, key, value)
            }
            "home" -> {
                startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
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

    /**
     * Some TV ROMs (the JMGO projector among them) silently ignore
     * setStreamVolume() but honour step adjustments, the path the volume keys
     * take. Set directly, verify, and walk there step by step if it didn't stick.
     */
    private fun setVolume(level: Double) {
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        val target = (level * max).roundToInt().coerceIn(0, max)
        if (audio.isStreamMute(stream) && target > 0) audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
        runCatching { audio.setStreamVolume(stream, target, 0) }
        var current = audio.getStreamVolume(stream)
        var steps = 0
        while (current != target && steps < max + 2) {
            val direction = if (current < target) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(stream, direction, 0)
            val next = audio.getStreamVolume(stream)
            if (next == current) break // not moving: give up instead of looping
            current = next
            steps++
        }
        Log.i(TAG, "volume target=$target reached=$current (max $max, $steps steps)")
    }

    /**
     * A running screensaver stays on top of anything started from the
     * background. A HOME key from the accessibility service ends it like a real
     * key press; then the action runs.
     */
    private fun afterWaking(action: () -> Unit) {
        val keys = KeyService.instance
        if (dreaming && keys != null) {
            Log.i(TAG, "ending screensaver first")
            keys.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            handler.postDelayed(action, 800)
        } else {
            action()
        }
    }

    // ---------------------------------------------------------------- standby

    fun adminActive(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun canSleep() = adminActive() || KeyService.instance != null

    /**
     * Screen off = standby. Device admin lockNow() is the reliable way; the
     * accessibility "lock screen" action is the fallback (Android 9+).
     */
    private fun sleep(): Boolean {
        Log.i(TAG, "going to standby")
        if (adminActive()) {
            getSystemService(DevicePolicyManager::class.java).lockNow()
            return true
        }
        val keys = KeyService.instance ?: return false
        return keys.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    // ---------------------------------------------------------------- misc

    fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    companion object {
        private const val DEBOUNCE_MS = 250L
        private const val HEARTBEAT_MS = 30_000L
        private const val FOREGROUND_POLL_MS = 3_000L
        private const val MAX_RETAINED = 5
        private const val YOUTUBE_TV = "com.google.android.youtube.tv"
        private const val NETFLIX_TV = "com.netflix.ninja"
        private val OUTPUT_PRIORITY = listOf("bluetooth", "headphones", "usb", "hdmi_arc", "line_out", "hdmi", "speaker")
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val BATTERY_LEVEL_CHANGED_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

        @Volatile
        var instance: MediaBridgeService? = null
            private set
    }
}
