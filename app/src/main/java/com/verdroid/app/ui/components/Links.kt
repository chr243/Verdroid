package com.verdroid.app.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** Clickable links in chat text (v1.0.56): bare http(s) URLs and Markdown [label](url). Pure detection is unit-tested. */
object Links {
    data class Found(val start: Int, val end: Int, val url: String)

    private val BARE = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?'\"*_"

    /** Bare URLs in [text], with trailing punctuation and unbalanced closing brackets trimmed. */
    fun find(text: String): List<Found> = BARE.findAll(text).mapNotNull { m ->
        var url = m.value
        while (url.isNotEmpty()) {
            val c = url.last()
            url = when {
                c in TRAILING -> url.dropLast(1)
                c == ')' && url.count { it == '(' } < url.count { it == ')' } -> url.dropLast(1)
                c == ']' && url.count { it == '[' } < url.count { it == ']' } -> url.dropLast(1)
                c == '}' && url.count { it == '{' } < url.count { it == '}' } -> url.dropLast(1)
                else -> break
            }
        }
        if (url.length <= "https://".length) null else Found(m.range.first, m.range.first + url.length, url)
    }.toList()

    /** Markdown link `[label](http…)`: group 1 = label, group 2 = url. */
    val MARKDOWN = Regex("""\[([^\]\n]+)]\((https?://(?:[^\s()]|\([^\s()]*\))+)\)""", RegexOption.IGNORE_CASE)

    fun style() = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))

    /** Appends [text] with its bare URLs as clickable links (opened by the platform UriHandler = default browser). */
    fun AnnotatedString.Builder.appendLinked(text: String, styles: TextLinkStyles = style()) {
        var last = 0
        for (f in find(text)) {
            append(text.substring(last, f.start))
            withLink(LinkAnnotation.Url(f.url, styles)) { append(text.substring(f.start, f.end)) }
            last = f.end
        }
        if (last < text.length) append(text.substring(last))
    }

    fun linked(text: String): AnnotatedString = androidx.compose.ui.text.buildAnnotatedString { appendLinked(text) }
}
