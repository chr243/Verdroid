package com.verdroid.app.agent

import com.verdroid.app.data.network.ParsedToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatCallGuardTest {
    private fun c(args: String, name: String = "web_fetch") = listOf(ParsedToolCall("id", name, args))
    @Test fun counts() {
        val g = RepeatCallGuard()
        assertEquals(1, g.observe(c("""{"url":"a","n":1}""")))
        assertEquals(2, g.observe(c("""{ "n":1, "url":"a" }"""))) // key order / whitespace ignored
        assertEquals(3, g.observe(c("""{"url":"a","n":1}""")))
        assertEquals(1, g.observe(c("""{"url":"b","n":1}""")))
        assertEquals(1, g.observe(c("""{"url":"b","n":1}""", "web_search")))
    }
}
