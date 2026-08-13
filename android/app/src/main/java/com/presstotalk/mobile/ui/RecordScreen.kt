package com.presstotalk.mobile.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.presstotalk.mobile.R
import com.presstotalk.mobile.data.Transcript
import com.presstotalk.mobile.ui.theme.recordingAccent
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(viewModel: RecordViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbars = remember { SnackbarHostState() }
    val copy = rememberCopyAction()

    var permissionGranted by remember { mutableStateOf(context.hasRecordPermission()) }
    var permissionDenied by remember { mutableStateOf(false) }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        permissionDenied = !granted
        if (granted) viewModel.toggleRecording()
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            snackbars.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            availableModels = state.availableModels,
            onUpdate = viewModel::updateSettings,
            onDismiss = { showSettings = false },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Press to Talk") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    permissionDenied && !permissionGranted -> PermissionDeniedCard(context)
                    state.modelState is ModelState.Loading -> LoadingModel()
                    state.modelState is ModelState.Missing ->
                        StatusCard(
                            title = "No model installed",
                            body = (state.modelState as ModelState.Missing).message,
                        )
                    state.modelState is ModelState.Failed ->
                        StatusCard(
                            title = "Could not load the model",
                            body = (state.modelState as ModelState.Failed).message,
                        )
                    state.isRecording || state.isFinishing -> LiveTranscript(state)
                    else -> IdleContent(
                        history = state.history,
                        onCopy = copy,
                        onDelete = viewModel::deleteTranscript,
                        onClearAll = viewModel::clearHistory,
                    )
                }
            }

            RecordButton(
                isRecording = state.isRecording,
                isFinishing = state.isFinishing,
                level = state.level,
                enabled = state.canRecord || state.isRecording,
                onClick = {
                    if (permissionGranted) {
                        viewModel.toggleRecording()
                    } else {
                        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp),
            )
        }
    }
}

// --- content states ---------------------------------------------------------

@Composable
private fun LoadingModel() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Loading the speech model", style = MaterialTheme.typography.bodyMedium)
        Text(
            "First launch takes a few seconds",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionDeniedCard(context: Context) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Microphone access needed", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Recording is the whole app, so it cannot do anything without the microphone. " +
                "Nothing is ever sent anywhere - transcription happens on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            },
        ) { Text("Open settings") }
    }
}

@Composable
private fun LiveTranscript(state: RecordUiState) {
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }

    // Follow the newest text, but stop the moment the user scrolls up to read
    // something earlier - and resume once they return to the bottom.
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress) follow = scroll.value >= scroll.maxValue - 48
    }
    LaunchedEffect(state.liveText) {
        if (follow) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDuration(state.elapsedSeconds),
                style = MaterialTheme.typography.titleMedium,
                color = if (state.isNearCap) recordingAccent else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isNearCap) {
                    Text(
                        "${formatDuration(state.remainingSeconds)} left",
                        style = MaterialTheme.typography.labelMedium,
                        color = recordingAccent,
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Surface(color = recordingAccent, shape = CircleShape) {
                    Box(Modifier.size(10.dp))
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    if (state.isFinishing) "FINISHING" else "REC",
                    style = MaterialTheme.typography.labelMedium,
                    color = recordingAccent,
                )
            }
        }

        Box(modifier = Modifier.weight(1f).verticalScroll(scroll)) {
            if (state.liveText.isEmpty()) {
                Text(
                    "Listening…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(state.liveText, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun IdleContent(
    history: List<Transcript>,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    if (history.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nothing recorded yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap the button and start talking. Everything stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val latest = history.first()
    val earlier = history.drop(1)
    var confirmClearAll by remember { mutableStateOf(false) }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Delete all transcripts?") },
            text = { Text("All ${history.size} transcripts will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearAll = false
                        onClearAll()
                    },
                ) { Text("Delete all") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { LatestTranscriptCard(latest, onCopy, onDelete) }

        if (earlier.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Earlier",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { confirmClearAll = true }) { Text("Clear all") }
                }
            }
            items(earlier, key = { it.id }) { transcript ->
                HistoryRow(transcript, onCopy, onDelete)
            }
        }
    }
}

/**
 * The newest transcript, with a full-width Copy button.
 *
 * Copying the thing just recorded is the reason the app exists, so it gets a
 * primary button rather than the small icon the older entries carry.
 */
@Composable
private fun LatestTranscriptCard(
    transcript: Transcript,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(transcript.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onCopy(transcript.text) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Copy")
                }
                IconButton(onClick = { onDelete(transcript.id) }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = "Delete transcript",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                transcript.subtitle(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryRow(
    transcript: Transcript,
    onCopy: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    transcript.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    transcript.subtitle(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onCopy(transcript.text) }) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    contentDescription = "Copy transcript",
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { onDelete(transcript.id) }) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = "Delete transcript",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// --- helpers ----------------------------------------------------------------

/**
 * Copies to the clipboard.
 *
 * No confirmation toast on Android 13+: the system shows its own preview, and a
 * second message on top of it reads as a bug.
 */
@Composable
private fun rememberCopyAction(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    return remember(clipboard, context) {
        fun copy(text: String) {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("transcript", text)))
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        }
        ::copy
    }
}

private fun Context.hasRecordPermission(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun formatDuration(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm")

private fun Transcript.subtitle(): String {
    val zoned = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault())
    val stamp = if (zoned.toLocalDate() == LocalDate.now()) {
        timeFormat.format(zoned)
    } else {
        dateFormat.format(zoned)
    }
    return buildString {
        append(stamp)
        append(" · ")
        append(formatDuration(durationMs / 1000f))
        language?.let { append(" · ").append(it) }
        if (interrupted) append(" · interrupted")
    }
}
