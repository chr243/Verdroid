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
