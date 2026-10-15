package com.verdroid.app.data.tools

/** What the phone currently offers; [ToolStatus.of] turns it into "ready" or what a tool still needs. */
data class ToolEnv(
    val shizukuReady: Boolean = false,
    /** Termux installed and Verdroid holds its RUN_COMMAND permission. */
    val termuxReady: Boolean = false,
    val accessibilityOn: Boolean = false,
    val gitToken: Boolean = false,
    /** All files access granted, so Documents/Verdroid is usable. */
    val storageReady: Boolean = false,
    /** rish + its dex copied into files/rish. */
    val rishReady: Boolean = false,
)

data class ToolStatus(val ready: Boolean, val text: String) {
    companion object {
        private val READY = ToolStatus(true, "Ready")

        fun of(name: String, env: ToolEnv): ToolStatus = when {
            name in setOf("read_file", "write_file", "list_dir") -> READY
            name.startsWith("workspace_") -> if (env.storageReady) READY
                else ToolStatus(false, "Needs All files access for Documents/Verdroid (Settings > Tools > Shared folder)")
            name == "web_fetch" || name == "web_search" -> READY
            name == "ebook_translate" ->
                if (env.termuxReady) ToolStatus(true, "Ready if ebook-translate is installed; needs termux-setup-storage + All files access")
                else ToolStatus(false, "Needs Termux + ebook-translate add-on (Settings > Tools)")
            name.startsWith("pdf_") ->
                if (env.termuxReady) ToolStatus(true, "Ready (PDF library installs on first use, or via the pdf-tools add-on); needs termux-setup-storage + All files access")
                else ToolStatus(false, "Needs Termux (Settings > Tools); the PDF library is installed on first use after you agree")
            name == "cookie_open" ->
                if (env.termuxReady) ToolStatus(true, "Beta: ready if chromium-selenium is installed and cookies were imported (cookie_import)")
                else ToolStatus(false, "Needs Termux + the chromium-selenium add-on (Settings > Tools)")
            name in setOf("x_timeline", "x_user_posts", "x_search") ->
                if (env.termuxReady) ToolStatus(true, "Beta: needs chromium-selenium and an X login (Logged-in sites / cookie_login)")
                else ToolStatus(false, "Needs Termux + the chromium-selenium add-on (Settings > Tools)")
            name == "cookie_login" -> ToolStatus(true, "Beta: opens an in-app login screen; the user taps Done")
            name.startsWith("cookie_") -> ToolStatus(true, "Beta: cookies stored encrypted in the app; import from a file in Documents/Verdroid")
            name.startsWith("selenium_") || name == "termux_python" ->
                if (env.termuxReady) ToolStatus(true, "Ready if chromium-selenium is installed (Available to install)" +
                    if (name == "termux_python") "" else "; saving files needs termux-setup-storage in Termux")
                else ToolStatus(false, "Needs Termux + the chromium-selenium add-on (Settings > Tools)")
            name == "termux_run" -> if (env.termuxReady) ToolStatus(true, "Ready if allow-external-apps is on in Termux")
                else ToolStatus(false, "Needs Termux and the Run commands permission (Settings > Tools > Termux)")
            name == "rish_run" -> when {
                !env.rishReady -> ToolStatus(false, "Needs Find/Pick rish in Settings > Shizuku & Git (deploys to /data/local/tmp/verdroid_rish)")
                !env.shizukuReady -> ToolStatus(false, "rish is staged; needs Shizuku running with permission to deploy/run")
                else -> ToolStatus(true, "Ready (runs from /data/local/tmp/verdroid_rish)")
            }
            name == "run_shell" -> if (env.shizukuReady) READY else ToolStatus(false, "Needs Shizuku running with permission (Settings > Shizuku & Git)")
            name == "git_push" || name == "git_clone" -> if (env.gitToken) READY
                else ToolStatus(true, "Ready for public repos — private ones need a Git token (Settings > Shizuku & Git)")
            name.startsWith("git_") -> READY
            name.startsWith("screen_") -> if (env.accessibilityOn) READY else ToolStatus(false, "Needs the Verdroid accessibility service (Settings > Phone > Permissions)")
            else -> READY
        }

        /** First sentence of a tool description, for the list. */
        fun short(description: String): String {
            val i = Regex("""\.(\s|$)""").find(description)?.range?.first
            return (if (i != null && i < 160) description.substring(0, i + 1) else description.take(160)).trim()
        }
    }
}
