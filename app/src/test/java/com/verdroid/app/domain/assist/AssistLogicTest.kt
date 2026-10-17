package com.verdroid.app.domain.assist

import org.junit.Assert.*
import org.junit.Test

class AssistLogicTest {
    @Test fun `assist and voice command are handled`() {
        assertTrue(AssistLogic.handles("android.intent.action.ASSIST"))
        assertTrue(AssistLogic.handles("android.intent.action.VOICE_COMMAND"))
        assertFalse(AssistLogic.handles("android.intent.action.SEND"))
        assertFalse(AssistLogic.handles(null))
    }

    @Test fun `falls back to the main app without overlay permission`() {
        assertEquals(AssistLogic.Route.CHAT_HEAD, AssistLogic.route(true))
        assertEquals(AssistLogic.Route.MAIN_APP, AssistLogic.route(false))
    }

    @Test fun `blank prompts are not sent`() {
        assertNull(AssistLogic.prompt("  \n"))
        assertEquals("hi", AssistLogic.prompt(" hi "))
    }
}

class AssistManifestTest {
    @Test fun `assist activity runs in its own translucent task`() {
        val f = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map { java.io.File(it) }.first { it.exists() }
        val s = f.readText()
        val i = s.indexOf(".ui.assist.AssistActivity")
        val block = s.substring(i, s.indexOf("</activity>", i))
        assertTrue(block, "android:taskAffinity=\"\"" in block)
        assertTrue(block, "android:launchMode=\"singleInstance\"" in block)
        assertTrue(block, "android:excludeFromRecents=\"true\"" in block)
        assertTrue(block, "@style/Theme.Verdroid.Assist" in block)
    }
}
