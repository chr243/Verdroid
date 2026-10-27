package com.verdroid.app.agent.tools

import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ShownImagesLoaderTest {
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)
    private val seen = mutableListOf<Request>()
    private fun client(ok: (String) -> Boolean) = OkHttpClient.Builder().addInterceptor { ch ->
        val r = ch.request(); seen += r
        val good = ok(r.url.toString())
        Response.Builder().request(r).protocol(Protocol.HTTP_1_1).code(if (good) 200 else 400).message("x")
            .body((if (good) png else "bad".toByteArray()).toResponseBody()).build()
    }.build()
    private fun dir() = java.nio.file.Files.createTempDirectory("img").toFile()

    @Test fun `sends browser-like headers and encodes spaces once`() {
        val f = ShownImages.fetch("https://example.com/a b%20c.png", dir(), client { true })
        assertTrue(f.isSuccess)
        val r = seen.single()
        assertEquals("/a%20b%20c.png", r.url.encodedPath)
        assertTrue(r.header("User-Agent")!!.startsWith("Mozilla/5.0 (Linux; Android"))
        assertTrue(r.header("Accept")!!.contains("image/png"))
    }

    @Test fun `wikimedia odd thumbnail width falls back to the original file`() {
        val thumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/4/47/P.png/333px-P.png"
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/4/47/P.png", ShownImages.wikimediaOriginal(thumb))
        assertNull(ShownImages.wikimediaOriginal("https://example.com/thumb/4/47/P.png/333px-P.png"))
        assertTrue(ShownImages.fetch(thumb, dir(), client { "/thumb/" !in it }).isSuccess)
        assertEquals(2, seen.size)
    }

    @Test fun `failure names the host`() {
        val e = ShownImages.fetch("https://img.example.org/x.png", dir(), client { false }).exceptionOrNull()!!
        assertEquals("HTTP 400 from img.example.org", e.message)
    }
}
