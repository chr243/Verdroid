package com.verdroid.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class LinksTest {
    private fun urls(s: String) = Links.find(s).map { it.url }

    @Test fun bareUrlsTrimTrailingPunctuation() {
        assertEquals(listOf("https://x.com/a/status/1"), urls("Posted: https://x.com/a/status/1."))
        assertEquals(listOf("https://a.com/b"), urls("(see https://a.com/b), then"))
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), urls("https://en.wikipedia.org/wiki/Foo_(bar)!"))
        assertEquals(listOf("http://a.io/x?y=1&z=2#f"), urls("go http://a.io/x?y=1&z=2#f;"))
        assertEquals(listOf("https://a.com", "https://b.com/c"), urls("https://a.com and https://b.com/c?"))
        assertTrue(urls("no links here, ftp://x and https:// alone").isEmpty())
    }

    @Test fun rangesPointAtTheUrl() {
        val s = "x https://a.com/p, y"
        val f = Links.find(s).single()
        assertEquals("https://a.com/p", s.substring(f.start, f.end))
    }

    @Test fun markdownLinks() {
        val m = Links.MARKDOWN.find("Read [the docs](https://example.com/a_(b)) now")!!
        assertEquals("the docs", m.groupValues[1]); assertEquals("https://example.com/a_(b)", m.groupValues[2])
        assertNull(Links.MARKDOWN.find("[x](javascript:alert(1))"))
    }

    @Test fun inlineKeepsTextAndAddsLinks() {
        val a = inline("See [docs](https://e.com) or https://f.com/x. **bold https://g.com**")
        assertEquals("See docs or https://f.com/x. bold https://g.com", a.text)
        val links = a.getLinkAnnotations(0, a.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url }
        assertEquals(listOf("https://e.com", "https://f.com/x", "https://g.com"), links)
    }
}
