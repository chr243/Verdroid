package com.verdroid.app.data.update

import kotlinx.serialization.json.*

/** A GitHub release as far as the updater cares. */
data class ReleaseInfo(val tag: String, val version: String, val name: String, val notes: String, val htmlUrl: String?, val asset: ApkAsset?)

/** [sha256]: lowercase hex from GitHub's asset `digest` ("sha256:…"), null on old releases without one. */
data class ApkAsset(val name: String, val url: String, val size: Long, val sha256: String? = null)

/** Pure update logic (unit-tested): version comparison, asset choice, release parsing, auto-check throttle. */
object UpdateLogic {
    const val LATEST_URL = "https://api.github.com/repos/chr243/Verdroid/releases/latest"
    /** Public release page the chats-screen hint opens. */
    const val RELEASES_PAGE = "https://github.com/chr243/Verdroid/releases/latest"

    /**
     * "New version on GitHub" pill next to the title (v1.0.56): only with update checks ON (the user's setting; default
     * unchanged), a cached newer public release, and a network. No extra requests: it reads the cached check result.
     */
    fun showHint(checksEnabled: Boolean, latestCached: String?, current: String, online: Boolean): Boolean =
        checksEnabled && online && latestCached != null && isNewer(latestCached, current)
    const val AUTO_CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    private data class V(val nums: List<Int>, val pre: String?)

    private fun parse(v: String): V? {
        val s = v.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = s.substringBefore('-')
        val pre = s.substringAfter('-', "").ifEmpty { null }
        val nums = core.split('.').map { it.toIntOrNull() ?: return null }
        return if (nums.isEmpty()) null else V(nums, pre)
    }

    /** Numeric semver compare ("1.0.10" > "1.0.9", "v1.2" == "1.2.0", "1.0.5-rc1" < "1.0.5"); null if unparseable. */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until maxOf(x.nums.size, y.nums.size)) {
            val c = (x.nums.getOrElse(i) { 0 }).compareTo(y.nums.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            x.pre == y.pre -> 0
            x.pre == null -> 1
            y.pre == null -> -1
            else -> x.pre.compareTo(y.pre)
        }
    }

    /** First ~[max] chars of release notes for the start-up panel (markdown headers/bullets stripped lightly). */
    fun notesSnippet(notes: String, max: Int = 240): String {
        val t = notes.lines().map { it.trim().trimStart('#', '-', '*', ' ') }.filter { it.isNotBlank() }.joinToString("\n")
        return if (t.length <= max) t else t.take(max).trimEnd() + "…"
    }

    /** GitHub updater default: off for fresh installs, on when an earlier version already used it. */
    fun defaultEnabled(hadPriorUpdaterState: Boolean): Boolean = hadPriorUpdaterState

    fun isNewer(latest: String, current: String): Boolean = (compare(latest, current) ?: 0) > 0

    /**
     * The APK to install: `Verdroid-*.apk` of the installed build type (debug → `-debug.apk`; release → `-release.apk` or plain
     * `Verdroid-v<ver>.apk`), else any `Verdroid-*.apk`,
     * never a non-Verdroid `.apk` (v1.0.64: review #15). Assets with size 0 or no URL are skipped.
     */
    fun pickAsset(assets: List<ApkAsset>, buildType: String = "debug"): ApkAsset? {
        val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.url.startsWith("https://") && it.size > 0 }
        val verdroid = apks.filter { it.name.startsWith("Verdroid-", ignoreCase = true) }
        val exact = verdroid.firstOrNull { it.name.endsWith("-$buildType.apk", ignoreCase = true) }
        // v1.0.40+: release assets are plain `Verdroid-v<ver>.apk` (no build-type suffix).
        val plainRelease = verdroid.firstOrNull { !it.name.endsWith("-debug.apk", ignoreCase = true) }
        return exact ?: (if (buildType == "release") plainRelease else null) ?: verdroid.firstOrNull()
    }

    fun parseRelease(body: String, buildType: String = "debug"): ReleaseInfo? {
        val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        val tag = s("tag_name") ?: return null
        if (o["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { a ->
            val ao = a as? JsonObject ?: return@mapNotNull null
            ApkAsset(ao["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                ao["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                ao["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                sha256Of(ao["digest"]?.jsonPrimitive?.contentOrNull))
        }
        return ReleaseInfo(tag, tag.removePrefix("v").removePrefix("V"), s("name") ?: tag, s("body").orEmpty(), s("html_url"), pickAsset(assets, buildType))
    }

    /** "sha256:<64 hex>" → hex; anything else → null. */
    fun sha256Of(digest: String?): String? {
        val h = digest?.trim()?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substring(7)?.lowercase() ?: return null
        return h.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Download check: a published digest must match; without one only the size check applies. */
    fun digestOk(expected: String?, actualHex: String): Boolean = expected == null || expected.equals(actualHex, ignoreCase = true)

    fun autoCheckDue(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0 || nowMs - lastCheckMs >= AUTO_CHECK_EVERY_MS || nowMs < lastCheckMs
}
