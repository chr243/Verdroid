package com.verdroid.app.data.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceTextTest {
    @Test fun stripsMarkdownAndCode() {
        val s = VoiceText.forSpeech("# Title\n**Bold** and _it_ ~~gone~~\n```py\nprint(1)\n```\n- item one\n1. step\n> quote\n[link](https://a.b) `x` https://c.d/e ![img](p.png)")
        assertFalse(s, s.contains("print(1)")); assertTrue(s.contains("code omitted"))
        listOf("#", "**", "```", "`", "https", "](", "~~").forEach { assertFalse("$it in $s", s.contains(it)) }
        listOf("Title", "Bold and it gone", "item one", "step", "quote", "link", "img").forEach { assertTrue("$it missing in $s", s.contains(it)) }
    }
    @Test fun thinkAndTables() {
        val s = VoiceText.forSpeech("<think>secret</think>| a | b |\n|---|---|\n| 1 | 2 |")
        assertFalse(s.contains("secret")); assertFalse(s.contains("---")); assertTrue(s.contains("a") && s.contains("2"))
    }
    @Test fun unclosedFenceDropped() { assertFalse(VoiceText.forSpeech("Hi\n```\nlong code").contains("long code")) }
    @Test fun blank() { assertEquals("", VoiceText.forSpeech(null)); assertTrue(VoiceText.chunks("  ").isEmpty()) }
    @Test fun chunksRespectMax() {
        val parts = VoiceText.chunks("Sentence one. ".repeat(1000) + "x".repeat(9000), 500)
        assertTrue(parts.all { it.length <= 500 }); assertEquals(("Sentence one. ".repeat(1000) + "x".repeat(9000)).replace(" ", "").length, parts.joinToString("").replace(" ", "").length)
    }
    @Test fun mergeAndAutoSend() {
        assertEquals("hello", VoiceText.merge("", " hello "))
        assertEquals("typed hello", VoiceText.merge("typed ", "hello"))
        assertEquals("typed", VoiceText.merge("typed", "  "))
        assertTrue(VoiceText.shouldAutoSend(true, "", "hi"))
        assertFalse(VoiceText.shouldAutoSend(true, "draft", "hi"))
        assertFalse(VoiceText.shouldAutoSend(false, "", "hi"))
        assertFalse(VoiceText.shouldAutoSend(true, "", " "))
    }
    @Test fun handsFreeAndSpeakOnce() {
        assertTrue(VoiceText.listenAfterSpeech(true, true, false, false))
        assertFalse(VoiceText.listenAfterSpeech(true, false, false, false))
        assertFalse(VoiceText.listenAfterSpeech(true, true, true, false))
        assertFalse(VoiceText.listenAfterSpeech(true, true, false, true))
        assertTrue(VoiceText.shouldSpeak(true, false, true, true, 9L, 3L))
        assertFalse(VoiceText.shouldSpeak(true, true, true, true, 9L, 3L))
        assertFalse(VoiceText.shouldSpeak(true, false, false, true, 9L, 3L))
        assertFalse(VoiceText.shouldSpeak(true, false, true, false, 9L, 3L))
        assertFalse(VoiceText.shouldSpeak(true, false, true, true, 9L, 9L))
    }
    @Test fun errors() {
        assertTrue(VoiceText.isSilence(7) && !VoiceText.isSilence(9))
        assertTrue(VoiceText.errorMessage(9).contains("permission"))
    }
}
