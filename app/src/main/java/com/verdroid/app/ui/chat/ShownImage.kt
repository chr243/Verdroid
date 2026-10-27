package com.verdroid.app.ui.chat

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.verdroid.app.agent.tools.ShownImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/** What a show_image result points at (only path/URL are stored in the message, never bytes). */
data class ShownImageRef(val path: String?, val url: String?, val caption: String?) {
    companion object {
        fun fromResult(json: String?): ShownImageRef? = runCatching {
            val o = Json.parseToJsonElement(json ?: return null).jsonObject
            if (o["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
            val path = o["path"]?.jsonPrimitive?.contentOrNull
            val url = o["url"]?.jsonPrimitive?.contentOrNull
            if (path == null && url == null) null else ShownImageRef(path, url, o["caption"]?.jsonPrimitive?.contentOrNull)
        }.getOrNull()
    }
}

private sealed interface Load { data object Loading : Load; data class Ok(val bmp: ImageBitmap, val file: File) : Load; data class Missing(val why: String) : Load }

/** Decodes off the main thread, downscaled to [maxSide] (URLs come from / go to the cache dir). */
private suspend fun load(ctx: android.content.Context, ref: ShownImageRef, maxSide: Int): Load = withContext(Dispatchers.IO) {
    val file = when {
        ref.path != null -> File(ref.path).takeIf { it.isFile } ?: return@withContext Load.Missing("Image not found: ${ref.path}")
        else -> ShownImages.fetch(ref.url!!, ctx.cacheDir).getOrElse { return@withContext Load.Missing("Couldn't load the image (${it.message})") }
    }
    val bmp = runCatching { com.verdroid.app.data.storage.AttachmentImages.thumbnail(file, maxSide) }.getOrNull()
        ?: return@withContext Load.Missing("Couldn't decode the image")
    Load.Ok(bmp.asImageBitmap(), file)
}

/** Inline image in the chat (tool card of show_image): tap = fullscreen with pinch-zoom and Share. */
@Composable
fun ShownImageCard(ref: ShownImageRef) {
    val ctx = LocalContext.current
    var state by remember(ref) { mutableStateOf<Load>(Load.Loading) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(ref) { state = load(ctx, ref, 1024) }
    Column(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(enabled = state is Load.Ok) { full = true },
            contentAlignment = Alignment.Center) {
            when (val s = state) {
                Load.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                is Load.Ok -> Image(s.bmp, contentDescription = ref.caption ?: "Image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                is Load.Missing -> Text("🖼️ ${s.why}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
        }
        ref.caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)) }
    }
    val ok = state as? Load.Ok
    if (full && ok != null) ShownImageFullscreen(ref, ok.file) { full = false }
}

@Composable
private fun ShownImageFullscreen(ref: ShownImageRef, file: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) { bmp = (load(ctx, ShownImageRef(file.path, null, null), 2560) as? Load.Ok)?.bmp }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
                    }
                }, contentAlignment = Alignment.Center) {
                    bmp?.let { b ->
                        Image(b, contentDescription = ref.caption ?: "Image", contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                    } ?: CircularProgressIndicator()
                }
                ref.caption?.let { Text(it, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 16.dp)) }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { share(ctx, ref, file) }) { Text("Share") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

private fun share(ctx: android.content.Context, ref: ShownImageRef, file: File) {
    runCatching {
        val kind = file.inputStream().use { ShownImages.sniff(ShownImages.head(it)) } ?: "png"
        // FileProvider can only serve its configured roots; a temp copy in the cache works for any allowed source.
        val out = File(File(ctx.cacheDir, "share"), "image.${if (kind == "jpg") "jpg" else kind}").apply { parentFile?.mkdirs() }
        file.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", out)
        val send = Intent(Intent.ACTION_SEND).setType(ShownImages.mime(kind)).putExtra(Intent.EXTRA_STREAM, uri)
            .apply { ref.caption?.let { putExtra(Intent.EXTRA_TEXT, it) } }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
