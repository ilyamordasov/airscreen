package com.airtv.receiver.link

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCommandServerTest {
    @Test fun `only a paired command launches a valid page`() {
        val opened = mutableListOf<String>()
        val server = RemoteCommandServer("a".repeat(32), { opened.add(it) }, 0)
        server.start()
        try {
            assertEquals(403, send(server, "bad", "https://example.org/watch"))
            assertEquals(400, send(server, "a".repeat(32), "http://example.org/watch"))
            assertEquals(200, send(server, "a".repeat(32), "https://example.org/watch"))
            assertEquals(listOf("https://example.org/watch"), opened)
        } finally {
            server.stop()
        }
    }

    private fun send(server: RemoteCommandServer, token: String, url: String): Int {
        val body = "token=${URLEncoder.encode(token, "UTF-8")}&url=${URLEncoder.encode(url, "UTF-8")}".toByteArray()
        val connection = URL("http://127.0.0.1:${server.listeningPort}/command")
            .openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
