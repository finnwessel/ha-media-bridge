package de.finnwessel.mediabridge

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Routes of the local API (port [PORT]):
 *
 *   GET  /info          – device id, name, version (open, used for discovery)
 *   POST /pair/start    – shows a 6-digit code on screen (open)
 *   POST /pair/finish   – {"code"} → {"token"} (open, 5 attempts per code)
 *   GET  /state         – current snapshot                     (token)
 *   GET  /apps          – launchable apps                      (token)
 *   GET  /artwork       – cover of the current media as JPEG   (token)
 *   POST /config        – {"webhook_url"}                      (token)
 *   POST /command       – {"command", ...}                     (token)
 *   POST /notify        – {"title", "message", "duration_ms",
 *                          "image" (base64), "actions" [{"id","title"}],
 *                          "tag", "pause"}                     (token)
 */
class Api(private val service: MediaBridgeService) {
    private val pairing = Pairing()
    private val main = Handler(Looper.getMainLooper())

    fun handle(req: Request): Response = when (req.method to req.path) {
        "GET" to "/info" -> Response.ok(info())
        "POST" to "/pair/start" -> pairStart()
        "POST" to "/pair/finish" -> pairFinish(req)
        else -> if (!authorized(req)) Response.error(401, "unauthorized") else when (req.method to req.path) {
            "GET" to "/state" -> Response.ok(onMain { service.snapshotJson() })
            "GET" to "/apps" -> apps()
            "GET" to "/artwork" -> artwork()
            "POST" to "/config" -> config(req)
            "POST" to "/command" -> command(req)
            "POST" to "/notify" -> notify(req)
            else -> Response.error(404, "not found")
        }
    }

    private fun info() = JSONObject().apply {
        put("id", Prefs.deviceId(service))
        put("name", service.deviceName())
        put("model", Build.MODEL)
        put("manufacturer", Build.MANUFACTURER)
        put("android", Build.VERSION.RELEASE)
        put("version", BuildConfig.VERSION_NAME)
        put("api", Discovery.API_VERSION)
    }

    private fun pairStart(): Response {
        val code = pairing.start() ?: return Response.error(429, "too many requests")
        Overlay.show(service, "Home Assistant", "Kopplungscode: $code", Pairing.VALID_MS)
        return Response.ok()
    }

    private fun pairFinish(req: Request): Response {
        val code = req.json().optString("code")
        if (!pairing.finish(code)) return Response.error(401, "invalid code")
        Overlay.show(service, "Home Assistant", "Erfolgreich verbunden", 4000)
        return Response.ok(info().put("token", Prefs.token(service)))
    }

    private fun apps(): Response {
        val list = JSONArray()
        service.launchableApps().forEach { (pkg, label) ->
            list.put(JSONObject().put("package", pkg).put("label", label))
        }
        return Response.ok(JSONObject().put("apps", list))
    }

    private fun artwork(): Response {
        val bytes = service.artworkBytes ?: return Response.error(404, "no artwork")
        return Response(200, bytes, "image/jpeg")
    }

    private fun config(req: Request): Response {
        val body = req.json()
        if (body.has("webhook_url")) {
            val url = body.optString("webhook_url").takeIf { it.isNotBlank() && !body.isNull("webhook_url") }
            Prefs.setWebhookUrl(service, url)
            main.post { service.publish(force = true) }
        }
        return Response.ok()
    }

    private fun command(req: Request): Response {
        val body = req.json()
        val name = body.optString("command")
        val ok = onMain { service.runCommand(name, body) }
        return if (ok) Response.ok() else Response.error(409, "command '$name' not possible")
    }

    private fun notify(req: Request): Response {
        val body = req.json()
        val message = body.optString("message").takeIf { it.isNotBlank() }
            ?: return Response.error(400, "message missing")
        val title = body.optString("title").takeIf { it.isNotBlank() && !body.isNull("title") }
        val image = body.optString("image").takeIf { it.isNotBlank() && !body.isNull("image") }?.let {
            runCatching {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.onFailure { e -> Log.w(TAG, "notification image unreadable: ${e.message}") }.getOrNull()
        }
        val actions = body.optJSONArray("actions")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val a = array.optJSONObject(i) ?: return@mapNotNull null
                val id = a.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                OverlayAction(id, a.optString("title").ifBlank { id })
            }.take(3)
        } ?: emptyList()
        val tag = body.optString("tag").takeIf { it.isNotBlank() && !body.isNull("tag") }
        if (body.optBoolean("pause", false)) onMain { service.runCommand("pause", JSONObject()) }

        Overlay.show(service, title, message, body.optLong("duration_ms", 8000), image, actions) { result ->
            if (actions.isNotEmpty()) {
                service.pushEvent(mapOf("event" to "notification_action", "action" to result, "tag" to tag))
            }
        }
        return Response.ok()
    }

    private fun authorized(req: Request): Boolean {
        val given = req.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: return false
        return MessageDigest.isEqual(given.toByteArray(), Prefs.token(service).toByteArray())
    }

    /** Media session APIs are used from the main thread only. */
    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(block)
        main.post(task)
        return task.get(3, TimeUnit.SECONDS)
    }

    companion object {
        const val PORT = 8765
    }
}
