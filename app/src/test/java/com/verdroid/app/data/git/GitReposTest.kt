package com.verdroid.app.data.git

import com.verdroid.app.agent.tools.*
import com.verdroid.app.data.storage.SharedFolder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GitReposTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Auth(override val token: String? = null) : GitAuth {
        override val username = "x-access-token"; override val authorName = "T"; override val authorEmail = "t@x"
    }

    private val shared by lazy { SharedFolder(tmp.newFolder("shared"), { true }) }
    private val private by lazy { WorkspaceSandbox(tmp.newFolder("private")) }
    private val gm by lazy { GitManager(private, shared, Auth()).also { it.allowFileRemotes = true } }

    private fun source(name: String, symlink: Boolean = false): File {
        val d = File(tmp.root, "src/$name").apply { mkdirs() }
        Git.init().setDirectory(d).call().use { g ->
            File(d, "a.txt").writeText("hello\n")
            File(d, "run.sh").apply { writeText("echo hi\n"); setExecutable(true) }
            if (symlink) Files.createSymbolicLink(File(d, "link").toPath(), File("a.txt").toPath())
            g.add().addFilepattern(".").call()
            g.commit().setMessage("init").setAuthor(PersonIdent("a", "a@x")).setCommitter(PersonIdent("a", "a@x")).call()
        }
        return d
    }
    private fun url(d: File) = d.toURI().toString()
    private fun j(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun args(vararg p: Pair<String, Any>) = buildJsonObject { p.forEach { (k, v) -> when (v) { is Boolean -> put(k, v); is Int -> put(k, v); else -> put(k, v.toString()) } } }

    @Test fun `clone defaults to Documents-Verdroid Repos, reuses the same repo, never overwrites another`() = runBlocking {
        val src = source("proj", symlink = true)
        val r = j(GitCloneTool(gm).execute(args("url" to url(src))))
        assertEquals(r.toString(), "Repos/proj", r.s("repo_path"))
        val dir = File(shared.root, "Repos/proj")
        assertEquals(dir.canonicalPath, r.s("absolute_path"))
        assertEquals("hello\n", File(dir, "a.txt").readText())
        // shared-storage friendly config; the symlink is checked out as a plain file
        Git.open(dir).use { g ->
            val c = g.repository.config
            assertFalse(c.getBoolean("core", "filemode", true)); assertFalse(c.getBoolean("core", "symlinks", true)); assertTrue(c.getBoolean("core", "ignorecase", false))
        }
        assertFalse(Files.isSymbolicLink(File(dir, "link").toPath())); assertTrue(File(dir, "link").isFile)
        assertTrue(Git.open(dir).use { it.status().call().isClean })
        // Same repo again → reused (pulled), not a second copy
        val again = j(GitCloneTool(gm).execute(args("url" to url(src))))
        assertEquals(true, again["reused"]!!.jsonPrimitive.boolean); assertEquals("Repos/proj", again.s("repo_path"))
        assertFalse(File(shared.root, "Repos/proj-2").exists())
        // Something else already at Repos/<name> → <name>-2, the user's files untouched
        val other = source("notes")
        File(shared.root, "Repos/notes").apply { mkdirs(); File(this, "mine.txt").writeText("keep") }
        val second = j(GitCloneTool(gm).execute(args("url" to url(other))))
        assertEquals("Repos/notes-2", second.s("repo_path"))
        assertEquals("keep", File(shared.root, "Repos/notes/mine.txt").readText())
        // Explicit destination honored (bare name → Repos/<name>)
        assertEquals("Repos/custom", j(GitCloneTool(gm).execute(args("url" to url(other), "path" to "custom"))).s("repo_path"))
        // git_repos lists them with branch + last commit
        val repos = j(GitReposTool(gm).execute(JsonObject(emptyMap())))
        val names = repos["repos"]!!.jsonArray.map { it.jsonObject.s("name") }
        assertEquals(listOf("custom", "notes-2", "proj"), names)
        assertEquals("init", repos["repos"]!!.jsonArray[0].jsonObject.s("last_message"))
    }

    @Test fun `repo argument - bare name, path, single default, choose among several`() = runBlocking {
        val src = source("one")
        GitCloneTool(gm).execute(args("url" to url(src)))
        // Exactly one repo → used without repo_path
        val st = j(GitStatusTool(gm).execute(JsonObject(emptyMap())))
        assertEquals("Repos/one", st.s("repo_path")); assertEquals(File(shared.root, "Repos/one").canonicalPath, st.s("absolute_path"))
        assertEquals("Repos/one", j(GitStatusTool(gm).execute(args("repo_path" to "one"))).s("repo_path"))
        assertEquals("Repos/one", j(GitStatusTool(gm).execute(args("repo_path" to "${SharedFolder.DISPLAY_PATH.let { shared.root.path }}/Repos/one/sub/dir"))).s("repo_path"))
        GitCloneTool(gm).execute(args("url" to url(source("two"))))
        val choose = j(GitStatusTool(gm).execute(JsonObject(emptyMap())))
        assertEquals("CHOOSE_REPO", choose.s("code"))
        assertEquals(listOf("one", "two"), choose["repos"]!!.jsonArray.map { it.jsonObject.s("name") })
        assertEquals("NOT_A_REPO", j(GitLogTool(gm).execute(args("repo_path" to "nope"))).s("code"))
        // log / branch / diff / pull work on Repos paths
        assertEquals("init", j(GitLogTool(gm).execute(args("repo_path" to "one")))["commits"]!!.jsonArray[0].jsonObject.s("message"))
        assertTrue(j(GitBranchTool(gm).execute(args("repo_path" to "one")))["branches"]!!.jsonArray.isNotEmpty())
        File(shared.root, "Repos/one/a.txt").writeText("changed\n")
        assertTrue(j(GitDiffTool(gm).execute(args("repo_path" to "one"))).s("diff")!!.contains("+changed"))
        Git.open(src).use { g -> File(src, "b.txt").writeText("b"); g.add().addFilepattern(".").call()
            g.commit().setMessage("more").setAuthor(PersonIdent("a", "a@x")).setCommitter(PersonIdent("a", "a@x")).call() }
        val pulled = j(GitPullTool(gm).execute(args("repo_path" to "one")))
        assertEquals(pulled.toString(), true, pulled["ok"]?.jsonPrimitive?.boolean)
        assertTrue(File(shared.root, "Repos/one/b.txt").isFile)
    }

    @Test fun `workspace_write into a repo is seen by git_status and committed, empty commits refused`() = runBlocking {
        GitCloneTool(gm).execute(args("url" to url(source("bridge"))))
        val w = j(WorkspaceWriteTool(shared).execute(args("path" to "Repos/bridge/findings/x.md", "content" to "finding")))
        assertEquals(true, w["ok"]!!.jsonPrimitive.boolean)
        // write_file: Repos/… and the <repo>/… shorthand land in the same shared repo
        val wf = j(WriteFileTool(private, shared).execute(args("path" to "bridge/y.md", "content" to "y")))
        assertEquals("Repos/bridge/y.md", wf.s("path")); assertEquals(SharedFolder.DISPLAY_PATH, wf.s("root"))
        assertTrue(File(shared.root, "Repos/bridge/y.md").isFile)
        assertEquals("y", j(ReadFileTool(private, shared).execute(args("path" to "Repos/bridge/y.md"))).s("content"))
        assertEquals("private workspace", j(WriteFileTool(private, shared).execute(args("path" to "scratch.txt", "content" to "s"))).s("root"))
        assertTrue(File(private.root, "scratch.txt").isFile)
        assertEquals("finding", j(WorkspaceReadTool(shared).execute(args("path" to "bridge/findings/x.md"))).s("content"))

        val st = j(GitStatusTool(gm).execute(args("repo_path" to "bridge")))
        val untracked = st["untracked"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(untracked.toString(), "findings/x.md" in untracked && "y.md" in untracked)
        val c = j(GitCommitTool(gm).execute(args("repo_path" to "bridge", "message" to "add")))
        assertEquals(c.toString(), 2, c["files"]!!.jsonPrimitive.int)
        Git.open(File(shared.root, "Repos/bridge")).use { g ->
            val tw = org.eclipse.jgit.treewalk.TreeWalk.forPath(g.repository, "findings/x.md", g.repository.parseCommit(g.repository.resolve("HEAD")).tree)
            assertNotNull("commit must contain the workspace_write file", tw)
        }
        val empty = j(GitCommitTool(gm).execute(args("repo_path" to "bridge", "message" to "again")))
        assertEquals("NOTHING_TO_COMMIT", empty.s("code"))
        assertEquals(File(shared.root, "Repos/bridge").canonicalPath, empty.s("absolute_path"))
        assertTrue(empty.s("hint")!!.contains("workspace_write"))
        assertEquals(true, j(GitCommitTool(gm).execute(args("repo_path" to "bridge", "message" to "e", "allow_empty" to true)))["ok"]!!.jsonPrimitive.boolean)
    }

    @Test fun `legacy private-workspace repos are listed, reachable explicitly, never moved`() = runBlocking {
        val old = File(private.root, "old").apply { mkdirs() }
        Git.init().setDirectory(old).call().close()
        File(old, "f.txt").writeText("x")
        val list = j(GitReposTool(gm).execute(JsonObject(emptyMap())))["repos"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, list.size); assertEquals(true, list[0]["legacy"]!!.jsonPrimitive.boolean); assertEquals("legacy:old", list[0].s("repo_path"))
        assertEquals("NO_REPO", j(GitStatusTool(gm).execute(JsonObject(emptyMap()))).s("code"))
        val bare = j(GitStatusTool(gm).execute(args("repo_path" to "old")))
        assertEquals("NOT_A_REPO", bare.s("code")); assertTrue(bare.toString().contains("legacy:old"))
        val st = j(GitStatusTool(gm).execute(args("repo_path" to "legacy:old")))
        assertEquals(old.canonicalPath, st.s("absolute_path")); assertEquals(GitManager.LEGACY_LABEL, st.s("root"))
        assertTrue(File(old, "f.txt").isFile)
    }

    @Test fun `git_repos scrubs tokens from remotes`() = runBlocking {
        GitCloneTool(gm).execute(args("url" to url(source("tok"))))
        Git.open(File(shared.root, "Repos/tok")).use { g -> g.repository.config.apply { setString("remote", "origin", "url", "https://x-access-token:ghp_SECRETxyz@github.com/o/r.git"); save() } }
        val out = GitManager(private, shared, Auth("ghp_SECRETxyz")).describeRepos().toString()
        assertFalse(out.contains("SECRET")); assertTrue(out.contains("https://github.com/o/r.git"))
    }

    @Test fun `write_file into a shared repo asks before overwriting, private scratch doesn't`() = runBlocking {
        GitCloneTool(gm).execute(args("url" to url(source("g"))))
        var asked = 0
        val guard = FileChangeGuard({ asked++; true }, shared = shared.root, privateRoot = private.root)
        guard.check(0, "write_file", args("path" to "Repos/g/a.txt", "content" to "x"))
        guard.check(0, "write_file", args("path" to "g/a.txt", "content" to "x"))
        guard.check(0, "workspace_write", args("path" to "g/a.txt", "content" to "x"))
        assertEquals(3, asked)
        File(private.root, "n.txt").writeText("n")
        guard.check(0, "write_file", args("path" to "n.txt", "content" to "x"))
        guard.check(0, "write_file", args("path" to "Repos/g/new.txt", "content" to "x"))
        assertEquals(3, asked)
    }
}
