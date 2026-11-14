package com.mardous.booming.ui.screen.lyrics

import android.content.ClipData
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.mardous.booming.R
import com.mardous.booming.data.model.lyrics.LyricsLineEditResult
import com.mardous.booming.extensions.showToast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Edits one line of lyrics that come from an external file. Saving may first ask the user to
 * grant access to the file's folder through the system folder picker.
 */
@Composable
fun LyricsLineEditDialog(
    initialText: String,
    events: Flow<LyricsLineEditEvent>,
    onSave: (String) -> Unit,
    onFolderAccessResult: (Uri?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    // Single-line editor: line breaks from multi-row cues become spaces
    val initialSingleLine = remember(initialText) {
        initialText.lines().joinToString(" ") { it.trim() }.trim()
    }
    var text by rememberSaveable { mutableStateOf(initialSingleLine) }
    var isSaving by rememberSaveable { mutableStateOf(false) }

    fun save() {
        if (isSaving) return
        // Unchanged: don't rewrite (would merge the rows of a multi-row cue)
        if (text == initialSingleLine) {
            onDismiss()
            return
        }
        isSaving = true
        onSave(text)
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) isSaving = false
        onFolderAccessResult(uri)
    }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is LyricsLineEditEvent.Result -> when (event.result) {
                    LyricsLineEditResult.Success -> {
                        context.showToast(R.string.lyrics_line_saved)
                        onDismiss()
                    }
                    LyricsLineEditResult.NoChanges -> onDismiss()
                    LyricsLineEditResult.NotFound -> {
                        isSaving = false
                        context.showToast(R.string.lyrics_line_not_found)
                    }
                    LyricsLineEditResult.Failed -> {
                        isSaving = false
                        context.showToast(R.string.failed_to_update_lyrics)
                    }
                }

                is LyricsLineEditEvent.RequestFolderAccess -> {
                    context.showToast(
                        context.getString(R.string.lyrics_grant_folder_access, event.folderPath)
                    )
                    folderPicker.launch(event.initialUri)
                }

                is LyricsLineEditEvent.WrongFolder -> {
                    isSaving = false
                    context.showToast(
                        context.getString(R.string.lyrics_wrong_folder, event.folderPath)
                    )
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lyrics_edit_line_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\r", "").replace('\n', ' ') },
                enabled = !isSaving,
                singleLine = true,
                // Keyboard "done" key saves
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("lyrics", text)))
                        }
                        // Android 13+ shows its own confirmation when the clipboard changes
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                            context.showToast(R.string.lyrics_copied_to_clipboard)
                        }
                    }
                ) {
                    Text(stringResource(R.string.lyrics_action_copy))
                }
                TextButton(
                    enabled = !isSaving,
                    onClick = { save() }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close_action))
            }
        }
    )
}
