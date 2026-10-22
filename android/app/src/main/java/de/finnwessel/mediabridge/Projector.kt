package de.finnwessel.mediabridge

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Vendor settings of JMGO projectors, kept in the regular settings tables.
 * Reading needs nothing; writing global settings needs WRITE_SECURE_SETTINGS
 * (granted via ADB). On other devices the values are simply absent.
 */
object Projector {
    const val PICTURE_MODE = "picture_mode"
    const val EYE_PROTECTOR = "eye_protector_mode"
    const val MEMC = "jmgo_memc_level"
    const val SOUND_MODE = "sound_advanced_sound_mode"

    /** Keys whose changes are worth a push. */
    val WATCHED = listOf(PICTURE_MODE, EYE_PROTECTOR, MEMC, SOUND_MODE)

    fun read(context: Context, key: String): Int? =
        Settings.Global.getString(context.contentResolver, key)?.toIntOrNull()

    fun write(context: Context, key: String, value: Int): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, key, value)
    }.onFailure { Log.w(TAG, "can't write $key (grant WRITE_SECURE_SETTINGS): ${it.message}") }
        .getOrDefault(false)

    private var distanceCache: Pair<Long, Int?>? = null

    /** Last distance measured by the time-of-flight sensor (autofocus), in mm; cached for a minute. */
    fun distanceMm(): Int? {
        val now = System.currentTimeMillis()
        distanceCache?.let { (at, value) -> if (now - at < 60_000) return value }
        val value = runCatching {
            val process = ProcessBuilder("getprop", "persist.vendor.jmgo.toflastdistance").start()
            process.waitFor(2, TimeUnit.SECONDS)
            process.inputStream.bufferedReader().readText().trim().toIntOrNull()?.takeIf { it > 0 }
        }.getOrNull()
        distanceCache = now to value
        return value
    }
}
