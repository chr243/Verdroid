package com.verdroid.app.domain.share

import com.verdroid.app.domain.share.ShareLogic.QuickAction.*
import com.verdroid.app.ui.OpenChatNav
import com.verdroid.app.ui.Routes
import org.junit.Assert.*
import org.junit.Test

class ShareDraftTest {
    @Test fun `typed text survives re-tapping and switching actions`() {
        var d = ShareDraft("https://a.b", hasFiles = false)
        assertEquals("", d.text); assertEquals("https://a.b", d.link); assertEquals("https://a.b", d.outgoing); assertEquals(ASK, d.selected)
        d = d.select(ASK).edit("my question")
        d = d.select(ASK)
        assertEquals("my question", d.text)
        d = d.select(SUMMARIZE)
        assertEquals("Summarize this page.", d.text); assertEquals("Summarize this page.\n\nhttps://a.b", d.outgoing)
        d = d.edit("Summarize briefly")
        d = d.select(ASK); assertEquals("my question", d.text)
        d = d.select(SUMMARIZE); assertEquals("Summarize briefly", d.text)
        d = d.select(TRANSLATE); assertEquals("Translate this page into English.", d.text); assertEquals("Translate this page into English.\n\nhttps://a.b", d.outgoing)
    }

    @Test fun `clearing a field is kept, not re-prefilled`() {
        val d = ShareDraft("x", false).select(TRANSLATE).edit("").select(ASK).select(TRANSLATE)
        assertEquals("", d.text)
    }

    @Test fun `re-tapping the selected action is a no-op`() {
        val d = ShareDraft("x", true).select(SUMMARIZE).edit("typed")
        assertSame(d, d.select(SUMMARIZE))
    }

    @Test fun `opening a new chat replaces an open different chat`() {
        assertTrue(OpenChatNav.needsNavigate(Routes.CHAT, 5, 9))
        assertFalse(OpenChatNav.needsNavigate(Routes.CHAT, 9, 9))
        assertTrue(OpenChatNav.needsNavigate(Routes.CHATS, null, 9))
        assertTrue(OpenChatNav.needsNavigate(null, null, 9))
    }

    @Test fun `text typed in the default field is Ask's and survives switching`() {
        var d = ShareDraft("https://a.b", false).edit("typed first")
        d = d.select(ASK); assertEquals("typed first", d.text)
        d = d.select(SUMMARIZE); assertTrue(d.text.startsWith("Summarize"))
        d = d.select(ASK); assertEquals("typed first", d.text)
    }

    @Test fun `a shared link opens with Summarize, text and files with Ask`() {
        val link = ShareDraft.initial(ShareLogic.sharedText("Link: https://a.b/c\n\nTitle", null), false)
        assertEquals(SUMMARIZE, link.selected)
        assertEquals("Summarize this page.", link.text); assertEquals("https://a.b/c", link.link)
        assertEquals("Summarize this page.\n\nhttps://a.b/c", link.outgoing)
        assertEquals("my q\n\nhttps://a.b/c", link.select(ASK).edit("my q").outgoing)
        assertNull(ShareDraft.initial("just some text", false).link)
        assertEquals("just some text", ShareDraft.initial("just some text", false).outgoing)
        assertEquals(SUMMARIZE, ShareDraft.initial("https://a.b", false).selected)
        assertEquals(ASK, ShareDraft.initial("just some text", false).selected)
        assertEquals(ASK, ShareDraft.initial("", true).selected)
        assertEquals(ASK, ShareDraft.initial("https://a.b", true).selected)
        // Per-action drafts unchanged: typed Summarize text survives a trip to Ask.
        val d = link.edit("short summary").select(ASK).select(SUMMARIZE)
        assertEquals("short summary", d.text)
        assertSame(link, link.select(SUMMARIZE))
    }

    @Test fun `X profile links are detected`() {
        listOf("https://x.com/elonmusk", "https://x.com/elonmusk/", "https://twitter.com/jack?lang=en", "https://mobile.x.com/a_b",
            "https://www.twitter.com/Jack", "http://www.x.com/user123?s=21&t=abc", "HTTPS://X.COM/someone#top")
            .forEach { assertTrue(it, ShareLogic.isXProfileUrl(it)) }
        listOf("https://x.com/elonmusk/status/123", "https://x.com/home", "https://x.com/explore", "https://x.com/search?q=a",
            "https://x.com/i/flow/login", "https://x.com/settings", "https://x.com/notifications", "https://x.com/messages",
            "https://x.com/hashtag/kotlin", "https://x.com/compose/post", "https://x.com/", "https://x.com",
            "https://evilx.com/elonmusk", "https://x.com.evil.io/elonmusk", "https://github.com/elonmusk",
            "https://x.com/this_handle_is_too_long_x", "https://x.com/a/likes")
            .forEach { assertFalse(it, ShareLogic.isXProfileUrl(it)) }
    }

    @Test fun `X profile opens with Ask who-is prefill, posts and other links keep Summarize`() {
        val p = ShareDraft.initial(ShareLogic.sharedText("https://x.com/elonmusk", "Elon Musk (@elonmusk) / X"), false)
        assertEquals(ASK, p.selected); assertEquals("Who is this person?", p.text); assertEquals("Who is this person? https://x.com/elonmusk", p.outgoing)
        assertTrue(p.select(SUMMARIZE).text.startsWith("Summarize this page."))
        assertEquals(SUMMARIZE, ShareDraft.initial("https://x.com/elonmusk/status/1", false).selected)
        assertEquals(SUMMARIZE, ShareDraft.initial("https://github.com/x", false).selected)
        assertEquals(ASK, ShareDraft.initial("https://x.com/elonmusk", true).selected)
        assertEquals("https://x.com/elonmusk", ShareDraft.initial("https://x.com/elonmusk", true).text)
    }
}
