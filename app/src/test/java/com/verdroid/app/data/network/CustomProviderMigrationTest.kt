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

    @Test fun v2KeysBecomeKeyListsAndCustomSlotIsKept() {
        val prefs = p("cp3"); val secure = p("cps3")
        prefs.edit().putString("base_custom", "https://my.example/v1").putString("base_groq", "https://api.groq.com/openai/v1").commit()
        secure.edit().putString("key_groq", "gsk_1").putString("key_custom", "sk_c").commit()
        CustomProviderStore.migrateV2(prefs, secure)
        assertEquals(listOf("gsk_1"), CustomProviderStore.readKeys(secure, "groq").map { it.key })
        assertEquals(listOf("sk_c"), CustomProviderStore.readKeys(secure, "custom").map { it.key })
        assertFalse(secure.contains("key_groq")); assertFalse(secure.contains("key_custom"))
        assertEquals(listOf("custom"), CustomProviderStore.readCustoms(prefs).map { it.id })
        assertEquals("https://my.example/v1", prefs.getString("base_custom", null))
        CustomProviderStore.migrateV2(prefs, secure) // idempotent
        assertEquals(1, CustomProviderStore.readKeys(secure, "groq").size)
        assertEquals(1, CustomProviderStore.readCustoms(prefs).size)
    }

    @Test fun v1ThenV2FromOldestLayout() {
        val prefs = p("cp4"); val secure = p("cps4")
        prefs.edit().putString("preset", "deepseek").commit(); secure.edit().putString("key", "ds_1").commit()
        CustomProviderStore.migrate(prefs, secure); CustomProviderStore.migrateV2(prefs, secure)
        assertEquals(listOf("ds_1"), CustomProviderStore.readKeys(secure, "deepseek").map { it.key })
        assertTrue(CustomProviderStore.readCustoms(prefs).isEmpty())
    }

    @Test fun v2FreshInstallHasNoCustoms() {
        val prefs = p("cp5"); val secure = p("cps5")
        CustomProviderStore.migrateV2(prefs, secure)
        assertTrue(CustomProviderStore.readCustoms(prefs).isEmpty())
        assertTrue(secure.all.isEmpty())
    }
}
