package com.verdroid.app.ui.chats

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.res.stringResource
import com.verdroid.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.verdroid.app.data.feedback.FeedbackLinks
import com.verdroid.app.data.feedback.StarPromptLogic
import com.verdroid.app.data.feedback.StarPromptStore

/** One-time, optional "star on GitHub" prompt after the 3rd chat ever created; never while a task runs. */
@Composable
fun StarPrompt(anyTaskActive: Boolean) {
    val context = LocalContext.current
    val store = remember { StarPromptStore.get(context) }
    val pending by store.pending.collectAsState()
    if (!StarPromptLogic.shouldShow(pending, store.done, anyTaskActive)) return
    AlertDialog(
        onDismissRequest = { store.finish() },
        title = { Text(stringResource(R.string.enjoying_verdroid)) },
        text = { Text(stringResource(R.string.star_body)) },
        confirmButton = {
            TextButton(onClick = {
                store.finish()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FeedbackLinks.REPO))) }
            }) { Text(stringResource(R.string.star_on_github)) }
        },
        dismissButton = { TextButton(onClick = { store.finish() }) { Text(stringResource(R.string.maybe_not)) } },
    )
}
