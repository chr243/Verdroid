package com.verdroid.app.agent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileChangeGuardTest {
    @get:Rule val tmp = TemporaryFolder()
    private val D = DestructiveDetector

    @Test fun shellPositives() {
        listOf("rm -rf foo", "rm a.txt", "cd x && rm b", "mv a b", "rmdir d", "unlink f", "shred -u f",
            "find . -name '*.tmp' -delete", "find /sdcard -type f -exec rm {} \\;", "git rm file", "git -C repo clean -fdx",
            "git mv a b", "rsync -a --delete src/ dst/", "sed -i 's/a/b/' /sdcard/x.txt", "echo x > /sdcard/Documents/Verdroid/Output/a.txt",
            "cat a > ~/notes.txt", "cp a.txt /sdcard/Download/a.txt", "dd if=/dev/zero of=/sdcard/x", "busybox rm x", "sudo rm -f x",
            "echo hi | tee /sdcard/x.txt", "x=1; truncate -s 0 f", "\$(rm -f a)", "cd /sdcard && echo x > a.txt")
            .forEach { assertTrue(it, D.shell(it).isNotEmpty()) }
    }

    @Test fun shellNegatives() {
        listOf("ls -la", "echo hello", "python3 run.py > out.txt", "ffmpeg -i in.mp4 out.mp3", "cat a.txt", "grep -r foo .",
            "echo x > /dev/null 2>&1", "cmd 2>&1 | head", "echo a >> log.txt", "mkdir -p out && cp a.txt out/", "mvn package",
            "echo firmware", "curl -o file.zip https://x", "echo x > /storage/emulated/0/Documents/Verdroid/Internal/a.txt",
            "# rm -rf / (comment)\nls", "git status", "git commit -m msg")
            .forEach { assertTrue("$it → ${D.shell(it)}", D.shell(it).isEmpty()) }
    }

    @Test fun rishRelativeIsNotOwned() {
        assertTrue(D.shell("echo x > a.txt", cwdOwned = false).isNotEmpty())
        assertTrue(D.shell("echo x > a.txt", cwdOwned = true).isEmpty())
    }

    @Test fun pythonDetection() {
        listOf("import os\nos.remove(p)", "os.unlink('a')", "shutil.rmtree(d)", "shutil.move(a, b)", "Path(p).unlink()", "os.rename(a,b)",
            "subprocess.run(['rm', '-f', 'x'])", "open('/sdcard/Download/a.txt', 'w').write(x)", "open(path, 'w')",
            "Path('/sdcard/x.txt').write_text('a')", "shutil.copy(a, '/sdcard/Download/b')")
            .forEach { assertTrue(it, D.python(it).isNotEmpty()) }
        listOf("print('hi')", "open('a.txt').read()", "open('out.csv', 'w')", "s.replace('a','b')",
            "open(os.path.join(os.environ['VERDROID_INTERNAL'], 'x'), 'w')", "df.to_csv('out.csv')", "open('/sdcard/x', 'r')")
            .forEach { assertTrue("$it → ${D.python(it)}", D.python(it).isEmpty()) }
    }

    private fun guard(answer: Boolean, asked: MutableList<FileChangeRequest>, root: File? = null) =
        FileChangeGuard({ asked += it; answer }, shared = root)

    @Test fun gateAsksAndRespectsDeny() = runBlocking {
        val asked = mutableListOf<FileChangeRequest>()
        val deny = guard(false, asked).check(7, "workspace_delete", buildJsonObject { put("path", "Output/a.txt") })!!
        val o = Json.parseToJsonElement(deny).jsonObject
        assertEquals(true, o["denied_by_user"]!!.jsonPrimitive.boolean)
        assertTrue(o["instruction"]!!.jsonPrimitive.content.contains("do not retry"))
        assertEquals(7L, asked.single().taskId); assertEquals("workspace_delete", asked.single().tool)
        assertNull(guard(true, asked).check(7, "termux_run", buildJsonObject { put("command", "rm x") }))
        assertEquals("rm x", asked.last().command)
    }

    @Test fun noPromptForNewFilesOrInternal() = runBlocking {
        val root = tmp.newFolder("Verdroid"); File(root, "Output").mkdirs(); File(root, "Internal").mkdirs()
        File(root, "Output/old.txt").writeText("x"); File(root, "Internal/s.py").writeText("x")
        val asked = mutableListOf<FileChangeRequest>()
        val g = guard(false, asked, root)
        assertNull(g.check(1, "workspace_write", buildJsonObject { put("path", "Output/new.txt"); put("content", "a") }))
        assertNull(g.check(1, "workspace_write", buildJsonObject { put("path", "Internal/s.py"); put("content", "a") }))
        assertNull(g.check(1, "workspace_write", buildJsonObject { put("path", "Output/old.txt"); put("content", "a"); put("mode", "create") }))
        assertNull(g.check(1, "termux_run", buildJsonObject { put("command", "python3 x.py > result.txt") }))
        assertNull(g.check(1, "selenium_screenshot", buildJsonObject { put("save_as", "new.png") }))
        assertTrue(asked.isEmpty())
        assertNotNull(g.check(1, "workspace_write", buildJsonObject { put("path", "Output/old.txt"); put("content", "a") }))
        assertNotNull(g.check(1, "pdf_merge", buildJsonObject { put("output_name", "old.txt") }))
        assertEquals(2, asked.size)
        assertTrue(asked[0].summary.startsWith("Overwrite"))
    }

    @Test fun scriptsAreInspected() = runBlocking {
        val asked = mutableListOf<FileChangeRequest>()
        val g = FileChangeGuard({ asked += it; true }) { tool, _ -> if (tool == "termux_python") "import shutil\nshutil.rmtree('/sdcard/x')" else null }
        assertNull(g.check(1, "termux_python", buildJsonObject { put("path", "a.py") }))
        assertEquals(1, asked.size)
        assertNull(g.check(1, "termux_python", buildJsonObject { put("path", "a.py"); put("code", "import os\nos.system('rm -rf /sdcard/y')") }))
        assertEquals(2, asked.size)
        assertNotNull(g.inspect("mcp__fs__delete_file", JsonObject(emptyMap())))
        assertNull(g.inspect("web_search", buildJsonObject { put("query", "rm -rf") }))
    }

    @Test fun registryBlocksDeniedCall() = runBlocking {
        var ran = false
        val t = object : AgentTool { override val name = "termux_run"; override val description = ""; override val parameters = JsonObject(emptyMap())
            override suspend fun execute(args: JsonObject): String { ran = true; return "{}" } }
        val reg = ToolRegistry(listOf(t), fileGuard = FileChangeGuard({ false }))
        val r = reg.execute("termux_run", """{"command":"rm -rf x"}""", 3)
        assertTrue(r.isError); assertFalse(ran)
        assertTrue(reg.execute("termux_run", """{"command":"ls"}""", 3).let { !it.isError } && ran)
    }
}
