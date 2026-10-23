package com.verdroid.app.chathead

// User-picked chat head GIFs, stored per theme: filesDir/chathead/<PALETTE>/{running,idle}.

import android.content.Context
import android.net.Uri
import com.verdroid.app.ui.theme.Palette
import com.verdroid.app.ui.theme.ThemeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object ChatHeadCustomGif {
    enum class Slot(val file: String) { RUNNING("running"), IDLE("idle") }

    private val _version = MutableStateFlow(0)
    /** Bumped on every pick/reset so the chat head re-decodes live. */
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile private var migrated = false

    private fun root(ctx: Context) = File(ctx.filesDir, "chathead").apply { mkdirs() }

    /** Moves pre-test4 un-namespaced picks (chathead/running, chathead/idle) to the theme active now. */
    private fun migrate(ctx: Context) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            runCatching {
                val root = root(ctx)
                val target = File(root, ThemeStore.get(ctx).palette.value.name).apply { mkdirs() }
                Slot.entries.forEach { s ->
                    val old = File(root, s.file)
                    if (old.isFile) {
                        val dst = File(target, s.file)
                        if (!dst.exists()) { if (!old.renameTo(dst)) { old.copyTo(dst, true); old.delete() } } else old.delete()
                    }
                    File(root, s.file + ".tmp").delete()
                }
            }
            migrated = true
        }
    }

    private fun dir(ctx: Context, theme: Palette): File { migrate(ctx); return File(root(ctx), theme.name).apply { mkdirs() } }
    fun file(ctx: Context, theme: Palette, slot: Slot): File = File(dir(ctx, theme), slot.file)
    fun custom(ctx: Context, theme: Palette, slot: Slot): File? = file(ctx, theme, slot).takeIf { it.isFile && it.length() > 0 }

    /** Copies the picked document into app-internal storage for [theme] (overwrites that theme's previous copy). */
    fun set(ctx: Context, theme: Palette, slot: Slot, uri: Uri): Boolean = runCatching {
        val tmp = File(dir(ctx, theme), slot.file + ".tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
        val dst = file(ctx, theme, slot)
        tmp.renameTo(dst) || run { tmp.copyTo(dst, true); tmp.delete() }
    }.getOrDefault(false).also { _version.value++ }

    /** Clears only [theme]'s pick for [slot]. */
    fun reset(ctx: Context, theme: Palette, slot: Slot) { file(ctx, theme, slot).delete(); _version.value++ }
}
