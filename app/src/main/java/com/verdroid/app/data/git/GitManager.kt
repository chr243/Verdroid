package com.verdroid.app.data.git

import com.verdroid.app.agent.tools.SharedFolderSandbox
import com.verdroid.app.agent.tools.WorkspaceSandbox
import com.verdroid.app.data.storage.SharedFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class GitStatusInfo(
    val branch: String?,
    val clean: Boolean,
    val added: Set<String>,
    val changed: Set<String>,
    val modified: Set<String>,
    val removed: Set<String>,
    val missing: Set<String>,
    val untracked: Set<String>,
    val conflicting: Set<String>,
    val path: String = "",
    val absolutePath: String = "",
    val root: String = "",
)

/** Token + author identity for git (implemented by [GitCredentialStore]; a plain fake in tests). */
interface GitAuth {
    val token: String?
    val username: String
    val authorName: String
    val authorEmail: String
}

/** A repo the git tools found (Documents/Verdroid/Repos/<name>, elsewhere in Documents/Verdroid, or the old private workspace). */
data class RepoRef(val dir: File, val path: String, val legacy: Boolean) {
    val name: String get() = dir.name
    val root: String get() = if (legacy) GitManager.LEGACY_LABEL else SharedFolder.DISPLAY_PATH
}

/**
 * JGit 5.13 (Java 8 API level, works on Android 10+). Repos live in the shared Documents/Verdroid tree (the same root
 * workspace_read/workspace_write use): clones default to Documents/Verdroid/Repos/<name>. A repo argument is a bare name
 * (→ Repos/<name>), a path relative to Documents/Verdroid ("Repos/x", or the "x/sub" shorthand), an absolute
 * Documents/Verdroid path, or "legacy:<name>" for repos cloned by older versions into the private filesDir/workspace (they
 * are listed, never moved or deleted). No repo given: the only repo in Repos/, else an error listing the choices.
 * Shared storage can't store POSIX modes or symlinks and is case-insensitive, so those repos get core.fileMode=false,
 * core.symlinks=false (links checked out as small text files) and core.ignorecase=true.
 */
