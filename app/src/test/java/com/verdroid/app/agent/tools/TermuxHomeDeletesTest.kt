package com.verdroid.app.agent.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TermuxHomeDeletesTest {
    private val H = TermuxHomeDeletes.HOME
    private fun skips(cmd: String) = assertTrue(cmd, TermuxHomeDeletes.allInTermuxHome(cmd))
    private fun asks(cmd: String) = assertFalse(cmd, TermuxHomeDeletes.allInTermuxHome(cmd))

    @Test fun termuxHomeRmAndMvSkip() {
        skips("rm -rf ~/verdroid-work/build")
        skips("rm -f \$HOME/a.txt \${HOME}/b.txt")
        skips("rm -rf $H/tmp/x")
        skips("mv ~/a.txt ~/old/a.txt")
        skips("cd ~/proj && rm -rf build dist && mv out.log logs/out.log")
        skips("cd ~/proj || exit 1\nrm -rf build")
        skips("set -e\ncd ~/proj\nrm -rf build")
        skips("rm -rf ~/cache/* 2>/dev/null")
        skips("rmdir -p ~/a/b; unlink ~/link")
        skips("rm -rf ~/x/../y")                       // .. that stays inside home
        skips("rm -rf ~")                              // home itself is "home and below"
    }

    @Test fun resolvedAssignmentSkipsUnresolvedAsks() {
        skips("W=~/verdroid-work/tmp\nrm -rf \"\$W\"")
        skips("export W=\"\$HOME/scratch\"; rm -rf \"\$W\"/*.part")
        skips("D=$H/dl; mv \"\$D/a\" \"\$D/b\"")
        asks("rm -rf \"\$W\"")                         // never assigned
        asks("W=\$(mktemp -d); rm -rf \"\$W\"")         // command substitution
        asks("W=relative/dir; rm -rf \"\$W\"")          // not an absolute path
        asks("W=~/a; unset W; rm -rf \"\$W/x\"")        // unset → would be /x
        asks("W=~/a; read W; rm -rf \"\$W\"")
        asks("W=\"$H/a /sdcard\"; rm -rf \$W")         // unquoted: word splitting
        asks("IFS=/; W=~/a; rm -rf \$W")
        asks("HOME=/sdcard; rm -rf ~/x")
        asks("\"W=~/a\"; rm -rf \"\$W\"")              // quoted: not an assignment
        asks("W=~/a rm -rf \"\$W\"")                   // per-command env
        asks("rm -rf \"\${W:-/sdcard}\"")
    }

    @Test fun sharedStorageMixedEscapesAndHiddenDeletesAsk() {
        asks("rm -rf /storage/emulated/0/Download/x")
        asks("rm /sdcard/a.txt")
        asks("rm -rf ~/Documents/../../../../../storage/emulated/0/x")
        asks("rm -rf ~/../usr/bin")                     // .. climbs out of home
        asks("rm -rf $H/../usr")
        asks("rm ~/a.txt /storage/emulated/0/Documents/Verdroid/Internal/b.txt")  // mixed
        asks("rm ~/a; rm /sdcard/b")
        asks("rm -rf build")                            // relative: termux_run starts in Documents/Verdroid/Internal
        asks("rm -rf Internal/x")
        asks("cd ~/proj; rm -rf build")                 // cd may have failed
        asks("cd ~/proj && true || rm -rf build")
        asks("rm -rf ~/.*")                             // .* can match ..
        asks("rm -rf ~/{a,/sdcard}")
        asks("rm -rf ~otheruser/x")
        asks("ls ~ | xargs rm -rf")
        asks("sudo rm -rf ~/x")
        asks("bash -c \"rm -rf /sdcard/x\"; rm ~/a")
        asks("rm -rf ~/x \$(echo /sdcard)")
        asks("for f in ~/a; do rm \$f; done")
        asks("rm --no-preserve-root -rf ~/x")
        asks("shred -u ~/a")
        asks("find ~ -name x -delete")
        asks("echo hi")                                 // nothing to prove
    }

    @Test fun guardSkipsOnlyProvenTermuxHomeDeletes() = runTest {
        var asked = 0
        val g = FileChangeGuard({ asked++; true })
        suspend fun run(cmd: String) = g.check(1, "termux_run", buildJsonObject { put("command", cmd) })
        assertNull(run("rm -rf ~/verdroid-work/build")); assertEquals(0, asked)
        assertNull(run("W=~/t; mv \"\$W/a\" \"\$W/b\"")); assertEquals(0, asked)
        run("rm -rf /storage/emulated/0/Download/x"); assertEquals(1, asked)
        run("rm ~/a /sdcard/b"); assertEquals(2, asked)
        run("rm -rf ~/../usr"); assertEquals(3, asked)
        run("rm -rf \"\$W\""); assertEquals(4, asked)
        // Overwrite rules unchanged: a redirect into shared storage still asks even next to a proven home delete.
        assertNotNull(g.inspect("termux_run", buildJsonObject { put("command", "rm ~/a; echo x > /sdcard/notes.txt") }))
        // Other tools are unchanged (run_shell is not Termux).
        assertNotNull(g.inspect("run_shell", buildJsonObject { put("command", "rm -rf $H/x") }))
    }
}
