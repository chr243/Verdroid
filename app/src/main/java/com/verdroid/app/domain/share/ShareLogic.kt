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

    /** Shared text, with the subject (often a page title) in front when it adds something. */
    fun sharedText(text: String?, subject: String?): String {
        val t = text?.trim().orEmpty(); val s = subject?.trim().orEmpty()
        return when {
            s.isEmpty() || t.contains(s) -> t
            t.isEmpty() -> s
            else -> "$s\n$t"
        }
    }

    fun isLink(text: String): Boolean = Regex("^https?://\\S+$").matches(text.trim())

    enum class QuickAction(val label: String) { SUMMARIZE("Summarize"), TRANSLATE("Translate"), ASK("Ask") }

    /** Prefill for the editable message box. [hasFiles] = files were shared; [text] = shared text/link. */
    fun prefill(action: QuickAction, text: String, hasFiles: Boolean): String {
        val what = when {
            hasFiles && text.isNotBlank() -> "the attached file(s)"
            hasFiles -> "the attached file(s)"
            isLink(text) -> "this page"
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

    fun canSend(fileCount: Int, userText: String): Boolean = fileCount > 0 || userText.isNotBlank()
}
