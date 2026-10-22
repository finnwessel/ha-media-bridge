package de.finnwessel.mediabridge

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Reports remote-control key presses to Home Assistant (short and long press).
 * Keys are only observed, never consumed: the remote keeps working as usual.
 * Enabled via ADB (see README); without it nothing is reported.
 */
class KeyService : AccessibilityService() {

    private val downAt = HashMap<Int, Long>()
    private val longReported = HashSet<Int>()

    override fun onServiceConnected() {
        Log.i(TAG, "key service connected")
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in IGNORED) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    downAt[code] = event.eventTime
                    longReported.remove(code)
                } else if (code !in longReported && event.eventTime - (downAt[code] ?: event.eventTime) >= LONG_PRESS_MS) {
                    // Report long presses while the key is still held, so the reaction is immediate.
                    longReported.add(code)
                    report(code, "long_press")
                }
            }
            KeyEvent.ACTION_UP -> {
                val down = downAt.remove(code)
                if (longReported.remove(code)) return false
                val press = if (down != null && event.eventTime - down >= LONG_PRESS_MS) "long_press" else "short_press"
                report(code, press)
            }
        }
        return false
    }

    private fun report(code: Int, press: String) {
        val key = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").lowercase()
        Log.i(TAG, "key $key $press")
        val service = MediaBridgeService.instance ?: return
        service.pushEvent(mapOf("event" to "key", "key" to key, "press" to press))
    }

    /**
     * Window changes tell which app is in front. UsageStats would too, but
     * some TV builds (JMGO) don't record activity events at all.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in NOT_APPS) return
        MediaBridgeService.instance?.onForegroundWindow(pkg)
    }

    override fun onInterrupt() = Unit

    companion object {
        @Volatile
        var instance: KeyService? = null
            private set

        private const val LONG_PRESS_MS = 600L

        /** System UI windows that come and go above apps. */
        private val NOT_APPS = setOf("com.android.systemui", "android", "com.google.android.inputmethod.latin")

        /** Navigation would flood Home Assistant without being useful as a trigger. */
        private val IGNORED = setOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
