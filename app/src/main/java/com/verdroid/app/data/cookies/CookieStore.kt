package com.verdroid.app.data.cookies

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Injected browser cookies in EncryptedSharedPreferences (same scheme as API keys / the Git token). App-private only. */
class CookieStore(private val context: Context) : CookieVault {
    private val prefs: SharedPreferences by lazy {
        try { create() } catch (e: Exception) { context.deleteSharedPreferences(FILE); com.verdroid.app.data.secure.KeyWipeNotice.record(context, "browser cookies"); create() }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(context, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    override fun all(): List<BrowserCookie> = BrowserCookies.decode(prefs.getString(KEY, null))
    override fun save(cookies: List<BrowserCookie>) {
        prefs.edit().apply { if (cookies.isEmpty()) remove(KEY) else putString(KEY, BrowserCookies.encode(cookies)) }.apply()
    }

    private companion object { const val FILE = "browser_cookies_secure"; const val KEY = "cookies" }
}
