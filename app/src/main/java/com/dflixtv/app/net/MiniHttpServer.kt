package com.dflixtv.app.net

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

/** A tiny HTTP/1.1 server for the local network: one short request per connection, no keep-alive. */
class MiniHttpServer(private val name: String, preferredPort: Int = 0, private val handler: (Request) -> Response) {

    class Request(val method: String, val path: String, val query: Map<String, String>, val form: Map<String, String>)

    class Response(val status: Int, val body: String, val contentType: String = "text/html; charset=utf-8") {
        companion object {
            fun json(body: String, status: Int = 200) = Response(status, body, "application/json; charset=utf-8")
        }
    }

    // A fixed port keeps clients' cached address valid across restarts; any free port if it's taken.
    // A failed bind closes the socket, so the fallback needs a fresh one.
    private val socket = runCatching { open(preferredPort) }.getOrElse { open(0) }
    val port: Int get() = socket.localPort

    private fun open(port: Int) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }

    fun start() = thread(name = name, isDaemon = true) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break
            }
            // Requests are tiny; a thread each keeps a slow phone from blocking the others.
            thread(name = "$name-request", isDaemon = true) { runCatching { client.use(::serve) } }
        }
    }

    fun stop() {
        runCatching { socket.close() }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val (method, target) = (reader.readLine() ?: return).split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = CharArray(contentLength.coerceIn(0, 512 * 1024)).let { buf ->
            var read = 0
            while (read < buf.size) {
                val n = reader.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        }
        val request = Request(method, target.substringBefore('?'), parseForm(target.substringAfter('?', "")), parseForm(body))
        val response = runCatching { handler(request) }.getOrElse { Response(500, "Error: ${it.message}", "text/plain") }
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 ${response.status} ${reason(response.status)}\r\nContent-Type: ${response.contentType}\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(bytes)
            flush()
        }
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        else -> "Error"
    }

    companion object {
        fun parseForm(text: String): Map<String, String> =
            text.split('&').filter { '=' in it }.associate { pair ->
                val (k, v) = pair.split('=', limit = 2)
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8").trim()
            }
    }
}
