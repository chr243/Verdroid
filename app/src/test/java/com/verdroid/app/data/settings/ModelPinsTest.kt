package com.verdroid.app.data.settings

import com.verdroid.app.data.network.ChatOutcome
import com.verdroid.app.data.network.PinnedOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ModelPinsTest {
    private val global = listOf("meta/llama:free", "kilo:kilo-auto/free", "prov:groq:llama-3.3")

    @Test fun inheritVsPinned() {
        assertEquals(global, ModelPins.resolve(global, null))
        assertEquals(listOf("anthropic/claude-paid"), ModelPins.resolve(global, ModelPin("anthropic/claude-paid")))
        assertEquals(listOf("prov:groq:llama-3.3"), ModelPins.resolve(global, ModelPin(" prov:groq:llama-3.3 ")))
        assertEquals(listOf("kilo:kilo-auto/free", "meta/llama:free", "prov:groq:llama-3.3"),
            ModelPins.resolve(global, ModelPin("kilo:kilo-auto/free", allowFallback = true)))
        assertEquals(global, ModelPins.resolve(global, ModelPin(" ")))
    }

    @Test fun noFallbackWhenPinnedMeansClearErrors() {
        val pin = ModelPin("anthropic/claude-paid")
        val f = PinnedOutcome.map(pin, "anthropic/claude-paid", ChatOutcome.Failure("HTTP 502 upstream"))
        assertTrue(f is ChatOutcome.Failure && f.message.contains("pinned to anthropic/claude-paid") && f.message.contains("Nothing else was tried"))
        val k = PinnedOutcome.map(pin, "x", ChatOutcome.NoApiKey)
        assertTrue(k is ChatOutcome.Failure && k.message.contains("no API key"))
        val r = PinnedOutcome.map(pin, "x", ChatOutcome.RateLimited(5, "busy"))
        assertTrue(r is ChatOutcome.RateLimited && r.message.startsWith("Pinned model x"))
    }

    @Test fun persistence() = runTest {
        val p = ModelPin("vendor/model|odd", true)
        assertEquals(p, ModelPins.decode(ModelPins.encode(p)))
        assertNull(ModelPins.decode("garbage")); assertNull(ModelPins.decode(null))
        val store = ChatModelPinStore.InMemory()
        store.set(3, ModelPin("a")); assertEquals(ModelPin("a"), store.observe(3).first())
        assertNull(store.pin(4)) // other / new chats inherit
        store.set(3, null); assertNull(store.pin(3))
    }
}
