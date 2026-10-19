package com.verdroid.app.agent.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SharedDeletePolicyTest {
    private val D = "/storage/emulated/0/Documents/Verdroid"
    private val dir = Files.createTempDirectory("sd").toFile().apply { File(this, "Output").mkdirs(); File(this, "Output/a.txt").writeText("x") }
    private fun run(cmd: String) = buildJsonObject { put("command", cmd) }
    private fun py(code: String) = buildJsonObject { put("code", code) }

    private class Policy(var always: Boolean = false) : SharedDeletePolicy {
        val chats = mutableSetOf<Long>()
        override fun allowsSharedDeletes(taskId: Long) = always || taskId in chats
    }
    private fun guard(policy: SharedDeletePolicy, asked: MutableList<FileChangeRequest>, overwrite: OverwritePolicy = OverwritePolicy.NEVER) =
        FileChangeGuard({ asked += it; false }, shared = dir, overwritePolicy = overwrite, deletePolicy = policy)

    private val inside = listOf(
        run("rm -rf $D/Internal/tmp"), run("rm $D/Output/a.txt $D/Repos/x/old.md"), run("mv $D/Output/a.txt $D/Output/b.txt"),
        run("cd $D/Internal && rm -f scratch.log"), run("W=$D/Internal/w; rm -rf \"\$W\""), run("rm /sdcard/Documents/Verdroid/Output/*.tmp"),
        run("rm ~/x $D/Internal/y"),
    )
    private val outside = listOf(
        run("rm -rf /sdcard/Download/x"), run("rm $D/Output/a /sdcard/b"), run("rm -rf $D/../Pictures"), run("rm -rf $D/Output/../../x"),
        run("rm -rf $D"), run("rm -rf \"\$X\""), run("rm -rf \$(cat list)"), run("rm $D/Output/a; find $D -delete"),
        run("echo $D/Output/a | xargs rm"), run("bash -c 'rm $D/Output/a'"), run("sudo rm $D/Output/a"), run("rm Output/a.txt"),
        run("rm $D/.*"), run("cd $D/Internal; rm x"),
    )

    @Test fun `classifier - inside skips, outside mixed escapes ask`() {
        inside.forEach { assertTrue(it.toString(), SharedFolderDeletes.termuxCommand(it["command"]!!.jsonPrimitive.content)) }
        outside.forEach { assertFalse(it.toString(), SharedFolderDeletes.termuxCommand(it["command"]!!.jsonPrimitive.content)) }
        assertFalse(SharedFolderDeletes.shellCommand("rm ~/x $D/Internal/y"))   // ~ unknown for the adb shell
        assertTrue(SharedFolderDeletes.shellCommand("rm $D/Internal/y"))
        assertTrue(SharedFolderDeletes.python("import os, shutil\nos.remove('$D/Output/a.txt')\nshutil.rmtree(\"$D/Internal/tmp\")\nshutil.move('$D/Output/a', '$D/Output/b')"))
        assertTrue(SharedFolderDeletes.python("from pathlib import Path\nPath('$D/Internal/x').unlink(missing_ok=True)"))
        assertFalse(SharedFolderDeletes.python("import os\nos.remove(p)"))
        assertFalse(SharedFolderDeletes.python("import os\nos.remove(f'$D/Output/{n}')"))
        assertFalse(SharedFolderDeletes.python("import os\nos.remove('$D/../x')"))
        assertFalse(SharedFolderDeletes.python("import os\nos.rename('$D/Output/a', '/sdcard/b')"))
        assertFalse(SharedFolderDeletes.python("import os\nos.remove(os.path.join('$D', 'a'))"))
        assertFalse(SharedFolderDeletes.python("import subprocess\nsubprocess.run('rm $D/Output/a', shell=True)"))
        assertFalse(SharedFolderDeletes.python("p.unlink()"))
    }

    @Test fun `switch off asks, on skips only proven Documents-Verdroid deletes`() = runTest {
        val asked = mutableListOf<FileChangeRequest>()
        val policy = Policy(always = false)
        val g = guard(policy, asked)
        inside.forEach { assertNotNull(g.check(1, "termux_run", it)) }
        assertEquals(inside.size, asked.size)
        // Asked requests offer the Documents/Verdroid buttons (except the one that also touches Termux home only: still all allowed areas)
        assertTrue(asked.all { it.sharedDeletesOnly })
        asked.clear(); policy.always = true
        inside.forEach { assertNull(it.toString(), g.check(1, "termux_run", it)) }
        assertNull(g.check(1, "workspace_delete", buildJsonObject { put("path", "Output/a.txt") }))
        assertNull(g.check(1, "run_shell", run("rm $D/Internal/x")))
        assertNull(g.check(1, "termux_python", py("import os\nos.remove('$D/Output/a.txt')")))
        assertTrue(asked.isEmpty())
        outside.forEach { assertNotNull(it.toString(), g.check(1, "termux_run", it)) }
        assertEquals(outside.size, asked.size)
        assertTrue(asked.none { it.sharedDeletesOnly })
        asked.clear()
        assertNotNull(g.check(1, "workspace_delete", buildJsonObject { put("path", "../x") }))
        assertNotNull(g.check(1, "termux_python", py("import os\nos.remove(path)")))
        assertNotNull(g.check(1, "mcp__fs__delete_file", JsonObject(emptyMap())))
        assertNotNull(g.check(1, "skill_delete", buildJsonObject { put("id", "x") }))
        // delete inside + overwrite outside: the overwrite still needs its own allowance
        assertNotNull(g.check(1, "termux_run", run("rm $D/Output/a; echo x > /sdcard/notes.txt")))
        assertNull(guard(policy, asked, overwrite = { true }).check(1, "termux_run", run("rm $D/Output/a; echo x > /sdcard/notes.txt")))
    }

    @Test fun `This chat scope and scheduled runs`() = runTest {
        val asked = mutableListOf<FileChangeRequest>()
        val policy = Policy()
        policy.chats += 7L
        val g = guard(policy, asked)
        assertNull(g.check(7, "termux_run", run("rm $D/Output/a")))
        assertNotNull(g.check(8, "termux_run", run("rm $D/Output/a")))
        assertEquals(1, asked.size)
        // Unattended runs never get deletes, even when allowed
        policy.always = true
        assertNotNull(g.headlessReason(7, "termux_run", run("rm $D/Output/a")))
        assertNotNull(g.headlessReason(7, "workspace_delete", buildJsonObject { put("path", "Output/a.txt") }))
    }

    @Test fun `Termux-home skip unchanged`() = runTest {
        val asked = mutableListOf<FileChangeRequest>()
        val g = guard(Policy(false), asked)
        assertNull(g.check(1, "termux_run", run("rm -rf ~/verdroid-work/build")))
        assertTrue(asked.isEmpty())
    }
}
