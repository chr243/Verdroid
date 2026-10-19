package com.verdroid.app.agent.tools

import com.verdroid.app.data.mcp.MiniHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WebFetchToolTest {
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()

    @Test fun `GET returns status body and truncates`() = runBlocking {
        server.route("/hello") { _, res -> res.reply(200, "x".repeat(50), "text/plain") }
        server.start()
        val r = Json.parseToJsonElement(WebFetchTool().execute(buildJsonObject {
            put("url", "http://127.0.0.1:${server.port}/hello"); put("max_bytes", 10)
        })).jsonObject
        assertEquals(true, r["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, r["status"]!!.jsonPrimitive.int)
        assertEquals(true, r["truncated"]!!.jsonPrimitive.boolean)
        assertEquals(10, r["body"]!!.jsonPrimitive.content.length)
    }

    @Test fun `404 on a git host is annotated, other hosts and statuses are not, no token is sent`() = runBlocking {
        var auth: String? = "unset"
        server.route("/o/private") { req, res -> auth = req.header("Authorization"); res.reply(404, "Not Found", "text/plain") }
        server.route("/o/public") { _, res -> res.reply(200, "<html><body><p>hi</p></body></html>", "text/html") }
        server.start()
        val git = WebFetchTool(gitHosts = setOf("127.0.0.1"))
        for (fmt in listOf("raw", "markdown")) {
            val r = Json.parseToJsonElement(git.execute(buildJsonObject { put("url", "http://127.0.0.1:${server.port}/o/private"); put("format", fmt) })).jsonObject
            assertEquals(404, r["status"]!!.jsonPrimitive.int)
            assertTrue(fmt, r["note"]!!.jsonPrimitive.content.contains("PRIVATE"))
            assertTrue(r["note"]!!.jsonPrimitive.content.contains("never uses the Git token"))
        }
        assertNull(auth)
        val ok = Json.parseToJsonElement(git.execute(buildJsonObject { put("url", "http://127.0.0.1:${server.port}/o/public"); put("format", "markdown") })).jsonObject
        assertNull(ok["note"])
        val other = Json.parseToJsonElement(WebFetchTool().execute(buildJsonObject { put("url", "http://127.0.0.1:${server.port}/o/private") })).jsonObject
        assertNull(other["note"])
        assertNotNull(WebFetchTool.gitHostNote(404, "github.com")); assertNotNull(WebFetchTool.gitHostNote(404, "api.github.com"))
        assertNull(WebFetchTool.gitHostNote(403, "github.com")); assertNull(WebFetchTool.gitHostNote(404, "example.com"))
    }
}
