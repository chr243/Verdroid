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
        assertEquals("https://x.y", ShareLogic.sharedText("https://x.y", "Title"))
        assertEquals("https://x.y", ShareLogic.sharedText("Title https://x.y", "Title"))
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

    @Test fun `localized link label is dropped and the full URL kept`() {
        val url = "https://github.com/chr243/Verdroid/blob/main/README.md?a=1&b=2#install"
        val out = ShareLogic.sharedText("\"Some selected text\"\nW tym link: $url", null)
        assertEquals(url, out)
        assertFalse(out.contains("W tym"))
        assertEquals(url, ShareLogic.sharedText("W tym link: $url", "W tym link: $url"))
    }

    @Test fun `prefills are English`() {
        val english = Regex("^[\\x20-\\x7E\\n]*$")
        ShareLogic.QuickAction.entries.forEach { a ->
            assertTrue(english.matches(a.label))
            listOf("https://a.b" to false, "plain" to false, "" to true).forEach { (t, f) ->
                val p = ShareLogic.prefill(a, t, f)
                assertTrue(p, english.matches(p))
                assertFalse(p, p.contains("W tym"))
            }
        }
        assertTrue(ShareLogic.prefill(ShareLogic.QuickAction.SUMMARIZE, ShareLogic.sharedText("x\nW tym link: https://a.b", null), false)
            .startsWith("Summarize this page."))
    }

    @Test fun `multiple files all reach the model and the bubble`() {
        assertEquals(listOf("a", "b", "c"), ShareLogic.mergeStreams(listOf("a", "b"), listOf("b", "c")))
        assertEquals(listOf("x", "y"), ShareLogic.mergeStreams(emptyList(), listOf("x", "y")))
        val p = listOf("Input/a.pdf" to 1L, "Input/b.epub" to 2L, "Input/c.txt" to 3L).map { AttachmentText.prefix(it.first, it.second) }
        val msg = ShareLogic.message(p, "Compare them")
        assertEquals(3, Regex("Attached file: ").findAll(msg).count())
        val d = AttachmentText.forDisplay(msg)
        assertEquals(listOf("a.pdf", "b.epub", "c.txt"), d.files.map { it.name })
        assertEquals(listOf(1L, 2L, 3L), d.files.map { it.bytes })
        assertEquals("Input/c.txt", d.files[2].path)
        assertEquals("Compare them", d.text); assertEquals(2, d.moreFiles)
        assertEquals(listOf("a.pdf"), AttachmentText.forDisplay(ShareLogic.message(p.take(1), "")).files.map { it.name })
        assertTrue(AttachmentText.forDisplay("plain").files.isEmpty())
    }

    @Test fun `shared link drops the title and extra text`() {
        val url = "https://wiadomosci.wp.pl/ofensywa-zurka-przekazal-kogo-odsunal-od-orzekania-7339897142413760a"
        val shared = ShareLogic.sharedText("Link:\n$url\n\n86\nOfensywa Żurka. \"Natychmiastowe odsunięcie\" trzech sędziów", null)
        assertEquals(url, shared)
        assertEquals(url, ShareLogic.sharedText("Some title", url))
        assertEquals("Summarize this page.\n\n$url", ShareLogic.prefill(ShareLogic.QuickAction.SUMMARIZE, shared, false))
        assertEquals("Translate this page into English.\n\n$url", ShareLogic.prefill(ShareLogic.QuickAction.TRANSLATE, shared, false))
        val x = ShareLogic.sharedText("Check this guy\nhttps://x.com/elonmusk", null)
        assertEquals("Who is this person? https://x.com/elonmusk", ShareLogic.prefill(ShareLogic.QuickAction.ASK, x, false))
        assertEquals("just some text\nline two", ShareLogic.sharedText("just some text\nline two", null))
    }
}
