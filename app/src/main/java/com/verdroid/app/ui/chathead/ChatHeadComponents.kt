package com.verdroid.app.ui.chathead

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.verdroid.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verdroid.app.domain.model.Task
import com.verdroid.app.ui.components.TaskAvatar

/**
 * Floating chat-head avatar with an unread badge. While [running], an indeterminate progress ring circles the
 * bubble in the 6 dp margin around it (fades in/out; the animation only exists while it is shown).
 */
@Composable
fun ChatHeadAvatar(task: Task?, unread: Int, running: Boolean = false, transparent: Boolean = true) {
    Box {
        androidx.compose.animation.AnimatedVisibility(
            visible = running,
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(320)),
        ) {
            ChatHeadProgressRing(Modifier.fillMaxSize())
        }
        ChatHeadBubble(task, unread, running, transparent)
    }
}

/** Spinning arc whose length breathes, like Material's indeterminate circular indicator, in the theme green. */
@Composable
private fun ChatHeadProgressRing(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "headRing")
    val rotation by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "headRingRotation",
    )
    val sweep by transition.animateFloat(
        40f, 250f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "headRingSweep",
    )
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val inset = 1.dp.toPx() + stroke / 2f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val topLeft = Offset(inset, inset)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        drawArc(color, rotation - 90f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun ChatHeadBubble(task: Task?, unread: Int, running: Boolean, transparent: Boolean) {
    val resId = when {
        transparent && running -> R.raw.chathead_running
        transparent -> R.raw.chathead_idle
        running -> R.raw.chathead_running_white
        else -> R.raw.chathead_idle_white
    }
    val gif = rememberChatHeadGif(resId)
    Box(Modifier.padding(6.dp)) {
        if (gif != null && transparent) {
            // Frameless: same 60 dp footprint as the framed bubble (54 dp + 3 dp padding + border) so size never changes.
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { ChatHeadGifView(gif, Modifier.size(64.dp)) }
        } else {
            Surface(shape = CircleShape, shadowElevation = 6.dp, color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)) {
                Box(Modifier.padding(3.dp)) {
                    if (gif != null) {
                        ChatHeadGifView(gif, Modifier.size(54.dp).clip(CircleShape))
                    } else if (task == null) {
                        // No task attached yet: show the Verdroid mark instead of a generic chat avatar.
                        Image(painterResource(R.drawable.ic_verdroid_logo), contentDescription = "Verdroid", modifier = Modifier.size(54.dp))
                    } else {
                        TaskAvatar(task, size = 54.dp, showStatusDot = false) // no status dot on the chat head; progress ring stays
                    }
                }
            }
        }
        if (unread > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (unread > 9) "9+" else unread.toString(), color = MaterialTheme.colorScheme.onError, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The ✕ target shown at the bottom of the screen while dragging the chat head. */
@Composable
fun DismissTarget(near: Boolean) {
    // Grows with a soft bounce when the head is pulled onto it (Messenger-style magnet feedback).
    val size by animateDpAsState(
        if (near) 76.dp else 60.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dismissSize",
    )
    // Pops in (fade + rise) when a drag starts instead of appearing abruptly.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) }
    Box(
        Modifier.size(84.dp).graphicsLayer {
            alpha = appear.value.coerceIn(0f, 1f)
            translationY = (1f - appear.value) * 24.dp.toPx()
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = if (near) 0.9f else 0.65f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 26.sp)
        }
    }
}

@Composable
fun OverlayPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow chat heads over other apps") },
        text = {
            Text(
                "To float the conversation over other apps, Verdroid needs the \"Display over other apps\" permission.\n\n" +
                    "On HyperOS/MIUI also open Other permissions and allow \"Display pop-up windows while running in the background\".",
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/** Decodes an animated WebP from res/raw (API 28+ AnimatedImageDrawable); null on failure so the icon is the fallback. */
@Composable
private fun rememberChatHeadGif(resId: Int): android.graphics.drawable.AnimatedImageDrawable? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val d = remember(resId) {
        runCatching {
            android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(ctx.resources, resId))
                as? android.graphics.drawable.AnimatedImageDrawable
        }.getOrNull()
    }
    androidx.compose.runtime.DisposableEffect(d) { d?.start(); onDispose { d?.stop() } }
    return d
}

@Composable
private fun ChatHeadGifView(gif: android.graphics.drawable.AnimatedImageDrawable, modifier: Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx -> android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER } },
        update = { iv -> if (iv.drawable !== gif) { iv.setImageDrawable(gif); gif.start() } },
        modifier = modifier,
    )
}
