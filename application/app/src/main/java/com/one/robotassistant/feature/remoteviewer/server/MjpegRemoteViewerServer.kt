package com.one.robotassistant.feature.remoteviewer.server

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MjpegRemoteViewerServer(
    private val port: Int = DEFAULT_PORT,
) : RemoteViewerServer, RemoteFramePublisher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latestFrame = AtomicReference<RemoteViewerFrame?>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var boundPort: Int? = null
    private var acceptJob: Job? = null

    @Volatile
    var lastError: String? = null
        private set

    override suspend fun start() = withContext(Dispatchers.IO) {
        if (serverSocket != null) return@withContext
        runCatching {
            ServerSocket().also { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(port))
                serverSocket = socket
                boundPort = socket.localPort
                lastError = null
                acceptJob = scope.launch { acceptClients(socket) }
            }
        }.onFailure { error ->
            lastError = error.message ?: "Không thể mở remote viewer"
            serverSocket = null
            boundPort = null
        }
    }

    override suspend fun stop() {
        val job = acceptJob
        acceptJob = null
        serverSocket?.close()
        serverSocket = null
        boundPort = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        job?.cancelAndJoin()
    }

    override fun getViewerUrl(): String? {
        if (serverSocket == null) return null
        val activePort = boundPort ?: return null
        val address = findLanAddress() ?: return "http://127.0.0.1:$activePort"
        return "http://$address:$activePort"
    }

    override suspend fun publish(frame: RemoteViewerFrame) {
        latestFrame.set(frame)
    }

    private suspend fun acceptClients(socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            clients += client
            scope.launch { handle(client) }
        }
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.use { client ->
                client.soTimeout = 5_000
                val input = BufferedInputStream(client.getInputStream())
                val requestLine = readHttpLine(input) ?: return@use
                var line: String?
                do {
                    line = readHttpLine(input)
                } while (!line.isNullOrEmpty())

                val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
                val output = BufferedOutputStream(client.getOutputStream())
                when (path) {
                    "/", "/index.html" -> sendHtml(output)
                    ViewerPaths.STREAM -> streamMjpeg(client, output)
                    ViewerPaths.SNAPSHOT -> sendSnapshot(output)
                    ViewerPaths.DEBUG -> sendDebug(output)
                    else -> sendNotFound(output)
                }
            }
        } finally {
            clients -= socket
        }
    }

    private fun sendHtml(output: BufferedOutputStream) {
        val body = VIEWER_HTML.toByteArray(StandardCharsets.UTF_8)
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n",
        )
        output.write(body)
        output.flush()
    }

    private suspend fun streamMjpeg(socket: Socket, output: BufferedOutputStream) {
        socket.soTimeout = 0
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                "Cache-Control: no-store, no-cache\r\nConnection: close\r\n\r\n",
        )
        output.flush()
        var lastTimestamp = -1L
        while (!socket.isClosed) {
            val frame = latestFrame.get()
            if (frame != null && frame.timestampMillis != lastTimestamp) {
                lastTimestamp = frame.timestampMillis
                output.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.jpegBytes.size}\r\n\r\n")
                output.write(frame.jpegBytes)
                output.write("\r\n")
                output.flush()
            }
            delay(75)
        }
    }

    private fun sendSnapshot(output: BufferedOutputStream) {
        val bytes = latestFrame.get()?.jpegBytes
        if (bytes == null) {
            sendServiceUnavailable(output)
            return
        }
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n",
        )
        output.write(bytes)
        output.flush()
    }

    private fun sendDebug(output: BufferedOutputStream) {
        val body = (latestFrame.get()?.debugJson ?: "{}").toByteArray(StandardCharsets.UTF_8)
        output.write(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n",
        )
        output.write(body)
        output.flush()
    }

    private fun sendNotFound(output: BufferedOutputStream) {
        val body = "Not found".toByteArray()
        output.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        output.write(body)
        output.flush()
    }

    private fun sendServiceUnavailable(output: BufferedOutputStream) {
        val body = "Camera frame is not ready".toByteArray()
        output.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        output.write(body)
        output.flush()
    }

    private fun BufferedOutputStream.write(text: String) {
        write(text.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun readHttpLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < 8_192) {
            val value = input.read()
            if (value == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun findLanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private object ViewerPaths {
        const val STREAM = "/stream.mjpeg"
        const val SNAPSHOT = "/snapshot.jpg"
        const val DEBUG = "/debug"
    }

    companion object {
        const val DEFAULT_PORT = 8080

        private val VIEWER_HTML = """
            <!doctype html>
            <html lang="vi">
            <head>
              <meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
              <title>ONE Remote Viewer</title>
              <style>
                html,body{margin:0;background:#000;color:#fff;font:14px monospace;height:100%}
                main{display:grid;place-items:center;height:100%;overflow:hidden}
                img{width:100%;height:100%;object-fit:contain}
                pre{position:fixed;left:12px;bottom:8px;margin:0;padding:8px;background:#000b;border:1px solid #fff;white-space:pre-wrap}
              </style>
            </head>
            <body><main><img src="/stream.mjpeg" alt="ONE camera"></main><pre id="debug">connecting…</pre>
            <script>setInterval(async()=>{try{debug.textContent=JSON.stringify(await (await fetch('/debug',{cache:'no-store'})).json(),null,2)}catch(e){}},500)</script>
            </body></html>
        """.trimIndent()
    }
}
