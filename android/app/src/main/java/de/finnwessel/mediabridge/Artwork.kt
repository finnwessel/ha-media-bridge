package de.finnwessel.mediabridge

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object Artwork {
    private const val MAX_SIZE = 600

    /** Scaled-down JPEG of a cover bitmap. */
    fun toJpeg(bitmap: Bitmap): ByteArray {
        val scale = minOf(1f, MAX_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    /** Short content hash, used by Home Assistant to know when the image changed. */
    fun id(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
}
