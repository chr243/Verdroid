package com.verdroid.app.data.prefs

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language override ("system" / "en" / "zh"), applied before any Activity's
 * super.onCreate via attachBaseContext so Compose resources resolve in the chosen locale.
 */
object LocaleHelper {

    /** Wrap the base context so the app process resolves strings for the saved language. */
    fun attachBaseContext(base: Context, language: String): Context {
        val tag = when (language) {
            "en" -> "en"
            "zh" -> "zh-CN"
            else -> return base // system default
        }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Re-apply after a language change; restarting the activity picks it up. */
    fun apply(language: String) {
        val tag = when (language) {
            "en" -> "en"
            "zh" -> "zh-CN"
            else -> null
        }
        if (tag != null) Locale.setDefault(Locale.forLanguageTag(tag))
    }
}
