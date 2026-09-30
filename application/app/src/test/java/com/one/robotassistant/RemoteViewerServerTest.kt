package com.one.robotassistant

import com.one.robotassistant.feature.remoteviewer.server.MjpegRemoteViewerServer
import com.one.robotassistant.feature.remoteviewer.server.RemoteViewerFrame
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteViewerServerTest {
    @Test
    fun servesViewerAndDebugState() = runTest {
        val server = MjpegRemoteViewerServer(port = 0)
        try {
            server.start()
            val assignedPort = URI(server.getViewerUrl()!!).port
            server.publish(
                RemoteViewerFrame(
                    jpegBytes = byteArrayOf(1, 2, 3),
                    timestampMillis = 1,
                    debugJson = "{\"camera\":\"RUNNING\"}",
                ),
            )

            val html = get("http://127.0.0.1:$assignedPort/")
            val debug = get("http://127.0.0.1:$assignedPort/debug")

            assertTrue(html.contains("ONE Remote Viewer"))
            assertEquals("{\"camera\":\"RUNNING\"}", debug)
        } finally {
            server.stop()
        }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 2_000
            readTimeout = 2_000
            inputStream.bufferedReader().use { it.readText() }
        }
    }
}
