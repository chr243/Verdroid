package com.verdroid.app.ui.chat

import com.verdroid.app.ui.chat.InlineImages.Seg
import org.junit.Assert.*
import org.junit.Test

class InlineImagesTest {
    @Test fun `splits text and images, ignores code fences`() {
        val s = InlineImages.split("Here:\n![a cat](https://x.com/c.png) nice\n```\n![no](https://x.com/n.png)\n```")
        assertEquals(Seg.Text("Here:"), s[0]); assertEquals(Seg.Image("a cat", "https://x.com/c.png"), s[1])
        assertTrue((s[2] as Seg.Text).text.startsWith(" nice") && "![no]" in (s[2] as Seg.Text).text)
        assertEquals(listOf(Seg.Text("plain")), InlineImages.split("plain"))
    }

    @Test fun `caps images per message`() {
        assertEquals(InlineImages.MAX_IMAGES, InlineImages.split((1..20).joinToString("\n") { "![](https://x.com/$it.png)" }).count { it is Seg.Image })
    }

    @Test fun `resolves only https and image files under Documents Verdroid`() {
        val r = "/tmp/vd-root"
        assertEquals("https://x.com/a.png", InlineImages.resolve("https://x.com/a.png", "")?.url)
        assertEquals("/tmp/vd-root/Output/p.png", InlineImages.resolve("Output/p.png", "plot", r)?.path)
        assertEquals("/tmp/vd-root/Output/p.png", InlineImages.resolve("file:///tmp/vd-root/Output/p.png", null, r)?.path)
        assertEquals("/tmp/vd-root/Output/p.png", InlineImages.resolve("Documents/Verdroid/Output/p.png", null, r)?.path)
        assertNull(InlineImages.resolve("http://x.com/a.png", null, r)); assertNull(InlineImages.resolve("content://media/1", null, r))
        assertNull(InlineImages.resolve("../secret.png", null, r)); assertNull(InlineImages.resolve("/data/data/x.png", null, r))
        assertNull(InlineImages.resolve("Output/notes.txt", null, r))
    }
}
