package com.verdroid.app.domain.share

import com.verdroid.app.domain.model.AttachmentText

/**
 * Pure logic behind "Share to Verdroid" (ui/share/ShareActivity): which shares are accepted, the quick-action
 * prefills, and the final model-facing message. No Android classes, JVM-tested.
 */
object ShareLogic {
    /** Images are deliberately not a share target (user decision); everything document-like is. */
    fun acceptsMime(mime: String?): Boolean {
        val m = mime?.lowercase()?.trim().orEmpty()
        return !m.startsWith("image/") && !m.startsWith("video/") && !m.startsWith("audio/")
    }

    /** Also filters by name when the sender gives no / a generic MIME type. */
    fun acceptsFile(mime: String?, name: String?): Boolean =
        acceptsMime(mime) && (name == null || !AttachmentText.isImageName(name))

    private val URL_RE = Regex("https?://[^\\s<>\"]+")
    /** "label: URL" lines in the sender's language (e.g. Chrome's Polish "W tym link: …"); the label is dropped. */
    private val LABELLED_URL_LINE = Regex("^\\s*[^:/\\n]{1,40}:\\s*(https?://\\S+)\\s*$")

    /**
     * Shared subject + text cleaned into English-only structure: localized "label: URL" lines lose their label, the
     * full URL is kept (never truncated), and text that comes with a link is laid out as "Link: <url>" + the text.
     */
    fun sharedText(text: String?, subject: String?): String {
        fun clean(v: String?) = v.orEmpty().lines().map { l -> LABELLED_URL_LINE.find(l)?.groupValues?.get(1) ?: l }
            .joinToString("\n").trim()
        val t = clean(text)
        val s = clean(subject)
        val combined = when {
            s.isEmpty() || t.contains(s) -> t
            t.isEmpty() -> s
            else -> "$s\n$t"
        }
        val urls = URL_RE.findAll(combined).map { it.value.trimEnd('.', ',', ')', ';') }.distinct().toList()
        if (urls.isEmpty()) return combined
        var rest = combined
        urls.forEach { rest = rest.replace(it, "") }
        rest = rest.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val links = urls.joinToString("\n") { "Link: $it" }
        return if (rest.isEmpty()) urls.joinToString("\n") else "$links\n\n$rest"
    }

    fun isLink(text: String): Boolean = Regex("^https?://\\S+$").matches(text.trim())

    enum class QuickAction(val label: String) { SUMMARIZE("Summarize"), TRANSLATE("Translate"), ASK("Ask") }

    /** Prefill for the editable message box. [hasFiles] = files were shared; [text] = shared text/link. */
    fun prefill(action: QuickAction, text: String, hasFiles: Boolean): String {
        val what = when {
            hasFiles -> "the attached file(s)"
            isLink(text) || text.trimStart().startsWith("Link: ") -> "this page"
            else -> "this text"
        }
        val head = when (action) {
            QuickAction.SUMMARIZE -> "Summarize $what."
            QuickAction.TRANSLATE -> "Translate $what into English."
            QuickAction.ASK -> ""
        }
        return listOf(head, text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** Message to send: one attachment line per saved file (hidden in the UI by [AttachmentText]) + the user's text. */
    fun message(attachmentPrefixes: List<String>, userText: String): String {
        val t = userText.trim()
        if (attachmentPrefixes.isEmpty()) return t
        return attachmentPrefixes.joinToString("") + t.ifEmpty { AttachmentText.DEFAULT_PROMPT }
    }

    /** EXTRA_STREAM entries first, then ClipData items not already listed (senders fill either or both). */
    fun <T> mergeStreams(extra: List<T>, clip: List<T>): List<T> = (extra + clip).distinct()

    fun canSend(fileCount: Int, userText: String): Boolean = fileCount > 0 || userText.isNotBlank()
}
