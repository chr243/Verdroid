package com.verdroid.app.data.network

import org.junit.Assert.*
import org.junit.Test

class ReasoningTextTest {
    private fun body(message: String) = """{"model":"m","choices":[{"finish_reason":"stop","message":$message}]}"""

    @Test fun `openrouter reasoning field`() {
        val p = CompletionParser.parse(body("""{"role":"assistant","content":"Answer","reasoning":"Step 1\nStep 2"}"""))!!
        assertEquals("Answer", p.content); assertEquals("Step 1\nStep 2", p.reasoning)
    }

    @Test fun `reasoning_content alias`() {
        val p = CompletionParser.parse(body("""{"role":"assistant","content":"A","reasoning_content":"thought"}"""))!!
        assertEquals("thought", p.reasoning)
    }

    @Test fun `reasoning_details text and summary, encrypted skipped`() {
        val p = CompletionParser.parse(body("""{"role":"assistant","content":"A","reasoning_details":[
            {"type":"reasoning.summary","summary":"Sum"},{"type":"reasoning.encrypted","data":"xyz"},{"type":"reasoning.text","text":"Raw"}]}"""))!!
        assertEquals("Sum\n\nRaw", p.reasoning)
    }

    @Test fun `no reasoning stays null`() {
        val p = CompletionParser.parse(body("""{"role":"assistant","content":"Hello","reasoning":null}"""))!!
        assertEquals("Hello", p.content); assertNull(p.reasoning)
    }

    @Test fun `inline think block is moved out of content`() {
        val p = CompletionParser.parse(body("""{"role":"assistant","content":"<think>\nhmm ok\n</think>\n\nFinal answer"}"""))!!
        assertEquals("Final answer", p.content); assertEquals("hmm ok", p.reasoning)
    }

    @Test fun `inline split edge cases`() {
        assertEquals(ReasoningText.Split("Answer", "pre"), ReasoningText.splitInline("pre</think>Answer"))
        assertEquals(ReasoningText.Split(null, "cut off mid"), ReasoningText.splitInline("<think>cut off mid"))
        assertEquals(ReasoningText.Split("I think so", null), ReasoningText.splitInline("I think so"))
        assertEquals(ReasoningText.Split("Use <thinking> tags", null), ReasoningText.splitInline("Use <thinking> tags"))
        assertEquals(ReasoningText.Split(null, null), ReasoningText.splitInline(null))
    }

    @Test fun `field and inline merge without duplicates`() {
        val p = CompletionParser.parse(body("""{"role":"assistant","content":"<think>same</think>Hi","reasoning":"same"}"""))!!
        assertEquals("Hi", p.content); assertEquals("same", p.reasoning)
    }

    @Test fun `tool call turn keeps reasoning`() {
        val p = CompletionParser.parse(body("""{"role":"assistant","content":null,"reasoning":"need search",
            "tool_calls":[{"id":"c1","type":"function","function":{"name":"web_search","arguments":"{}"}}]}"""))!!
        assertNull(p.content); assertEquals("need search", p.reasoning); assertEquals("web_search", p.toolCalls.single().name)
    }
}
