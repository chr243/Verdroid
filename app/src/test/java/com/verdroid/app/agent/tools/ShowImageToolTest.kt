package com.verdroid.app.agent.tools

import com.verdroid.app.data.mcp.MiniHttpServer
import com.verdroid.app.data.storage.SharedFolder
import com.verdroid.app.ui.chat.ShownImageRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShowImageToolTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 1, 2, 3)

    private val shared by lazy { SharedFolder(tmp.newFolder("shared"), { true }) }
    private val charts by lazy { tmp.newFolder("charts") }
    private val cache by lazy { tmp.newFolder("cache") }
    private fun tool(access: Boolean = true, http: Boolean = false) =
        ShowImageTool(if (access) shared else SharedFolder(shared.root) { false }, charts, cache, allowHttp = http)
    private fun run(t: ShowImageTool, src: String, cap: String? = null) = runBlocking {
        Json.parseToJsonElement(t.execute(buildJsonObject { put("source", src); cap?.let { put("caption", it) } })).jsonObject
    }

    @Test fun `files - allowed roots, shorthand, traversal and type checks`() {
        File(shared.root, "Output").mkdirs(); File(shared.root, "Output/a.png").writeBytes(png)
        File(shared.root, "Output/fake.png").writeText("not an image"); File(shared.root, "Output/n.txt").writeText("x")
        File(shared.root, "Repos/proj/.git").mkdirs(); File(shared.root, "Repos/proj/img.png").writeBytes(png)
        File(charts, "chart-1.png").writeBytes(png)
        File(tmp.root, "secret.png").writeBytes(png)
        val t = tool()
        val ok = run(t, "Output/a.png", "cap")
        assertEquals(true, ok["ok"]!!.jsonPrimitive.boolean); assertEquals("cap", ok["caption"]!!.jsonPrimitive.content); assertEquals("png", ok["type"]!!.jsonPrimitive.content)
        assertNotNull(run(t, "${SharedFolder.DISPLAY_PATH.let { shared.root.path }}/Output/a.png")["ok"])
        assertNotNull(run(t, "proj/img.png")["ok"])                                  // repo shorthand
        assertNotNull(run(t, File(charts, "chart-1.png").path)["ok"])                // app chart output
        for (bad in listOf("../secret.png", "Output/../../secret.png", File(tmp.root, "secret.png").path, "/etc/hosts", "Output/fake.png", "Output/n.txt", "Output/missing.png"))
            assertNotNull(bad, run(t, bad)["error"])
        assertTrue(run(tool(access = false), "Output/a.png")["error"]!!.jsonPrimitive.content.contains("All files access"))
        // the stored result drives the chat card
        val ref = ShownImageRef.fromResult(ok.toString())!!
        assertEquals("cap", ref.caption); assertTrue(ref.path!!.endsWith("Output/a.png")); assertNull(ShownImageRef.fromResult("""{"error":"x"}"""))
    }

    @Test fun `urls - https only, checked, size and type, private hosts, cached`() {
        var hits = 0
        server.route("/ok.png") { _, res -> hits++; res.replyBytes(png) }
        server.route("/page") { _, res -> res.reply(200, "<html>", "text/html") }
        server.start()
        val base = "http://127.0.0.1:${server.port}"
        assertTrue(run(tool(), "$base/ok.png")["error"]!!.jsonPrimitive.content.contains("https"))                // cleartext refused
        assertTrue(run(tool(), "https://localhost/a.png")["error"]!!.jsonPrimitive.content.contains("private"))
        assertTrue(run(tool(), "https://192.168.0.2/a.png")["error"]!!.jsonPrimitive.content.contains("private"))
        val t = tool(http = true)
        val r = run(t, "$base/ok.png"); assertEquals(base + "/ok.png", r["url"]!!.jsonPrimitive.content)
        run(t, "$base/ok.png"); assertEquals(1, hits)                                                              // cached
        assertNotNull(run(t, "$base/page")["error"]); assertNotNull(run(t, "$base/none.png")["error"])
        assertNull(run(t, "$base/ok.png")["path"])                                                                 // no local path leaked
        assertEquals(base + "/ok.png", ShownImageRef.fromResult(r.toString())!!.url)
    }

    @Test fun `sniff and hosts`() {
        assertEquals("jpg", ShownImages.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("gif", ShownImages.sniff("GIF89a".toByteArray()))
        assertEquals("webp", ShownImages.sniff("RIFF....WEBP".toByteArray()))
        assertNull(ShownImages.sniff("<svg>".toByteArray()))
        assertFalse(ShownImages.hostAllowed("10.0.0.1")); assertFalse(ShownImages.hostAllowed("172.20.1.1")); assertFalse(ShownImages.hostAllowed("::1"))
        assertTrue(ShownImages.hostAllowed("upload.wikimedia.org"))
        assertEquals("files", com.verdroid.app.data.tools.ToolGroups.groupOf("show_image"))
    }
}
