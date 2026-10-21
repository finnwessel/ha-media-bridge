package de.finnwessel.mediabridge

import android.content.Context

object Prefs {
    private const val FILE = "media_bridge"
    private const val KEY_URL = "webhook_url"

    fun webhookUrl(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_URL, null)

    fun setWebhookUrl(context: Context, url: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_URL, url).apply()
    }
}
