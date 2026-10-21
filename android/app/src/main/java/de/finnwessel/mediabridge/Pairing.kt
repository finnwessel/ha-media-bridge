package de.finnwessel.mediabridge

import android.os.SystemClock
import java.security.SecureRandom

/**
 * One-time pairing: Home Assistant asks for a code, the device shows it on
 * screen, the user types it into Home Assistant, which receives the token.
 */
class Pairing {
    private val random = SecureRandom()
    private var code: String? = null
    private var expiresAt = 0L
    private var attemptsLeft = 0
    private var lastStart = 0L

    /** Returns a new code, or null when called again too quickly. */
    @Synchronized
    fun start(): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStart < 3000) return null
        lastStart = now
        return "%06d".format(random.nextInt(1_000_000)).also {
            code = it
            expiresAt = now + VALID_MS
            attemptsLeft = 5
        }
    }

    @Synchronized
    fun finish(input: String): Boolean {
        val expected = code ?: return false
        if (SystemClock.elapsedRealtime() > expiresAt || attemptsLeft <= 0) {
            code = null
            return false
        }
        attemptsLeft--
        val ok = java.security.MessageDigest.isEqual(expected.toByteArray(), input.trim().toByteArray())
        if (ok) code = null
        return ok
    }

    companion object {
        const val VALID_MS = 120_000L
    }
}
