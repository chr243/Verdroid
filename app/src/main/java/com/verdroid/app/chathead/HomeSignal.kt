package com.verdroid.app.chathead

/**
 * Instant "user went Home/Recents" signal from the Accessibility service (TYPE_WINDOW_STATE_CHANGED of the launcher).
 * Overlay windows usually KEEP focus when the launcher comes forward (they sit above it), so focus-loss doesn't fire,
 * and ACTION_CLOSE_SYSTEM_DIALOGS arrives late on HyperOS. The chat-head service listens only while its window is open.
 */
object HomeSignal {
    @Volatile var listener: ((pkg: String) -> Unit)? = null

    /** Packages that mean "home/recents is now in front". */
    fun isHomeOrRecents(pkg: String?, launchers: Set<String>): Boolean =
        pkg != null && (pkg in launchers || pkg in RECENTS)

    /** Hardware/3-button-nav keys that mean "leaving": HOME and APP_SWITCH (Recents). */
    fun isLeaveKey(keyCode: Int): Boolean =
        keyCode == android.view.KeyEvent.KEYCODE_HOME || keyCode == android.view.KeyEvent.KEYCODE_APP_SWITCH

    private val RECENTS = setOf("com.android.systemui.recents", "com.android.quickstep")
    /** Events right after the window opened are ignored (launcher may still settle). */
    const val ARM_DELAY_MS = 400L
}
