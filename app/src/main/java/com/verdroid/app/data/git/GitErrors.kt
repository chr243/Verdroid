package com.verdroid.app.data.git

import kotlinx.serialization.json.*

/** A clone/push failure already turned into a clear, token-free JSON answer for the agent. */
class GitAccessException(val json: JsonObject) : Exception(json["error"]?.jsonPrimitive?.contentOrNull)

/**
 * Turns JGit's vague failures ("git-upload-pack not permitted", "Invalid remote: origin") into the real HTTP status, the
 * server's own text and a concrete hint (pure, unit-tested). The status comes from a smart-HTTP probe of the same remote
 * (info/refs?service=git-upload-pack | git-receive-pack, Basic x-access-token auth: what git itself sends). The token is
 * never printed: every message is scrubbed.
 */
object GitErrors {
    /** (HTTP status, first lines of the server's text) for [service] on [repoUrl], or null when unreachable. */
    fun interface Prober { fun probe(repoUrl: String, service: String, user: String, token: String?): Pair<Int, String>? }

    val HTTP = Prober { repoUrl, service, user, token ->
        runCatching {
            val u = java.net.URL(infoRefs(repoUrl, service))
            val c = u.openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "git/2.45 (Verdroid probe)")
            token?.let { c.setRequestProperty("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$it".toByteArray())) }
            val code = c.responseCode
            val body = (if (code >= 400) c.errorStream else null)?.use { s -> s.readBytes().take(600).toByteArray().toString(Charsets.UTF_8) }.orEmpty()
            c.disconnect()
            code to body
        }.getOrNull()
    }

    /** https://host/owner/repo(.git) → …/repo.git/info/refs?service=… (credentials in the URL are dropped). */
    fun infoRefs(repoUrl: String, service: String): String {
        val clean = stripUserInfo(repoUrl).trimEnd('/')
        val base = if (clean.endsWith(".git")) clean else "$clean.git"
        return "$base/info/refs?service=$service"
    }

    fun stripUserInfo(url: String): String = url.replace(Regex("""^(https?://)[^/@]*@"""), "$1")

    fun scrub(text: String, token: String?): String {
        var t = text.replace(Regex("""(https?://)[^/@\s]*@"""), "$1")
        if (!token.isNullOrBlank()) t = t.replace(token, "***")
        return t
    }

    /** Lines the server sent ("remote: …" or a plain-text body), without HTML. */
    fun remoteText(messages: String, body: String?): String? {
        val fromJgit = messages.lines().filter { it.contains("remote:", ignoreCase = true) }
            .map { it.substring(it.indexOf("remote:", ignoreCase = true) + 7).trim() }.filter { it.isNotEmpty() }
        val fromBody = body?.takeIf { '<' !in it }?.lines()?.map { it.trim().removePrefix("remote:").trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return (fromJgit + fromBody).distinct().joinToString(" ").take(300).ifBlank { null }
    }

    /**
     * [op] "clone" | "push". [error]: the JGit exception; [probe]: (status, body) from [Prober] or null.
     */
    fun explain(op: String, url: String, error: Throwable, probe: Pair<Int, String>?, hasToken: Boolean, token: String?): JsonObject {
        val chain = generateSequence(error) { it.cause }.take(6).toList()
        val messages = scrub(chain.joinToString("\n") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }, token)
        val all = messages.lowercase()
        val status: Int? = probe?.first?.takeIf { it >= 400 } ?: when {
            Regex("""\b(401)\b|not authorized|authentication is required|can't get username|could not read username""").containsMatchIn(all) -> 401
            Regex("""\b(403)\b|not permitted|write access to repository not granted|resource not accessible|permission to .* denied""").containsMatchIn(all) -> 403
            Regex("""\b(404)\b|repository not found|not found|norepository|invalid remote""").containsMatchIn(all) -> 404
            else -> null
        }
        val remote = remoteText(messages, probe?.second?.let { scrub(it, token) })
        val need = if (op == "push") "Read and write" else "Read"
        val (code, msg, hint) = when (status) {
            401 -> Triple("AUTH_FAILED", "The Git server answered 401: the token is missing, invalid or expired" + (if (!hasToken) " (no Git token is saved)" else ""),
                "Save a valid token in Settings > Shizuku & Git setup. For GitHub, a private repo the token can't see can also answer 401 to git.")
            403 -> Triple("PERMISSION_DENIED", "The Git server answered 403: the token can see this repository but lacks permission to $op it",
                "On a fine-grained GitHub token set Repository permissions → Contents to $need for this repository (repository access alone isn't enough; " +
                    "the repo JSON's permissions.push=true does not prove git can push).")
            404 -> Triple("REPO_NOT_FOUND", "Repository not found or not visible to this token (HTTP 404)",
                "Check the owner/name, and that the token's repository access includes this repo. Private repos answer 404 to anything without access.")
            else -> Triple("GIT_FAILED", "git $op failed", null)
        }
        return buildJsonObject {
            put("ok", false); put("code", code); put("error", msg)
            status?.let { put("http_status", it) }
            put("url", stripUserInfo(url))
            remote?.let { put("remote", it) }
            hint?.let { put("hint", it) }
            put("detail", messages.take(400))
        }
    }
}
