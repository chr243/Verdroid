package com.verdroid.app.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Material 3 "emphasized" easing approximation used for container transform and shared axis. */
val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
const val MOTION_MS = 300
/** Container transform (chat row <-> chat) runs a bit longer so the morph reads clearly. */
const val CONTAINER_MS = 400

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

fun chatContainerKey(taskId: Long) = "chat-container-$taskId"

/**
 * Container transform between a chat row and ChatDetailScreen. No-op when there is no shared
 * transition / nav animation scope (previews, chat heads, anything outside the NavHost).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.chatContainer(taskId: Long): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    if (taskId == 0L) return this
    return with(shared) {
        this@chatContainer.sharedBounds(
            rememberSharedContentState(chatContainerKey(taskId)),
            animatedVisibilityScope = anim,
            boundsTransform = BoundsTransform { _, _ -> tween(CONTAINER_MS, easing = EmphasizedEasing) },
            // ScaleToBounds: no per-frame remeasure of the whole chat (RemeasureToBounds dropped most frames on device).
            resizeMode = SharedTransitionScope.ResizeMode.ScaleToBounds(),
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(12.dp)),
        )
    }
}

/** True while the enclosing NavHost destination is still animating in/out (false outside the NavHost). */
@Composable
fun navTransitionRunning(): Boolean = LocalNavAnimatedScope.current?.transition?.isRunning == true
