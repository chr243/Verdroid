package com.verdroid.app.data.cookies

import com.verdroid.app.ui.cookies.LoginDetector
import com.verdroid.app.ui.cookies.LoginDetector.Verdict.*
import org.junit.Assert.*
import org.junit.Test

class LoginDetectorTest {
    private val flow = "https://x.com/i/flow/login"

    @Test fun xNeedsAuthToken() {
        assertEquals(NOT_YET, LoginDetector.check("x.com", flow, setOf("guest_id", "ct0"), 2, 0))
        assertEquals(WAIT, LoginDetector.check("x.com", flow, setOf("auth_token"), 2, 0))
        assertEquals(DONE, LoginDetector.check("x.com", flow, setOf("auth_token", "ct0", "twid"), 2, 0))
        assertEquals(DONE, LoginDetector.check("x.com", "https://x.com/home", setOf("auth_token"), 2, 0))
        assertEquals(DONE, LoginDetector.check("x.com", flow, setOf("auth_token"), 2, 1_500))
    }

    @Test fun genericNeedsLeavingLoginAndMoreCookies() {
        val s = "example.org"
        assertEquals(NOT_YET, LoginDetector.check(s, "https://example.org/login", setOf("a", "b", "c"), 1, 5_000))
        assertEquals(NOT_YET, LoginDetector.check(s, "https://example.org/home", setOf("a"), 1, 5_000))
        assertEquals(WAIT, LoginDetector.check(s, "https://example.org/home", setOf("a", "sess"), 1, 0))
        assertEquals(DONE, LoginDetector.check(s, "https://example.org/home", setOf("a", "sess"), 1, 1_000))
        assertTrue(LoginDetector.isLoginUrl("https://accounts.site.com/oauth/authorize?x=1"))
        assertFalse(LoginDetector.isLoginUrl("https://site.com/feed?next=login"))
    }
}
