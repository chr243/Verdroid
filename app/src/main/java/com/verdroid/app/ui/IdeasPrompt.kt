package com.verdroid.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verdroid.app.data.update.AppUpdater
import com.verdroid.app.data.update.UpdateState
import kotlinx.coroutines.delay

/**
 * One-time in-app announcements ("campaigns"). Each has an id; the last id the user saw is stored, so a campaign shows once
 * per install (fresh install or update alike) and a future campaign only needs a new id. Pure rule unit-tested.
 */
object Campaigns {
    const val IDEAS = "ideas-discussion-2026-10"
    const val IDEAS_URL = "https://github.com/chr243/Verdroid/discussions/2"
    private const val PREFS = "campaigns"
    private const val KEY = "last_seen_campaign"

    /** Show [id] only on the main screen, when no other startup dialog is up, and only if not seen yet. */
    fun shouldShow(id: String, lastSeen: String?, onMainScreen: Boolean, otherDialogUp: Boolean): Boolean =
        onMainScreen && !otherDialogUp && lastSeen != id

    fun lastSeen(ctx: Context): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    fun markSeen(ctx: Context, id: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, id).apply() }
}

/** "Got an idea?" pop-up: MainActivity only (never assist/chat head/scheduled runs), deferred until the chat list is shown. */
@Composable
fun IdeasPrompt(onMainScreen: Boolean, updater: AppUpdater) {
    val ctx = LocalContext.current
    var seen by remember { mutableStateOf(Campaigns.lastSeen(ctx)) }
    val upd by updater.state.collectAsStateWithLifecycle()
    val updEnabled by updater.enabled.collectAsStateWithLifecycle()
    val updateUp = updEnabled && upd is UpdateState.Available && !updater.startupPromptDismissed
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(onMainScreen) { settled = false; if (onMainScreen) { delay(1_500); settled = true } }
    if (!settled || !Campaigns.shouldShow(Campaigns.IDEAS, seen, onMainScreen, updateUp)) return
    fun done() { Campaigns.markSeen(ctx, Campaigns.IDEAS); seen = Campaigns.IDEAS }
    AlertDialog(
        onDismissRequest = { done() },
        title = { Text("Got an idea for Verdroid?") },
        text = { Text("Tell us what Verdroid should do next — share or upvote feature ideas on GitHub Discussions.") },
        confirmButton = {
            TextButton(onClick = {
                done()
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Campaigns.IDEAS_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text("Share an idea") }
        },
        dismissButton = { TextButton(onClick = { done() }) { Text("Maybe later") } },
    )
}
