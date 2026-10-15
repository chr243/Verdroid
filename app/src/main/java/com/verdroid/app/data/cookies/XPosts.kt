package com.verdroid.app.data.cookies

import kotlinx.serialization.json.*

/** Posts scraped from X by the Selenium helper (`xposts`), parsed and formatted for the agent. Pure, unit-tested. */
data class XPost(
    val name: String, val handle: String, val text: String, val time: String, val rel: String, val url: String,
    val quoted: String = "", val social: String = "", val replies: String = "", val reposts: String = "", val likes: String = "",
    val raw: String = "",
)

object XPosts {
    fun parse(arr: JsonArray?): List<XPost> = arr.orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty().trim()
        XPost(s("name"), s("handle"), s("text"), s("time"), s("rel"), s("url").substringBefore("/analytics").substringBefore("/photo/"),
            s("quoted"), s("social"), count(s("replies")), count(s("reposts")), count(s("likes")), cleanRaw(s("raw")))
            .takeIf { it.text.isNotEmpty() || it.raw.isNotEmpty() || it.url.isNotEmpty() }
    }

    /** "1,234 Likes. Like" → "1,234"; "Reply" → "". */
    fun count(ariaLabel: String): String = Regex("""^\s*([\d][\d,.]*\s*[KMB]?)""").find(ariaLabel)?.groupValues?.get(1)?.trim().orEmpty()

    private val CHROME = setOf("Follow", "Subscribe", "Show more", "Translate post", "·", "Ad", "Promoted", "Pinned", "Show this thread")
    /** Fallback article text with UI chrome dropped (buttons, separators, bare counters). */
    fun cleanRaw(raw: String): String = raw.lines().map { it.trim() }
        .filter { it.isNotEmpty() && it !in CHROME && !Regex("""^[\d,.]+[KMB]?$""").matches(it) }
        .joinToString(" ")

    private fun oneLine(t: String) = t.replace(Regex("""\s+"""), " ").trim()

    fun relTime(p: XPost): String = when {
        p.rel.isEmpty() -> p.time
        p.rel.any { it.isLetter() } && p.rel.length <= 4 && p.rel.last() in "smhd" -> p.rel + " ago"
        else -> p.rel
    }

    /** "Post 1: @handle (Name) - text - 1h ago - link" (+ repost/quote/counts when known). */
    fun format(posts: List<XPost>): String = posts.mapIndexed { i, p ->
        buildString {
            append("Post ${i + 1}: ")
            if (p.social.isNotEmpty()) append("[${oneLine(p.social)}] ")
            if (p.handle.isNotEmpty() || p.name.isNotEmpty()) {
                append(p.handle.ifEmpty { p.name }); if (p.handle.isNotEmpty() && p.name.isNotEmpty()) append(" (${p.name})")
            } else append("(unknown author)")
            append(" - ").append(oneLine(p.text.ifEmpty { p.raw }).ifEmpty { "(no text)" })
            if (p.quoted.isNotEmpty()) append(" | quoting: ").append(oneLine(p.quoted))
            relTime(p).takeIf { it.isNotEmpty() }?.let { append(" - ").append(it) }
            val counts = listOfNotNull(p.replies.takeIf { it.isNotEmpty() }?.let { "$it replies" },
                p.reposts.takeIf { it.isNotEmpty() }?.let { "$it reposts" }, p.likes.takeIf { it.isNotEmpty() }?.let { "$it likes" })
            if (counts.isNotEmpty()) append(" - ").append(counts.joinToString(", "))
            if (p.url.isNotEmpty()) append(" - ").append(p.url)
        }
    }.joinToString("\n")
}
