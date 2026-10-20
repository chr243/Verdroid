package com.verdroid.app.data.secure

import org.junit.Assert.assertTrue
import org.junit.Test

class KeyWipeNoticeTest {
    @Test fun message() {
        val m = KeyWipeNotice.message(setOf("Git token", "OpenRouter API keys"))
        assertTrue(m.contains("re-enter") && m.contains("Git token") && m.contains("OpenRouter API keys"))
    }
}
