package com.verdroid.app.data.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CustomProviderMigrationTest {
    private fun p(n: String) = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences(n, Context.MODE_PRIVATE)

    @Test fun singleSlotMovesToPerProvider() {
        val prefs = p("cp"); val secure = p("cps")
        prefs.edit().putString("preset", "groq").putString("base", "https://api.groq.com/openai/v1/").putString("headers", "X: 1").commit()
        secure.edit().putString("key", "gsk_1").commit()
        CustomProviderStore.migrate(prefs, secure)
        assertEquals("groq", prefs.getString(CustomProviderStore.KEY_ACTIVE, null))
        assertEquals("https://api.groq.com/openai/v1/", prefs.getString("base_groq", null))
        assertEquals("X: 1", prefs.getString("headers_groq", null))
        assertEquals("gsk_1", secure.getString("key_groq", null))
        assertFalse(prefs.contains("preset")); assertFalse(secure.contains("key"))
        CustomProviderStore.migrate(prefs, secure) // idempotent
        assertEquals("gsk_1", secure.getString("key_groq", null))
    }

    @Test fun freshInstallNoop() {
        val prefs = p("cp2"); val secure = p("cps2")
        CustomProviderStore.migrate(prefs, secure)
        assertTrue(prefs.all.isEmpty() && secure.all.isEmpty())
    }
}
