package com.verdroid.app.data.network

import com.verdroid.app.data.network.ProviderProbe.Verdict
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class ProviderProbeTest {
    @Test fun `local hosts are recognised`() {
        listOf("http://localhost:11434/v1/", "http://127.0.0.1:1234/v1/", "http://192.168.1.20:11434/v1/", "http://10.0.0.5/v1/",
            "http://172.20.1.1:8080/", "http://ollama.local:11434/v1/", "http://[::1]:11434/v1/")
            .forEach { assertTrue(it, ProviderProbe.isLocal(it)) }
        listOf("https://api.groq.com/openai/v1/", "https://api.openai.com/v1/", "http://172.32.0.1/", "not a url", "")
            .forEach { assertFalse(it, ProviderProbe.isLocal(it)) }
    }

    @Test fun `local server off is skipped, everything else fails`() {
        assertEquals(Verdict.SKIP, ProviderProbe.classify("http://localhost:11434/v1/", ConnectException("Failed to connect to localhost/127.0.0.1:11434")))
        assertEquals(Verdict.SKIP, ProviderProbe.classify("http://192.168.1.5:1234/v1/", SocketTimeoutException("timeout")))
        assertEquals(Verdict.SKIP, ProviderProbe.classify("http://localhost:11434/v1/", IOException("wrapped", ConnectException("refused"))))
        // reachable local server returning errors = real misconfiguration
        assertEquals(Verdict.FAIL, ProviderProbe.classify("http://localhost:11434/v1/", IOException("HTTP 404: not found")))
        // remote cloud providers keep failing on connection errors
        assertEquals(Verdict.FAIL, ProviderProbe.classify("https://api.groq.com/openai/v1/", ConnectException("Failed to connect")))
        // malformed URL
        assertEquals(Verdict.FAIL, ProviderProbe.classify("localhost:11434", IllegalArgumentException("Expected URL scheme")))
        assertEquals("Ollama server not reachable at http://localhost:11434/v1/ (not running?)",
            ProviderProbe.skipNote("Ollama", "http://localhost:11434/v1/"))
    }
}
