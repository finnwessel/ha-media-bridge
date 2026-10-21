package de.finnwessel.mediabridge

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

object Prefs {
    private const val FILE = "media_bridge"
    private const val KEY_URL = "webhook_url"
    private const val KEY_TOKEN = "api_token"
    private const val KEY_DEVICE_ID = "device_id"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun webhookUrl(context: Context): String? = prefs(context).getString(KEY_URL, null)

    fun setWebhookUrl(context: Context, url: String?) {
        prefs(context).edit().putString(KEY_URL, url).apply()
    }

    /** Stable random ID that identifies this device in Home Assistant. */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    /** API token handed to Home Assistant during pairing; created on first use. */
    fun token(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_TOKEN, null) ?: newToken().also {
            p.edit().putString(KEY_TOKEN, it).apply()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
