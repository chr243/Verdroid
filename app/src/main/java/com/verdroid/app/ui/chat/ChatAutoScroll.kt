package com.verdroid.app.ui.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Stick-to-bottom for the chat list (v1.0.34): new content only scrolls the list when the user was already at (or
 * near) the bottom. Scrolling up to read stops following; scrolling back down resumes it. Sending a message always
 * jumps to the bottom. Opening a chat starts at the newest message.
 */
object ChatScroll {
    /** Item scroll offset past any real item height; LazyColumn clamps it to the end of the content. */
    const val TO_END = 100_000

    /** True when the last item is visible and its bottom is within [thresholdPx] of the viewport end. */
    fun nearBottom(lastVisibleIndex: Int?, totalItems: Int, lastItemBottom: Int, viewportEnd: Int, thresholdPx: Int): Boolean =
        lastVisibleIndex == null || totalItems == 0 ||
            (lastVisibleIndex >= totalItems - 1 && lastItemBottom - viewportEnd <= thresholdPx)

    fun nearBottom(state: LazyListState, thresholdPx: Int): Boolean {
        val info = state.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()
        return nearBottom(last?.index, info.totalItemsCount, last?.let { it.offset + it.size } ?: 0, info.viewportEndOffset, thresholdPx)
    }
}

/**
 * "Follow" flag for [state]. Only a user gesture (drag, plus the fling that follows it) can change it, and it is
 * re-evaluated when that gesture's scrolling stops. Our own programmatic scrolls (opening a chat at the bottom,
 * following new output) never flip it, so the list can't get stuck at the top when a chat opens and its messages /
 * tool calls arrive in separate emissions mid-scroll.
 */
@Composable
fun rememberFollowBottom(state: LazyListState): MutableState<Boolean> {
    val follow = remember(state) { mutableStateOf(true) }
    val threshold = with(LocalDensity.current) { 96.dp.roundToPx() }
    LaunchedEffect(state, threshold) {
        var userGesture = false      // a drag started and its scroll hasn't settled yet
        var gestureScrolled = false  // isScrollInProgress was seen true during that gesture
        launch {
            state.interactionSource.interactions.collect { if (it is DragInteraction.Start) { userGesture = true; gestureScrolled = false } }
        }
        snapshotFlow { state.isScrollInProgress to ChatScroll.nearBottom(state, threshold) }.collect { (scrolling, near) ->
            if (!userGesture) return@collect
            follow.value = near
            if (scrolling) gestureScrolled = true
            else if (gestureScrolled) userGesture = false
        }
    }
    return follow
}