@Singleton
class GitManager internal constructor(
    private val legacy: WorkspaceSandbox,
    private val folder: SharedFolder,
    private val creds: GitAuth,
) {
    @Inject constructor(sandbox: WorkspaceSandbox, store: GitCredentialStore, folder: SharedFolder) : this(sandbox, folder, store)

    private fun credentials() = creds.token?.let { UsernamePasswordCredentialsProvider(creds.username, it) }

    /** Smart-HTTP probe used only to explain a failed clone/push (same host, same credentials git sends). */
    internal var prober: GitErrors.Prober = GitErrors.HTTP
    /** Tests clone from local file:// repos; the app only allows https://. */
    internal var allowFileRemotes = false

    private val shared get() = SharedFolderSandbox(folder)
    val reposDir: File get() = File(folder.root, SharedFolderSandbox.REPOS)

    private fun fail(op: String, url: String, e: Exception, service: String): Nothing {
        if (e is IllegalArgumentException || e is IllegalStateException || e is GitAccessException) throw e
        val token = creds.token
        val probe = if (url.startsWith("http")) runCatching { prober.probe(url, service, creds.username, token) }.getOrNull() else null
        throw GitAccessException(GitErrors.explain(op, url, e, probe, token != null, token))
    }

    private fun err(code: String, msg: String, extra: JsonObjectBuilder.() -> Unit = {}): Nothing =
        throw GitAccessException(buildJsonObject { put("ok", false); put("code", code); put("error", msg); extra() })

    private fun needAccess() {
        if (!folder.hasAccess()) err("NO_STORAGE_ACCESS", "Repos live in ${SharedFolder.DISPLAY_PATH}/Repos, but All files access is not granted. " +
            "Ask the user to open Settings > Tools > Shared folder and tap Grant.")
        folder.ensure()
    }

    private fun isRepo(d: File) = File(d, ".git").exists()

    /** Repos under Documents/Verdroid/Repos, plus legacy ones in the private workspace (never moved). */
    fun repos(includeLegacy: Boolean = true): List<RepoRef> {
        val shared = if (folder.hasAccess()) reposDir.listFiles().orEmpty().filter { it.isDirectory && isRepo(it) }.sortedBy { it.name.lowercase() }
            .map { RepoRef(it.canonicalFile, "${SharedFolderSandbox.REPOS}/${it.name}", false) } else emptyList()
        val old = if (includeLegacy) legacy.root.listFiles().orEmpty().filter { it.isDirectory && isRepo(it) }.sortedBy { it.name.lowercase() }
            .map { RepoRef(it.canonicalFile, "legacy:${it.name}", true) } else emptyList()
        return shared + old
    }

    private fun JsonObjectBuilder.choices(list: List<RepoRef>) = putJsonArray("repos") {
        list.forEach { r -> addJsonObject { put("name", r.name); put("repo_path", r.path); put("absolute_path", r.dir.path); if (r.legacy) put("legacy", true) } }
    }

    /** Resolves a repo argument (see class doc). A path inside a repo resolves to that repo. */
    fun resolveRepo(arg: String?): RepoRef {
        val a = arg?.trim().orEmpty()
        if (a.startsWith("legacy:")) {
            val d = legacy.resolve(a.removePrefix("legacy:"))
            if (!isRepo(d)) err("NOT_A_REPO", "No legacy repo ${a.removePrefix("legacy:")} in the private workspace") { choices(repos()) }
            return RepoRef(d, "legacy:${legacy.relativePath(d)}", true)
        }
        if (a.startsWith("/") && (a == legacy.root.path || a.startsWith(legacy.root.path + "/"))) {
            val d = upToRepo(legacy.resolve(a.removePrefix(legacy.root.path)), legacy.root)
                ?: err("NOT_A_REPO", "Not a git repository: $a")
            return RepoRef(d, "legacy:${legacy.relativePath(d)}", true)
        }
        needAccess()
        if (a.isEmpty() || a == ".") {
            val list = repos(includeLegacy = false)
            when (list.size) {
                1 -> return list[0]
                0 -> err("NO_REPO", "No repos in ${SharedFolder.DISPLAY_PATH}/Repos yet. Clone one with git_clone" +
                    (if (repos().any { it.legacy }) ", or pass repo_path=\"legacy:<name>\" for one in the old private workspace." else ".")) { choices(repos()) }
                else -> err("CHOOSE_REPO", "Several repos in ${SharedFolder.DISPLAY_PATH}/Repos; pass repo_path (a name from the list).") { choices(repos()) }
            }
        }
        val sb = shared
        val rel = if (!a.startsWith("/") && '/' !in a && isRepo(File(reposDir, a))) "${SharedFolderSandbox.REPOS}/$a" else a
        val dir = try { sb.resolve(rel) } catch (e: SecurityException) { err("BAD_PATH", e.message ?: "Path not allowed") }
        val repo = upToRepo(dir, sb.root) ?: err("NOT_A_REPO", "Not a git repository: ${sb.relativePath(dir)} (${dir.path}). " +
            "Repos are in ${SharedFolder.DISPLAY_PATH}/Repos/<name>.") { choices(repos()) }
        return RepoRef(repo, sb.relativePath(repo), false)
    }

    private fun upToRepo(start: File, root: File): File? {
        var d: File? = start
        while (d != null && (d == root || d.path.startsWith(root.path + File.separator))) {
            if (isRepo(d)) return d
            if (d == root) return null
            d = d.parentFile
        }
        return null
    }

    private fun open(ref: RepoRef): Git = Git.open(ref.dir).also { if (!ref.legacy) sharedStorageConfig(it.repository) }

    /** Settings that make JGit behave on Android shared storage (FUSE: no exec bits, no symlinks, case-insensitive). */
    private fun sharedStorageConfig(repo: Repository) {
        val c = repo.config
        val want = mapOf(ConfigConstants.CONFIG_KEY_FILEMODE to false, ConfigConstants.CONFIG_KEY_SYMLINKS to false, "ignorecase" to true)
        val stale = want.filter { (k, v) -> c.getString(ConfigConstants.CONFIG_CORE_SECTION, null, k) != v.toString() }
        if (stale.isEmpty()) return
        stale.forEach { (k, v) -> c.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null, k, v) }
        runCatching { c.save() }
    }

    private fun remoteUrl(repo: Repository, remote: String = "origin") = repo.config.getString("remote", remote, "url")

    private fun sameRemote(a: String?, b: String): Boolean {
        fun norm(u: String) = GitErrors.stripUserInfo(u.trim()).trimEnd('/').removeSuffix(".git").lowercase()
        return a != null && norm(a) == norm(b)
    }

    data class CloneResult(val ref: RepoRef, val branch: String?, val reused: Boolean, val note: String?)

    /**
     * Clone into Documents/Verdroid/Repos/<repo-name> (default) or [path]. The default folder already holding the same repo
     * is reused (fast-forward pull); holding something else → <name>-2, -3… Never overwrites or deletes existing files.
     */
    suspend fun clone(url: String, path: String?, branch: String?, depth: Int?): CloneResult = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || (allowFileRemotes && url.startsWith("file:"))) { "Only https:// remotes are supported" }
        needAccess()
        val name = GitErrors.stripUserInfo(url).trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        val explicit = path?.trim()?.ifBlank { null }
        var dir: File = if (explicit == null) File(reposDir, name)
            else try { shared.resolve(if ('/' !in explicit) "${SharedFolderSandbox.REPOS}/$explicit" else explicit) } catch (e: SecurityException) { err("BAD_PATH", e.message ?: "Path not allowed") }
        dir = dir.canonicalFile
        fun busy(d: File) = d.exists() && (d.isFile || d.listFiles()?.isNotEmpty() == true)
        if (busy(dir) && isRepo(dir)) {
            val same = Git.open(dir).use { sameRemote(remoteUrl(it.repository), url) }
            if (same) {
                val ref = RepoRef(dir, shared.relativePath(dir), false)
                val note = runCatching { pullBlocking(ref) }.fold({ "Already cloned here; pulled: $it" }, { e ->
                    "Already cloned here; pull failed (${(e as? GitAccessException)?.json?.get("error")?.jsonPrimitive?.contentOrNull ?: e.message}). Local copy kept as is." })
                return@withContext CloneResult(ref, Git.open(dir).use { it.repository.branch }, true, note)
            }
        }
        if (busy(dir)) {
            if (explicit != null) err("TARGET_NOT_EMPTY", "Target is not empty and isn't a clone of this repo: ${shared.relativePath(dir)} (${dir.path})")
            var i = 2
            while (busy(File(reposDir, "$name-$i"))) {
                val d = File(reposDir, "$name-$i")
                if (isRepo(d) && Git.open(d).use { sameRemote(remoteUrl(it.repository), url) }) {
                    val ref = RepoRef(d.canonicalFile, shared.relativePath(d), false)
                    val note = runCatching { pullBlocking(ref) }.fold({ "Already cloned here; pulled: $it" }, { "Already cloned here; pull failed. Local copy kept as is." })
                    return@withContext CloneResult(ref, Git.open(d).use { it.repository.branch }, true, note)
                }
                i++
            }
            dir = File(reposDir, "$name-$i").canonicalFile
        }
        val existed = dir.exists()
        dir.parentFile?.mkdirs()
        // Clone by hand (init → shared-storage config → fetch → check out) so core.symlinks/fileMode apply to the first
        // checkout (symlinks become small files instead of failing on FUSE). Same result as `git clone`.
        // depth > 0 → shallow clone isn't supported by JGit 5.x; the parameter is accepted but ignored.
        val head = try {
            Git.init().setDirectory(dir).call().use { git ->
                val repo = git.repository
                // A token embedded in the clone URL is used for this fetch only and never stored (like `git remote set-url`).
                val clean = GitErrors.stripUserInfo(url)
                repo.config.apply {
                    setString("remote", "origin", "url", clean)
                    setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
                    save()
                }
                sharedStorageConfig(repo)
                val res = git.fetch().setRemote(url).setCredentialsProvider(credentials())
                    .setRefSpecs(org.eclipse.jgit.transport.RefSpec("+refs/heads/*:refs/remotes/origin/*")).call()
                val heads = res.advertisedRefs.filter { it.name.startsWith("refs/heads/") }
                val adv = res.getAdvertisedRef("HEAD")
                val target = branch?.removePrefix("refs/heads/")?.ifBlank { null }
                    ?: adv?.takeIf { it.isSymbolic }?.target?.name?.removePrefix("refs/heads/")
                    ?: heads.sortedBy { when (it.name) { "refs/heads/main" -> 0; "refs/heads/master" -> 1; else -> 2 } }
                        .firstOrNull { adv != null && it.objectId == adv.objectId }?.name?.removePrefix("refs/heads/")
                    ?: heads.firstOrNull()?.name?.removePrefix("refs/heads/")
                if (target != null) {
                    val id = repo.resolve("refs/remotes/origin/$target") ?: throw IllegalArgumentException("Branch not found on the remote: $target")
                    repo.updateRef("refs/heads/$target").apply { setNewObjectId(id); update() }
                    repo.updateRef(org.eclipse.jgit.lib.Constants.HEAD).link("refs/heads/$target")
                    repo.config.apply { setString("branch", target, "remote", "origin"); setString("branch", target, "merge", "refs/heads/$target"); save() }
                    git.reset().setMode(ResetCommand.ResetType.HARD).setRef("HEAD").call()
                }
                repo.branch
            }
        } catch (e: Exception) { runCatching { if (!existed) dir.deleteRecursively() else File(dir, ".git").deleteRecursively() }; fail("clone", url, e, "git-upload-pack") }
        CloneResult(RepoRef(dir, shared.relativePath(dir), false), head, false,
            if (dir.name != name) "${SharedFolderSandbox.REPOS}/$name holds something else, so this clone went to ${dir.name}" else null)
    }

    suspend fun status(path: String?): GitStatusInfo = withContext(Dispatchers.IO) {
        val ref = resolveRepo(path)
        open(ref).use { git ->
            val s = git.status().call()
            GitStatusInfo(git.repository.branch, s.isClean, s.added, s.changed, s.modified, s.removed, s.missing, s.untracked, s.conflicting,
                ref.path, ref.dir.path, ref.root)
        }
    }

    data class CommitResult(val id: String, val files: Int, val ref: RepoRef)

    /** Stages everything (adds + removals) and commits. Refuses an empty commit unless [allowEmpty]. */
    suspend fun commit(path: String?, message: String, allowEmpty: Boolean = false): CommitResult = withContext(Dispatchers.IO) {
        val ref = resolveRepo(path)
        open(ref).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            val s = git.status().call()
            val staged = s.added.size + s.changed.size + s.removed.size
            if (staged == 0 && !allowEmpty) err("NOTHING_TO_COMMIT", "Nothing to commit in ${ref.path} (${ref.dir.path}): no changed files there.") {
                put("repo_path", ref.path); put("absolute_path", ref.dir.path); put("root", ref.root)
                put("hint", "Edit files inside this folder first: workspace_write path=\"${if (ref.legacy) "…" else ref.path}/<file>\" (or write_file " +
                    "with the same Repos/… path). Files written elsewhere (e.g. the private workspace) are invisible to this repo. " +
                    "Pass allow_empty=true only if an empty commit is really wanted.")
            }
            val ident = PersonIdent(creds.authorName, creds.authorEmail)
            val id = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).setAllowEmpty(allowEmpty).call().id.name
            CommitResult(id, staged, ref)
        }
    }

    suspend fun push(path: String?, remote: String?, branch: String?): List<String> = withContext(Dispatchers.IO) {
        val cp = credentials() ?: throw IllegalStateException("No Git token set (Settings > Shizuku & Git setup)")
        val ref = resolveRepo(path)
        open(ref).use { git ->
            val cmd = git.push().setRemote(remote?.ifBlank { null } ?: "origin").setCredentialsProvider(cp)
            if (!branch.isNullOrBlank()) cmd.add(branch)
            val url = runCatching { remoteUrl(git.repository, remote?.ifBlank { null } ?: "origin") }.getOrNull().orEmpty()
            val results = try { cmd.call() } catch (e: Exception) { fail("push", url, e, "git-receive-pack") }
            results.flatMap { result -> result.remoteUpdates.map { "${it.remoteName}: ${it.status}${it.message?.let { m -> " ($m)" } ?: ""}" } }
        }
    }

    private fun pullBlocking(ref: RepoRef): String = open(ref).use { git ->
        val url = runCatching { remoteUrl(git.repository) }.getOrNull().orEmpty()
        val r = try { git.pull().setCredentialsProvider(credentials()).setFastForward(org.eclipse.jgit.api.MergeCommand.FastForwardMode.FF_ONLY).call() }
            catch (e: Exception) { fail("pull", url, e, "git-upload-pack") }
        if (!r.isSuccessful) err("PULL_FAILED", "Pull did not fast-forward in ${ref.path}: ${r.mergeResult?.mergeStatus ?: r.rebaseResult?.status ?: "unknown"} " +
            "(local changes or diverged history; nothing was overwritten)")
        r.mergeResult?.mergeStatus?.toString() ?: "ok"
    }

    suspend fun pull(path: String?): Pair<RepoRef, String> = withContext(Dispatchers.IO) { val ref = resolveRepo(path); ref to pullBlocking(ref) }

    data class LogEntry(val id: String, val author: String, val time: Long, val message: String)

    suspend fun log(path: String?, max: Int): Pair<RepoRef, List<LogEntry>> = withContext(Dispatchers.IO) {
        val ref = resolveRepo(path)
        ref to open(ref).use { git ->
            if (git.repository.resolve("HEAD") == null) emptyList()
            else git.log().setMaxCount(max.coerceIn(1, 200)).call().map { c ->
                LogEntry(c.id.name.take(12), c.authorIdent.name, c.commitTime * 1000L, c.shortMessage)
            }
        }
    }

    /** Working-tree changes vs HEAD (staged + unstaged), as a unified diff capped at [maxChars]. */
    suspend fun diff(path: String?, maxChars: Int): Triple<RepoRef, String, Boolean> = withContext(Dispatchers.IO) {
        val ref = resolveRepo(path)
        open(ref).use { git ->
            val out = java.io.ByteArrayOutputStream()
            val head = git.repository.resolve("HEAD^{tree}")
            val cmd = git.diff().setOutputStream(out)
            if (head != null) cmd.setOldTree(git.repository.newObjectReader().use { r -> org.eclipse.jgit.treewalk.CanonicalTreeParser().apply { reset(r, head) } })
            cmd.call()
            val text = GitErrors.scrub(out.toString("UTF-8"), creds.token)
            Triple(ref, text.take(maxChars), text.length > maxChars)
        }
    }

    suspend fun branches(path: String?): Triple<RepoRef, String?, List<String>> = withContext(Dispatchers.IO) {
        val ref = resolveRepo(path)
        open(ref).use { git ->
            Triple(ref, git.repository.branch, git.branchList().setListMode(org.eclipse.jgit.api.ListBranchCommand.ListMode.ALL).call()
                .map { Repository.shortenRefName(it.name) })
        }
    }

    /** One line per repo for git_repos: remote (token-free), branch, last commit. */
    suspend fun describeRepos(): JsonArray = withContext(Dispatchers.IO) {
        JsonArray(repos().map { r ->
            buildJsonObject {
                put("name", r.name); put("repo_path", r.path); put("absolute_path", r.dir.path)
                if (r.legacy) { put("legacy", true); put("note", LEGACY_NOTE) }
                runCatching {
                    Git.open(r.dir).use { git ->
                        remoteUrl(git.repository)?.let { put("remote", GitErrors.scrub(GitErrors.stripUserInfo(it), creds.token)) }
                        put("branch", git.repository.branch)
                        if (git.repository.resolve("HEAD") != null) git.log().setMaxCount(1).call().firstOrNull()?.let { c ->
                            put("last_commit", c.id.name.take(12)); put("last_message", c.shortMessage)
                            put("last_commit_time", java.time.Instant.ofEpochSecond(c.commitTime.toLong()).toString())
                        }
                    }
                }.onFailure { put("error", "${it.javaClass.simpleName}: ${it.message}") }
            }
        })
    }

    companion object {
        const val LEGACY_LABEL = "private app workspace (legacy)"
        const val LEGACY_NOTE = "Cloned by an older Verdroid into the private app workspace; workspace_* tools can't see its files. Use repo_path " +
            "\"legacy:<name>\", or re-clone with git_clone (goes to Documents/Verdroid/Repos). Moving it is a normal move (the user confirms)."
    }
}
