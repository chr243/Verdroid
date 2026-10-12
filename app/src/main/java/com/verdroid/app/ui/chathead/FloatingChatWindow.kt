package com.verdroid.app.ui.chathead

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val EmphasizedAccel = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * Floating chat window chrome (v1.0.41): rounded card with a drag grip on top (moves the overlay window), a resize
 * grip in the bottom-right corner, and a scale/fade in from / out toward the bubble ([pivot] = bubble position).
 */
@Composable
fun FloatingChatWindow(
    closing: Boolean,
    pivot: Pair<Float, Float>,
    durationMs: Int,
    onClosed: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(closing) {
        if (closing) { progress.animateTo(0f, tween(durationMs, easing = EmphasizedAccel)); onClosed() }
        else progress.animateTo(1f, tween(durationMs + 80, easing = Emphasized))
    }
    val origin = TransformOrigin(pivot.first, pivot.second)
    Surface(
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxSize().padding(4.dp).graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            scaleX = 0.2f + 0.8f * p; scaleY = 0.2f + 0.8f * p
            transformOrigin = origin
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Drag grip (header strip): moves the floating window.
                Box(
                    Modifier.fillMaxWidth().height(18.dp)
                        .semantics { contentDescription = "Drag to move window" }
                        .pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); onDrag(d.x, d.y) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            }
            // Resize grip (bottom-right corner).
            Box(
                Modifier.align(Alignment.BottomEnd).size(28.dp)
                    .semantics { contentDescription = "Drag to resize window" }
                    .pointerInput(Unit) { detectDragGestures { change, d -> change.consume(); onResize(d.x, d.y) } },
                contentAlignment = Alignment.BottomEnd,
            ) {
                Box(Modifier.padding(6.dp).size(10.dp).clip(RoundedCornerShape(topStart = 10.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)))
            }
        }
    }
}
