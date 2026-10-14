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
     * full URL is kept (never truncated), and text that comes with a link (titles etc.) is dropped: only the URL(s) remain.
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
        // A shared link carries only the link: titles, counters and other text the sender adds are dropped.
        return if (urls.isEmpty()) combined else urls.joinToString("\n")
    }

    fun containsUrl(text: String): Boolean = URL_RE.containsMatchIn(text)

    private val X_HOSTS = setOf("x.com", "twitter.com", "mobile.x.com", "mobile.twitter.com", "www.x.com", "www.twitter.com")
    private val X_RESERVED = setOf("home", "explore", "search", "i", "settings", "notifications", "messages", "hashtag",
        "compose", "login", "logout", "signup", "tos", "privacy", "share", "intent", "account", "about", "jobs")
    private val X_PROFILE_RE = Regex("^https?://([^/?#]+)/([A-Za-z0-9_]{1,15})/?(?:[?#].*)?$", RegexOption.IGNORE_CASE)

    /** True for an X/Twitter profile link (x.com/<handle>), not posts, reserved pages or other sites. */
    fun isXProfileUrl(url: String): Boolean {
        val m = X_PROFILE_RE.matchEntire(url.trim()) ?: return false
        return m.groupValues[1].lowercase() in X_HOSTS && m.groupValues[2].lowercase() !in X_RESERVED
    }

    /** The X profile URL in [text], if any (first URL only). */
    fun xProfileUrl(text: String): String? =
        URL_RE.find(text)?.value?.trimEnd('.', ',', ')', ';')?.takeIf(::isXProfileUrl)

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
            QuickAction.ASK -> if (!hasFiles) xProfileUrl(text)?.let { return "Who is this person? $it" } ?: "" else ""
        }
        return listOf(head, text.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** Instruction-only prefill when a link is shown separately (read-only line); [action] null = plain/empty. */
    fun instruction(action: QuickAction?, link: String): String = when (action) {
        QuickAction.SUMMARIZE -> "Summarize this page."
        QuickAction.TRANSLATE -> "Translate this page into English."
        QuickAction.ASK -> if (xProfileUrl(link) != null) X_QUESTION else ""
        null -> ""
    }

    const val X_QUESTION = "Who is this person?"

    /** Final text = instruction + link: a one-line question gets "<q> <url>", anything else "<text>\n\n<url>". */
    fun withLink(text: String, link: String?): String {
        val t = text.trim()
        return when {
            link == null -> t
            t.isEmpty() -> link
            t.endsWith("?") && '\n' !in t -> "$t $link"
            else -> "$t\n\n$link"
        }
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

    /** Shares always use Bubble mode (compact sheet, then the chat head); without "Display over other apps" → Full app. */
    fun shareUsesBubble(canDrawOverlays: Boolean): Boolean = canDrawOverlays

    /**
     * A share-spawned chat head is hidden while the main app is in front; on leaving the app it only comes back when the
     * chat still has a reason to show (unread reply or reply still running) and overlays are allowed.
     */
    fun restoreShareHead(unread: Int, running: Boolean, canDrawOverlays: Boolean): Boolean =
        canDrawOverlays && (unread > 0 || running)

}

/**
 * Message box state of the share sheet. Each quick action (and "none" = the plain shared text) keeps its own text, so
 * switching actions never discards what the user typed: an action's template is only used while its field is untouched,
 * and tapping the already-selected action is a no-op.
 */
data class ShareDraft(
    val shared: String,
    val hasFiles: Boolean,
    /** Ask is selected from the start, so text typed before tapping any action is Ask's draft and is kept. */
    val selected: ShareLogic.QuickAction? = ShareLogic.QuickAction.ASK,
    private val edited: Map<ShareLogic.QuickAction?, String> = emptyMap(),
) {
    /** Shared link (no files): shown read-only under the title, kept out of the editable field, appended on Send. */
    val link: String? get() = if (!hasFiles && ShareLogic.containsUrl(shared)) shared.trim() else null

    /** What the message field shows. */
    val text: String get() = edited[selected] ?: template(selected)

    /** What is sent to the agent: the field text plus the link. */
    val outgoing: String get() = ShareLogic.withLink(text, link)

    private fun template(a: ShareLogic.QuickAction?): String = link?.let { ShareLogic.instruction(a, it) }
        ?: if (a == null) shared else ShareLogic.prefill(a, shared, hasFiles)

    fun select(a: ShareLogic.QuickAction): ShareDraft = if (a == selected) this else copy(selected = a)

    fun edit(newText: String): ShareDraft = copy(edited = edited + (selected to newText))

    companion object {
        /** Sheet start: Ask ("Who is this person?") for an X profile link, Summarize for other links, Ask for text/files. */
        fun initial(shared: String, hasFiles: Boolean): ShareDraft = ShareDraft(shared, hasFiles, when {
            hasFiles -> ShareLogic.QuickAction.ASK
            ShareLogic.xProfileUrl(shared) != null -> ShareLogic.QuickAction.ASK
            ShareLogic.containsUrl(shared) -> ShareLogic.QuickAction.SUMMARIZE
            else -> ShareLogic.QuickAction.ASK
        })
    }
}
