package com.verdroid.app.domain.share

import com.verdroid.app.domain.share.ShareLogic.QuickAction.*
import com.verdroid.app.ui.OpenChatNav
import com.verdroid.app.ui.Routes
import org.junit.Assert.*
import org.junit.Test

class ShareDraftTest {
    @Test fun `typed text survives re-tapping and switching actions`() {
        var d = ShareDraft("https://a.b", hasFiles = false)
        assertEquals("https://a.b", d.text); assertEquals(ASK, d.selected)
        d = d.select(ASK).edit("my question")
        d = d.select(ASK)
        assertEquals("my question", d.text)
        d = d.select(SUMMARIZE)
        assertEquals("Summarize this page.\n\nhttps://a.b", d.text)
        d = d.edit("Summarize briefly")
        d = d.select(ASK); assertEquals("my question", d.text)
        d = d.select(SUMMARIZE); assertEquals("Summarize briefly", d.text)
        d = d.select(TRANSLATE); assertEquals("Translate this page into English.\n\nhttps://a.b", d.text)
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
        val link = ShareDraft.initial("Link: https://a.b/c\n\nTitle", false)
        assertEquals(SUMMARIZE, link.selected)
        assertEquals("Summarize this page.\n\nLink: https://a.b/c\n\nTitle", link.text)
        assertEquals(SUMMARIZE, ShareDraft.initial("https://a.b", false).selected)
        assertEquals(ASK, ShareDraft.initial("just some text", false).selected)
        assertEquals(ASK, ShareDraft.initial("", true).selected)
        assertEquals(ASK, ShareDraft.initial("https://a.b", true).selected)
        // Per-action drafts unchanged: typed Summarize text survives a trip to Ask.
        val d = link.edit("short summary").select(ASK).select(SUMMARIZE)
        assertEquals("short summary", d.text)
        assertSame(link, link.select(SUMMARIZE))
    }
}
