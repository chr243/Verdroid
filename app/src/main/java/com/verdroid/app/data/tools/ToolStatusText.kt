package com.verdroid.app.data.tools

import android.content.Context
import com.verdroid.app.R

/** Maps the pure English status texts of [ToolStatus] to localized resources. */
object ToolStatusText {
    private val MAP: Map<String, Int> = mapOf(
        "Beta, posts as you: asks Post / Cancel first (unless allowed)" to R.string.ts_00,
        "Beta: cookies stored encrypted in the app; import from a file in Documents/Verdroid" to R.string.ts_01,
        "Beta: needs chromium-selenium and an X login (Settings > Tools > Web & logins)" to R.string.ts_02,
        "Beta: opens an in-app login screen; the user taps Done" to R.string.ts_03,
        "Beta: ready if chromium-selenium is installed and you logged in to the site (Settings > Tools > Web & logins)" to R.string.ts_04,
        "Needs All files access for Documents/Verdroid (Settings > Tools > Shared folder)" to R.string.ts_05,
        "Needs Find/Pick rish in Settings > Shizuku & Git (deploys to /data/local/tmp/verdroid_rish)" to R.string.ts_06,
        "Needs Shizuku running with permission (Settings > Shizuku & Git)" to R.string.ts_07,
        "Needs Termux (Settings > Tools); the PDF library is installed on first use after you agree" to R.string.ts_08,
        "Needs Termux + ebook-translate add-on (Settings > Tools)" to R.string.ts_09,
        "Needs Termux + the chromium-selenium add-on (Settings > Tools)" to R.string.ts_10,
        "Needs Termux and the Run commands permission (Settings > Tools > Terminal)" to R.string.ts_11,
        "Needs the Verdroid accessibility service (Settings > Phone > Permissions)" to R.string.ts_12,
        "Ready" to R.string.ts_13,
        "Ready (PDF library installs on first use, or via the pdf-tools add-on); needs termux-setup-storage + All files access" to R.string.ts_14,
        "Ready (runs from /data/local/tmp/verdroid_rish)" to R.string.ts_15,
        "Ready for public repos — private ones need a Git token (Settings > Shizuku & Git)" to R.string.ts_16,
        "Ready if allow-external-apps is on in Termux" to R.string.ts_17,
        "Ready if chromium-selenium is installed (Settings > Tools > Terminal > Verdroid add-ons)" to R.string.ts_18,
        "Ready if ebook-translate is installed; needs termux-setup-storage + All files access" to R.string.ts_19,
        "rish is staged; needs Shizuku running with permission to deploy/run" to R.string.ts_20,
    )

    fun localize(ctx: Context, text: String): String = MAP[text]?.let { ctx.getString(it) } ?: text
}
