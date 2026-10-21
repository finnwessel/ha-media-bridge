package de.finnwessel.mediabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manual configuration via ADB (the Home Assistant integration normally does
 * this over the API after pairing):
 *
 *   adb shell am broadcast -f 0x20 -n de.finnwessel.mediabridge/.ConfigReceiver \
 *       -a de.finnwessel.mediabridge.CONFIGURE --es url "http://<ha>/api/webhook/<id>"
 *
 * Extras: `url` sets the webhook, `--ez resend true` pushes the current state,
 * `--ez show_token true` prints the API token to logcat.
 * Requires android.permission.DUMP, which the ADB shell has and normal apps
 * don't, so nothing else on the device can use it.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        intent.getStringExtra("url")?.trim()?.let { url ->
            Prefs.setWebhookUrl(context, url.ifBlank { null })
            Log.i(TAG, "webhook URL set")
        }
        if (intent.getBooleanExtra("show_token", false)) {
            Log.i(TAG, "API token: ${Prefs.token(context)}")
        }
        val service = MediaBridgeService.instance
        if (service == null) {
            Log.w(TAG, "listener not connected yet – grant notification access (see README)")
        } else if (intent.hasExtra("url") || intent.getBooleanExtra("resend", false)) {
            service.publish(force = true)
        }
    }

    companion object {
        const val ACTION = "de.finnwessel.mediabridge.CONFIGURE"
    }
}
