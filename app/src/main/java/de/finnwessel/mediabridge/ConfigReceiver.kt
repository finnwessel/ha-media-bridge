package de.finnwessel.mediabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Configuration without any UI, via ADB:
 *
 *   adb shell am broadcast -f 0x20 -n de.finnwessel.mediabridge/.ConfigReceiver \
 *       -a de.finnwessel.mediabridge.CONFIGURE --es url "http://<ha>:8123/api/webhook/<id>"
 *
 * Add `--ez resend true` (alone or together with url) to push the current state.
 * The receiver requires android.permission.DUMP, which the ADB shell has and
 * normal apps don't, so nothing else on the device can redirect the webhook.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        intent.getStringExtra("url")?.trim()?.let { url ->
            Prefs.setWebhookUrl(context, url)
            Log.i(MediaBridgeService.TAG, "webhook URL set")
        }
        val service = MediaBridgeService.instance
        if (service == null) {
            Log.w(MediaBridgeService.TAG, "listener not connected yet – grant notification access (see README)")
        } else if (intent.hasExtra("url") || intent.getBooleanExtra("resend", false)) {
            service.publish(force = true)
        }
    }

    companion object {
        const val ACTION = "de.finnwessel.mediabridge.CONFIGURE"
    }
}
