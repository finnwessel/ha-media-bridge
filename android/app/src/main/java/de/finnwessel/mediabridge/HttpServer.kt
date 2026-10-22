package de.finnwessel.mediabridge

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors

class Request(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val query: Map<String, String> = emptyMap(),
) {
    fun json(): JSONObject = if (body.isBlank()) JSONObject() else JSONObject(body)
}

class Response(val code: Int, val body: ByteArray, val contentType: String = "application/json") {
    constructor(code: Int, text: String) : this(code, text.toByteArray())

    companion object {
        fun ok(json: JSONObject = JSONObject().put("ok", true)) = Response(200, json.toString())
        fun error(code: Int, message: String) = Response(code, JSONObject().put("error", message).toString())
    }
}

/**
 * Minimal HTTP/1.1 server for the local API. One request per connection,
 * JSON in and out, small bodies only. Deliberately dependency-free.
 */
class HttpServer(private val port: Int, private val handle: (Request) -> Response) {
    private val workers = Executors.newFixedThreadPool(4)
    @Volatile private var socket: ServerSocket? = null

    fun start() {
        if (socket != null) return
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        socket = server
        Thread({ acceptLoop(server) }, "MediaBridge-http").apply { isDaemon = true }.start()
        Log.i(TAG, "API listening on port $port")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val client = server.accept()
                workers.execute { serve(client) }
            } catch (_: SocketException) {
                return // closed
            } catch (e: Exception) {
                Log.w(TAG, "accept failed: ${e.message}")
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { s ->
            s.soTimeout = 5000
            val response = try {
                val request = parse(BufferedInputStream(s.getInputStream()))
                if (request == null) Response.error(400, "bad request") else handle(request)
            } catch (e: Exception) {
                Log.w(TAG, "request failed: ${e.message}")
                Response.error(500, e.message ?: "error")
            }
            val body = response.body
            val head = "HTTP/1.1 ${response.code} ${reason(response.code)}\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            s.getOutputStream().apply {
                write(head.toByteArray())
                write(body)
                flush()
            }
        }
    }

    private fun parse(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val query = parts[1].substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return Request(parts[0].uppercase(), parts[1].substringBefore('?'), headers, String(body, 0, read), query)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            if (buf.size() > 8192) return null
            buf.write(b)
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        429 -> "Too Many Requests"
        else -> "Error"
    }

    companion object {
        private const val MAX_BODY = 2 * 1024 * 1024 // notification images
    }
}
