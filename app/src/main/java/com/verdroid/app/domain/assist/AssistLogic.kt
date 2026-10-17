package com.verdroid.app.domain.assist

/**
 * "Digital assistant app" support (issue #1): [com.verdroid.app.ui.assist.AssistActivity] handles ASSIST / VOICE_COMMAND
 * (long-press Home / power, assistant gesture) with a compact prompt card over the current app.
 */
object AssistLogic {
    const val ACTION_ASSIST = "android.intent.action.ASSIST"
    const val ACTION_VOICE_COMMAND = "android.intent.action.VOICE_COMMAND"
    val ACTIONS = setOf(ACTION_ASSIST, ACTION_VOICE_COMMAND)

    enum class Route { CHAT_HEAD, MAIN_APP }

    fun handles(action: String?): Boolean = action in ACTIONS

    /** After Send: the floating chat head when overlays are allowed, otherwise the chat in the main app. */
    fun route(canDrawOverlays: Boolean): Route = if (canDrawOverlays) Route.CHAT_HEAD else Route.MAIN_APP

    /** Trimmed prompt or null when there is nothing to send. */
    fun prompt(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
}
