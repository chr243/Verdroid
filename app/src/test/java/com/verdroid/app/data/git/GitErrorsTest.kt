package com.verdroid.app.data.git

import com.verdroid.app.data.mcp.MiniHttpServer
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GitErrorsTest {
    private val server = MiniHttpServer()
    @After fun stop() = server.stop()
    private val tok = "github_pat_SECRET123"

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    @Test fun `401 403 404 map to clear codes, hints and status`() {
        val e = org.eclipse.jgit.api.errors.TransportException("https://github.com/o/r.git: git-upload-pack not permitted")
        val a = GitErrors.explain("clone", "https://github.com/o/r", e, 401 to "Invalid username or token.", true, tok)
        assertEquals("AUTH_FAILED", a.s("code")); assertEquals(401, a["http_status"]!!.jsonPrimitive.int)
        assertTrue(a.s("error")!!.contains("missing, invalid or expired"))
        val f = GitErrors.explain("clone", "https://github.com/o/r", e, 403 to "", true, tok)
        assertEquals("PERMISSION_DENIED", f.s("code"))
        assertTrue(f.s("hint")!!.contains("Contents to Read for"))
        val p = GitErrors.explain("push", "https://github.com/o/r", e, 403 to "", true, tok)
        assertTrue(p.s("hint")!!.contains("Contents to Read and write"))
        val n = GitErrors.explain("clone", "https://github.com/o/missing", InvalidRemoteException("Invalid remote: origin"), 404 to "", true, tok)
        assertEquals("REPO_NOT_FOUND", n.s("code"))
        assertTrue(n.s("error")!!.contains("not found or not visible to this token (HTTP 404)"))
        // No probe: status is inferred from JGit's own text.
        assertEquals("PERMISSION_DENIED", GitErrors.explain("clone", "u", e, null, true, tok).s("code"))
        assertEquals("REPO_NOT_FOUND", GitErrors.explain("clone", "u", InvalidRemoteException("Invalid remote: origin"), null, true, tok).s("code"))
    }

    @Test fun `remote text is surfaced and the token never appears`() {
        val e = org.eclipse.jgit.api.errors.TransportException(
            "https://x-access-token:$tok@github.com/o/r.git: remote: Permission to o/r.git denied to bot.\nremote: Write access to repository not granted.")
        val j = GitErrors.explain("push", "https://x-access-token:$tok@github.com/o/r.git", e, 403 to "remote: Write access to repository not granted.", true, tok)
        assertTrue(j.s("remote")!!, j.s("remote")!!.contains("Write access to repository not granted"))
        assertTrue(j.s("remote")!!.contains("Permission to o/r.git denied"))
        assertEquals("https://github.com/o/r.git", j.s("url"))
        assertFalse(j.toString().contains(tok)); assertFalse(j.toString().contains("SECRET"))
        assertEquals("https://github.com/o/r.git/info/refs?service=git-receive-pack", GitErrors.infoRefs("https://u:$tok@github.com/o/r", "git-receive-pack"))
    }

    @Test fun `real JGit clone failure plus HTTP probe gives status and server text`() {
        var auth: String? = null
        server.route("/o/r.git/info/refs") { req, res -> auth = req.header("Authorization"); res.reply(403, "remote: Resource not accessible by personal access token\n", "text/plain") }
        server.start()
        val url = "http://127.0.0.1:${server.port}/o/r.git"
        val dir = Files.createTempDirectory("gclone").toFile()
        val err = runCatching {
            Git.cloneRepository().setURI(url).setDirectory(dir).setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", tok)).call().close()
        }.exceptionOrNull() as Exception
        val probe = GitErrors.HTTP.probe(url, "git-upload-pack", "x-access-token", tok)
        assertEquals(403, probe!!.first)
        assertEquals("Basic " + java.util.Base64.getEncoder().encodeToString("x-access-token:$tok".toByteArray()), auth)
        val j = GitErrors.explain("clone", url, err, probe, true, tok)
        assertEquals("PERMISSION_DENIED", j.s("code")); assertEquals(403, j["http_status"]!!.jsonPrimitive.int)
        assertTrue(j.s("remote")!!.contains("Resource not accessible by personal access token"))
        assertFalse(j.toString().contains(tok))
        dir.deleteRecursively()
    }
}
