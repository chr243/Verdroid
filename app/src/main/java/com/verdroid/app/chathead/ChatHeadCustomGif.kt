package com.verdroid.app.chathead

// TEMP: remove after user picks — user-picked chat head GIFs for testing (Miku theme only).

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object ChatHeadCustomGif {
    enum class Slot(val file: String) { RUNNING("running"), IDLE("idle") }

    private val _version = MutableStateFlow(0)
    /** Bumped on every pick/reset so the chat head re-decodes live. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun dir(ctx: Context) = File(ctx.filesDir, "chathead").apply { mkdirs() }
    fun file(ctx: Context, slot: Slot): File = File(dir(ctx), slot.file)
    fun custom(ctx: Context, slot: Slot): File? = file(ctx, slot).takeIf { it.isFile && it.length() > 0 }

    /** Copies the picked document into app-internal storage (overwrites the previous app-internal copy). */
    fun set(ctx: Context, slot: Slot, uri: Uri): Boolean = runCatching {
        val tmp = File(dir(ctx), slot.file + ".tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        tmp.renameTo(file(ctx, slot)) || run { tmp.copyTo(file(ctx, slot), true); tmp.delete() }
    }.getOrDefault(false).also { _version.value++ }

    fun reset(ctx: Context, slot: Slot) { file(ctx, slot).delete(); _version.value++ }
}
