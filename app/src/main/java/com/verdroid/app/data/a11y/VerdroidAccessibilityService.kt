package com.verdroid.app.data.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AccessibilityService that performs taps/swipes (dispatchGesture), text input and reads the on-screen node tree.
 * It is a system-bound service with no Hilt injection; tools reach it through the [instance] singleton.
 */
class VerdroidAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        launchers = resolveLaunchers()
        // Make sure key filtering + all window events are on even if the XML of an older install is cached.
        runCatching {
            serviceInfo = serviceInfo.apply {
                eventTypes = eventTypes or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                notificationTimeout = 0
            }
        }.onFailure { HeadLog.d("a11y serviceInfo update failed: ${it.message}") }
        val si = serviceInfo
        HeadLog.d("a11y connected events=0x${Integer.toHexString(si?.eventTypes ?: 0)} flags=0x${Integer.toHexString(si?.flags ?: 0)} " +
            "caps=0x${Integer.toHexString(si?.capabilities ?: 0)} pkgs=${si?.packageNames?.toList()} launchers=$launchers")
    }

    /** 3-button nav: HOME/RECENTS keys reach a11y key filters before the system acts → earliest possible signal. Never consumed. */
    override fun onKeyEvent(event: android.view.KeyEvent?): Boolean {
        val e = event ?: return false
        val l = com.verdroid.app.chathead.HomeSignal.listener
        if (l != null) HeadLog.d("a11y key ${android.view.KeyEvent.keyCodeToString(e.keyCode)} action=${e.action}")
        if (l != null && e.action == android.view.KeyEvent.ACTION_DOWN && com.verdroid.app.chathead.HomeSignal.isLeaveKey(e.keyCode)) {
            l("key:" + android.view.KeyEvent.keyCodeToString(e.keyCode))
        }
        return false
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Default launcher package(s), refreshed on connect (HyperOS: com.miui.home). */
    private var launchers: Set<String> = emptySet()

    private fun resolveLaunchers(): Set<String> = runCatching {
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(home, 0).mapNotNull { it.activityInfo?.packageName }
            .filter { it != packageName && it != "com.android.settings" }.toSet()
    }.getOrDefault(emptySet())

    /** Tools poll the node tree on demand; events are only used for the instant Home signal (chat-head window). */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val l = com.verdroid.app.chathead.HomeSignal.listener ?: return
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> event.packageName?.toString()
            // Our focusable overlay is the *active* window, so rootInActiveWindow is always us. Use the topmost
            // application window instead (windows are ordered top → bottom).
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> topAppPackage()
            else -> return
        }
        HeadLog.d("a11y ${AccessibilityEvent.eventTypeToString(type)} pkg=${event.packageName} top=$pkg")
        if (launchers.isEmpty()) launchers = resolveLaunchers()
        if (com.verdroid.app.chathead.HomeSignal.isHomeOrRecents(pkg, launchers)) l(pkg!!)
    }

    private fun topAppPackage(): String? = runCatching {
        windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() != packageName }
            ?.root?.packageName?.toString()
    }.getOrNull()

    private object HeadLog {
        fun d(m: String) = com.verdroid.app.chathead.HeadLog.d(m)
    }
    override fun onInterrupt() {}

    suspend fun tap(x: Float, y: Float): Boolean = gesture(buildPath(x, y), 0, 60)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, durationMs.coerceIn(50, 10_000))

    private fun buildPath(x: Float, y: Float) = Path().apply { moveTo(x, y); lineTo(x, y) }

    private suspend fun gesture(path: Path, startTime: Long, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = CompletableDeferred<Boolean>()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.complete(true) }
            override fun onCancelled(d: GestureDescription?) { done.complete(false) }
        }, null)
        if (!dispatched) return false
        return withTimeoutOrNull(durationMs + 5_000) { done.await() } ?: false
    }

    /** Finds the first editable (or focused) node and sets its text. */
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findEditable(root) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) findEditable(node.getChild(i))?.let { return it }
        return null
    }

    /** Snapshot of the active window's node tree (bounded depth/breadth) as a readable structure. */
    fun screenTree(maxDepth: Int = 40): A11yNode? {
        val root = rootInActiveWindow ?: return null
        return capture(root, 0, maxDepth)
    }

    private fun capture(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): A11yNode {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val children = if (depth >= maxDepth) emptyList() else
            (0 until node.childCount).mapNotNull { node.getChild(it) }.take(60).map { capture(it, depth + 1, maxDepth) }
        return A11yNode(
            cls = node.className?.toString(),
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            viewId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.viewIdResourceName else null,
            clickable = node.isClickable,
            editable = node.isEditable,
            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    companion object {
        @Volatile var instance: VerdroidAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null
    }
}

/** Serializable-friendly node snapshot. */
data class A11yNode(
    val cls: String?,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val bounds: IntArray,
    val children: List<A11yNode>,
)
