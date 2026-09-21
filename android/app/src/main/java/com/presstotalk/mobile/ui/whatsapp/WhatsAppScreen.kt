package com.presstotalk.mobile.ui.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.presstotalk.mobile.R
import com.presstotalk.mobile.whatsapp.VoiceNoteFile
import com.presstotalk.mobile.whatsapp.VoiceNoteGroup
import com.presstotalk.mobile.whatsapp.WhatsAppVoiceNoteScanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The WhatsApp voice-notes browser.
 *
 * [onTranscribe] hands a picked voice note to the shared transcription engine
 * (the same one the mic and the file picker use), which saves the result to the
 * transcript history tagged with its origin.
 */
@Composable
fun WhatsAppScreen(
    viewModel: WhatsAppViewModel,
    isTranscribing: Boolean = false,
    transcriptionProgress: Float = 0f,
    transcribingFileName: String? = null,
    onTranscribe: (Uri, String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbars.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    val openTree = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.grantAccess(uri)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isTranscribing) {
            TranscriptionProgress(transcriptionProgress, transcribingFileName)
        }

        Box(modifier = Modifier.weight(1f)) {
        when {
            !state.hasAccess -> GrantAccessCard(
                onGrant = {
                    // Pre-navigate the picker to the WhatsApp voice notes folder
                    // so the user just taps "Use this folder" instead of hunting.
                    val hint = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes",
                    )
                    openTree.launch(hint)
                },
            )

            state.isScanning -> CenteredSpinner("Scanning voice notes…")
            state.groups.isEmpty() -> EmptyState(
                onRescan = viewModel::rescan,
                onRevoke = viewModel::revokeAccess,
            )
            else -> VoiceNoteList(
                groups = state.groups,
                enabled = !isTranscribing,
                onRescan = viewModel::rescan,
                onRevoke = viewModel::revokeAccess,
                onTranscribe = onTranscribe,
            )
        }
        SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun GrantAccessCard(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("WhatsApp voice notes", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Browse and transcribe WhatsApp voice notes. " +
                "Tap the button below and confirm access — the folder is pre-selected. " +
                "Nothing leaves this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGrant) {
            Icon(
                painterResource(R.drawable.ic_whatsapp),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text("Grant access")
        }
    }
}

@Composable
private fun CenteredSpinner(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyState(onRescan: () -> Unit, onRevoke: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No voice notes found", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Make sure the folder you picked holds WhatsApp voice notes, then rescan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRescan) { Text("Rescan") }
            TextButton(onClick = onRevoke) { Text("Change folder") }
        }
    }
}

@Composable
private fun VoiceNoteList(
    groups: List<VoiceNoteGroup>,
    enabled: Boolean,
    onRescan: () -> Unit,
    onRevoke: () -> Unit,
    onTranscribe: (Uri, String) -> Unit,
) {
    var selectedFile by remember { mutableStateOf<VoiceNoteFile?>(null) }

    selectedFile?.let { file ->
        MetadataDialog(file = file, onDismiss = { selectedFile = null })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Voice notes by month",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onRevoke) { Text("Change folder") }
            }
            HorizontalDivider()
        }

        groups.forEach { group ->
            item(key = "hdr-${group.folderName}") {
                Text(
                    group.folderName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(group.files, key = { it.uri.toString() }) { file ->
                VoiceNoteRow(
                    file = file,
                    enabled = enabled,
                    onTranscribe = { onTranscribe(file.uri, file.name) },
                    onInfo = { selectedFile = file },
                )
            }
        }
    }
}

@Composable
private fun VoiceNoteRow(
    file: VoiceNoteFile,
    enabled: Boolean,
    onTranscribe: () -> Unit,
    onInfo: () -> Unit,
) {
    Surface(
        onClick = onTranscribe,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_whatsapp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatTimestamp(file.lastModifiedMs)} · ${formatFileSize(file.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onInfo) {
                Icon(
                    painterResource(R.drawable.ic_info),
                    contentDescription = "Voice note details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** The (i) dialog: full path, size, date, and the folder it came from. */
@Composable
private fun MetadataDialog(file: VoiceNoteFile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MetaRow("Name", file.name)
                MetaRow("Folder", file.folderName)
                MetaRow("Size", formatFileSize(file.sizeBytes))
                MetaRow("Saved", formatTimestamp(file.lastModifiedMs))
                MetaRow("Location", WhatsAppVoiceNoteScanner.readablePath(file.uri))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    context.revealInFilesApp(file.uri)
                },
            ) { Text("Open location") }
        },
    )
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun Context.revealInFilesApp(uri: Uri) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                data = uri
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            },
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun TranscriptionProgress(progress: Float, fileName: String?) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                fileName?.let { "Transcribing $it…" } ?: "Transcribing…",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
