package com.verdroid.app.chathead

import android.view.KeyEvent

/** Pure rules for the expanded chat-head panel (unit-tested; the service only wires them to Android). */
object ChatHeadPanelPolicy {
    /** Reasons ACTION_CLOSE_SYSTEM_DIALOGS carries for Home / Recents (overlays never get KEYCODE_HOME). */
    val HOME_REASONS = setOf("homekey", "recentapps", "gestureNav", "fs_gesture")

    enum class KeyDecision { IGNORE, CONSUME, COLLAPSE }

    /**
     * Back collapses on ACTION_UP (not cancelled); the DOWN is consumed so nothing else reacts to it. While the
     * keyboard is open the IME consumes the first Back itself (closes the keyboard), so this only sees the second.
     */
    fun onKey(action: Int, keyCode: Int, canceled: Boolean): KeyDecision = when {
        keyCode != KeyEvent.KEYCODE_BACK && keyCode != KeyEvent.KEYCODE_ESCAPE -> KeyDecision.IGNORE
        action == KeyEvent.ACTION_UP && !canceled -> KeyDecision.COLLAPSE
        else -> KeyDecision.CONSUME
    }

    /** Focus loss must persist this long before collapsing (filters transient blips). */
    const val FOCUS_LOSS_CONFIRM_MS = 60L

    /** Collapse only on a focus LOSS after the window actually had focus (the first callbacks may be false). */
    class FocusTracker {
        private var hadFocus = false
        /** @return true when the caller should (after confirming) collapse. */
        fun onFocusChanged(has: Boolean): Boolean {
            if (has) { hadFocus = true; return false }
            return hadFocus
        }
    }

    fun collapseOnSystemDialogs(reason: String?): Boolean = reason != null && reason in HOME_REASONS
}

/** Where the head goes back to when the panel collapses: the position saved on expand, clamped to the screen. */
class HeadPositionStore(private val read: (String) -> Int?, private val write: (String, Int) -> Unit) {
    data class Bounds(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)

    fun save(x: Int, y: Int) { write(KEY_X, x); write(KEY_Y, y) }

    fun restore(fallbackX: Int, fallbackY: Int, b: Bounds): Pair<Int, Int> =
        (read(KEY_X) ?: fallbackX).coerceIn(b.minX, maxOf(b.minX, b.maxX)) to (read(KEY_Y) ?: fallbackY).coerceIn(b.minY, maxOf(b.minY, b.maxY))

    companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}

/** Floating chat window geometry (pure, unit-tested): default size/placement, drag and resize clamping. */
object FloatingWindowGeometry {
    data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    const val DEFAULT_W = 0.85f
    const val DEFAULT_H = 0.60f

    /** ~85% x 60% of the usable area, centred horizontally, a bit above centre (room for the keyboard). */
    fun default(areaW: Int, areaH: Int, top: Int = 0): Rect {
        val w = (areaW * DEFAULT_W).toInt(); val h = (areaH * DEFAULT_H).toInt()
        return Rect((areaW - w) / 2, top + ((areaH - h) * 0.35f).toInt(), w, h)
    }

    /** Keeps at least [grip] px of the window on screen so it can always be dragged back. */
    fun move(r: Rect, dx: Int, dy: Int, areaW: Int, areaH: Int, top: Int, grip: Int): Rect =
        r.copy(x = (r.x + dx).coerceIn(grip - r.w, areaW - grip), y = (r.y + dy).coerceIn(top, top + areaH - grip))

    /** Bottom-right corner resize, between [minW]x[minH] and the screen edge. */
    fun resize(r: Rect, dw: Int, dh: Int, areaW: Int, areaH: Int, top: Int, minW: Int, minH: Int): Rect =
        r.copy(w = (r.w + dw).coerceIn(minW, maxOf(minW, areaW - r.x)), h = (r.h + dh).coerceIn(minH, maxOf(minH, top + areaH - r.y)))

    /** Transform origin (0..1 within the window) pointing at the bubble centre, for the collapse/expand scale. */
    fun pivot(r: Rect, headCx: Int, headCy: Int): Pair<Float, Float> =
        ((headCx - r.x).toFloat() / r.w).coerceIn(0f, 1f) to ((headCy - r.y).toFloat() / r.h).coerceIn(0f, 1f)
}
