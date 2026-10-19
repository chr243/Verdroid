package com.verdroid.app.data.cookies

import com.verdroid.app.ui.cookies.LoginDetector
import org.junit.Assert.*
import org.junit.Test

class LoginDetectorTest {
    /** The login screen never closes by itself (all sites): no auto-complete API is left to call from page loads. */
    @Test fun noAutoCompleteAnyMore() {
        val names = LoginDetector::class.java.declaredMethods.map { it.name }.toSet()
        assertFalse(names.toString(), names.any { it == "check" || it.startsWith("check$") })
        assertTrue("isBlank" in names && "blankHint" in names)
        val activity = java.io.File("src/main/java/com/verdroid/app/ui/cookies/CookieLogin.kt").takeIf { it.isFile }?.readText()
        if (activity != null) {
            val body = activity.substringAfter("class CookieLoginActivity")
            assertFalse(body.contains("autoCheck") || body.contains("doUpdateVisitedHistory"))
            assertTrue(body.contains("done(url, save = true)"))
            assertTrue(body.contains("CookieLoginBridge.TIMEOUT_MS"))
        }
    }
}
