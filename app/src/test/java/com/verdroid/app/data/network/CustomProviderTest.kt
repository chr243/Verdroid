package com.verdroid.app.data.network

import org.junit.Assert.*
import org.junit.Test

class CustomProviderTest {
    @Test fun normalizeUrls() {
        assertEquals("https://api.openai.com/v1/", ProviderUrls.normalize("api.openai.com/v1"))
        assertEquals("https://api.groq.com/openai/v1/", ProviderUrls.normalize(" https://api.groq.com/openai/v1/chat/completions "))
        assertEquals("http://localhost:11434/v1/", ProviderUrls.normalize("localhost:11434/v1/models"))
        assertEquals("http://192.168.1.20:1234/v1/", ProviderUrls.normalize("192.168.1.20:1234/v1"))
        assertNull(ProviderUrls.normalize(""))
        assertNull(ProviderUrls.normalize("ftp://x.y/"))
        assertNull(ProviderUrls.normalize("has space.com"))
    }

    @Test fun lanDetection() {
        listOf("localhost", "127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.0.10", "pc.local").forEach { assertTrue(it, ProviderUrls.isLocalOrLan(it)) }
        listOf("8.8.8.8", "172.32.0.1", "api.openai.com", "192.169.1.1").forEach { assertFalse(it, ProviderUrls.isLocalOrLan(it)) }
        assertTrue(ProviderUrls.cleartextAllowed("http://localhost:11434/v1/"))
        assertFalse(ProviderUrls.cleartextAllowed("http://api.example.com/v1/"))
        assertTrue(ProviderUrls.cleartextAllowed("https://api.example.com/v1/"))
    }

    @Test fun headerSelection() {
        assertTrue(ProviderUrls.isOpenRouterHost("openrouter.ai"))
        assertFalse(ProviderUrls.isOpenRouterHost("api.kilo.ai"))
        assertFalse(ProviderUrls.isOpenRouterHost("evilopenrouter.ai"))
        val h = ProviderUrls.requestHeaders("sk-1", ProviderUrls.parseHeaders("X-Org: a\nAuthorization: nope\nbad line\n: x"))
        assertEquals(mapOf("X-Org" to "a", "Authorization" to "Bearer sk-1"), h)
        assertEquals(emptyMap<String, String>(), ProviderUrls.requestHeaders(null, emptyMap()))
    }

    @Test fun modelIdsAndParsing() {
        assertEquals(ModelProvider.CUSTOM, ModelIds.provider("custom:llama3"))
        assertEquals("llama3", ModelIds.bare("custom:llama3"))
        assertEquals("llama3 (Custom)", ModelIds.label("custom:llama3"))
        assertTrue(ModelIds.isOpenRouter("openrouter/free"))
        assertEquals(listOf("a", "b"), ProviderUrls.parseModels("""{"data":[{"id":"b"},{"id":"a"}]}"""))
        assertEquals(listOf("x"), ProviderUrls.parseModels("""[{"id":"x"}]"""))
        assertEquals(emptyList<String>(), ProviderUrls.parseModels("<html>"))
    }
}
