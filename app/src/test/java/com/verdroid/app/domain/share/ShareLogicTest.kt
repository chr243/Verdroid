package com.verdroid.app.domain.share

import com.verdroid.app.domain.model.AttachmentText
import com.verdroid.app.domain.share.ShareLogic.QuickAction
import org.junit.Assert.*
import org.junit.Test

class ShareLogicTest {
    @Test fun `images are rejected and documents accepted`() {
        assertFalse(ShareLogic.acceptsMime("image/jpeg"))
        assertFalse(ShareLogic.acceptsFile("application/octet-stream", "a.PNG"))
        listOf("application/pdf", "application/epub+zip", "application/x-mobipocket-ebook", "text/plain", null,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document").forEach { assertTrue(it, ShareLogic.acceptsMime(it)) }
        assertTrue(ShareLogic.acceptsFile("application/pdf", "book.pdf"))
    }

    @Test fun `subject and text combine without duplication`() {
        assertEquals("https://x.y", ShareLogic.sharedText("https://x.y", null))
        assertEquals("Title\nhttps://x.y", ShareLogic.sharedText("https://x.y", "Title"))
        assertEquals("Title https://x.y", ShareLogic.sharedText("Title https://x.y", "Title"))
        assertEquals("Only", ShareLogic.sharedText(null, "Only"))
    }

    @Test fun `quick actions prefill`() {
        assertEquals("Summarize this page.\n\nhttps://a.b/c", ShareLogic.prefill(QuickAction.SUMMARIZE, "https://a.b/c", false))
        assertEquals("Translate this text into English.\n\nhola", ShareLogic.prefill(QuickAction.TRANSLATE, "hola", false))
        assertEquals("Summarize the attached file(s).", ShareLogic.prefill(QuickAction.SUMMARIZE, "", true))
        assertEquals("hola", ShareLogic.prefill(QuickAction.ASK, "hola", false))
    }

    @Test fun `message hides every attachment line in the UI`() {
        val p = listOf(AttachmentText.prefix("Input/a.pdf", 10), AttachmentText.prefix("Input/b.epub", 20))
        val msg = ShareLogic.message(p, "Summarize")
        val d = AttachmentText.forDisplay(msg)
        assertEquals("a.pdf", d.fileName); assertEquals(1, d.moreFiles); assertEquals("Summarize", d.text)
        assertFalse(d.text.contains("Attached file"))
        assertEquals("📎 a.pdf +1 · Summarize", AttachmentText.preview(msg))
        assertEquals("", AttachmentText.forDisplay(ShareLogic.message(p, " ")).text)
        assertEquals("hi", ShareLogic.message(emptyList(), " hi "))
        assertFalse(ShareLogic.canSend(0, " ")); assertTrue(ShareLogic.canSend(1, ""))
    }
}
