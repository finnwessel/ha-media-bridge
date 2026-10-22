package de.finnwessel.mediabridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream

/**
 * App logos for Home Assistant: the TV banner (the wide logo the launcher shows)
 * or the launcher icon, rendered to PNG with transparency.
 */
object AppIcons {
    private const val BANNER_WIDTH = 640
    private const val ICON_SIZE = 256
    private val cache = LruCache<String, ByteArray>(24)

    fun png(context: Context, pkg: String, banner: Boolean): ByteArray? {
        val key = "$pkg|$banner"
        cache.get(key)?.let { return it }
        val bytes = runCatching { render(context, pkg, banner) }
            .onFailure { Log.w(TAG, "icon for $pkg: ${it.message}") }
            .getOrNull() ?: return null
        cache.put(key, bytes)
        return bytes
    }

    private fun render(context: Context, pkg: String, banner: Boolean): ByteArray? {
        val pm = context.packageManager
        val drawable: Drawable = if (banner) {
            val leanback = pm.getLeanbackLaunchIntentForPackage(pkg)
                ?.let { pm.resolveActivity(it, 0)?.activityInfo?.loadBanner(pm) }
            leanback ?: pm.getApplicationBanner(pkg) ?: pm.getApplicationIcon(pkg)
        } else {
            pm.getApplicationIcon(pkg)
        }
        return toPng(drawable, if (banner) BANNER_WIDTH else ICON_SIZE)
    }

    private fun toPng(drawable: Drawable, maxSize: Int): ByteArray {
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxSize
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxSize
        val scale = maxSize.toFloat() / maxOf(w, h)
        val width = (w * scale).toInt().coerceAtLeast(1)
        val height = (h * scale).toInt().coerceAtLeast(1)
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, width, height, true)
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                drawable.setBounds(0, 0, width, height)
                drawable.draw(canvas)
            }
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }
}
