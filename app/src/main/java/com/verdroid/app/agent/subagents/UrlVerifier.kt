package com.verdroid.app.agent.subagents

/**
 * Checks the URLs in a sub-agent's findings against the URLs it actually saw (tool arguments and tool results).
 * Free models sometimes garble URLs ("x.xcom"); a URL that is very close to a seen one is replaced by it, any other
 * unseen URL is marked "[unverified]". Pure, unit-tested.
 */
object UrlVerifier {
    private val URL = Regex("""https?://[^\s<>"'`\]\[(){}|\\^]+""", RegexOption.IGNORE_CASE)
    private const val TRAIL = ".,;:!?*_~'\""

    data class Report(val text: String, val verified: List<String>, val corrected: Map<String, String>, val unverified: List<String>)

    /** URLs in [text] (JSON-escaped slashes undone, trailing punctuation dropped). */
    fun extract(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return URL.findAll(text.replace("\\/", "/")).map { it.value.trimEnd { c -> c in TRAIL } }.filter { it.length > 8 }.toList()
    }

    /** Comparison key: lower-case scheme/host, no "www.", no trailing slash, no fragment. */
    fun normalize(url: String): String {
        val noFrag = url.substringBefore('#').trimEnd('/')
        val m = Regex("""^(https?)://([^/?]+)(.*)$""", RegexOption.IGNORE_CASE).find(noFrag) ?: return noFrag.lowercase()
        val host = m.groupValues[2].lowercase().removePrefix("www.")
        return "https://" + host + m.groupValues[3]
    }

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** "Very close": at most 2 edits, and at most 1 edit per 12 characters. */
    private fun maxEdits(len: Int) = minOf(2, maxOf(1, len / 12))

    /** The seen URL [url] most likely means, or null. */
    fun closest(url: String, seen: Collection<String>): String? {
        val n = normalize(url)
        seen.firstOrNull { normalize(it) == n }?.let { return it }
        return seen.map { it to distance(n, normalize(it)) }.filter { it.second <= maxEdits(n.length) }
            .minByOrNull { it.second }?.first
    }

    fun verify(findings: String, seen: Set<String>): Report {
        val corrected = linkedMapOf<String, String>()
        val unverified = mutableListOf<String>()
        val verified = linkedSetOf<String>()
        val urls = extract(findings).distinct()
        var text = findings
        for (u in urls.sortedByDescending { it.length }) {
            if (u in seen) { verified += u; continue }
            val fix = closest(u, seen)
            if (fix != null) {
                if (fix != u) { corrected[u] = fix; text = replaceUrl(text, u, fix) }
                verified += fix
            } else { unverified += u; text = replaceUrl(text, u, "$u [unverified]") }
        }
        // Keep findings order for the list.
        val ordered = extract(text).filter { it in verified }.distinct()
        return Report(text, ordered + (verified - ordered.toSet()), corrected, unverified)
    }

    /** Replaces [from] only where it is a whole URL (not a prefix of a longer one). */
    private fun replaceUrl(text: String, from: String, to: String): String =
        Regex(Regex.escape(from) + """(?![\w/%\-.~?=&#+]*[\w/%\-~=&#+])""").replace(text) { to.replace("$", "\\$") }
}
