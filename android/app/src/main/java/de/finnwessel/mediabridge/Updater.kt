package de.finnwessel.mediabridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update: downloads a release APK and hands it to the package installer.
 * The first update asks for confirmation on screen (OK with the remote); once the
 * app is its own installer, Android 12+ installs further updates without asking.
 * Progress is reported to Home Assistant as `update` events.
 */
object Updater {
    @Volatile
    var running = false
        private set

    fun install(context: Context, url: String): Boolean {
        if (running || !url.startsWith("https://")) return false
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            report("failed", "install permission missing")
            return false
        }
        running = true
        Thread({ run(context.applicationContext, url) }, "updater").start()
        return true
    }

    private fun run(context: Context, url: String) {
        try {
            report("downloading")
            val file = File(context.cacheDir, "update.apk")
            download(url, file)
            val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
                ?: return fail("download is not an APK")
            if (info.packageName != context.packageName) return fail("APK is for ${info.packageName}")
            report("installing", info.versionName)

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val intent = PendingIntent.getBroadcast(
                    context, id, Intent(context, UpdateReceiver::class.java), flags,
                )
                session.commit(intent.intentSender)
            }
            // The result arrives in UpdateReceiver; on success this process is replaced.
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun download(url: String, target: File) {
        var location = URL(url)
        repeat(5) {
            val conn = location.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "MediaBridge/${BuildConfig.VERSION_NAME}")
            when (conn.responseCode) {
                in 300..399 -> {
                    val next = conn.getHeaderField("Location") ?: error("redirect without location")
                    conn.disconnect()
                    location = URL(location, next)
                    if (location.protocol != "https") error("insecure redirect")
                }
                200 -> {
                    conn.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                    return
                }
                else -> error("HTTP ${conn.responseCode}")
            }
        }
        error("too many redirects")
    }

    private fun fail(message: String) {
        Log.w(TAG, "update failed: $message")
        running = false
        report("failed", message)
    }

    fun finished(status: Int, message: String?) {
        running = false
        if (status == PackageInstaller.STATUS_SUCCESS) report("installed") else report("failed", message ?: "status $status")
    }

    fun report(status: String, detail: String? = null) {
        Log.i(TAG, "update $status ${detail ?: ""}")
        MediaBridgeService.instance?.pushEvent(mapOf("event" to "update", "status" to status, "detail" to detail))
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            Updater.report("confirm")
            // Allowed from the background thanks to the overlay permission.
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        Updater.finished(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
