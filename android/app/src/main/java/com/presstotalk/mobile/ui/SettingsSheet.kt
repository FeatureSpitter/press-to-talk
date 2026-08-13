package com.presstotalk.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.presstotalk.mobile.asr.LanguageMode
import com.presstotalk.mobile.data.AppSettings
import com.presstotalk.mobile.data.HistoryPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            SettingLabel("Language")
            SettingHint(
                "Auto detects per utterance. Pin one if detection wanders on short " +
                    "phrases. Changing this reloads the model.",
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = LanguageMode.entries
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.languageMode == mode,
                        onClick = { onUpdate { it.copy(languageMode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) {
                        Text(
                            when (mode) {
                                LanguageMode.AUTO -> "Auto"
                                LanguageMode.PORTUGUESE -> "Português"
                                LanguageMode.ENGLISH -> "English"
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            SettingLabel("Transcripts kept")
            SettingHint("Oldest are dropped past this. Lowering it trims straight away.")
            SliderRow(
                value = settings.historyCap.toFloat(),
                range = HistoryPolicy.MIN_CAP.toFloat()..HistoryPolicy.MAX_CAP.toFloat(),
                valueLabel = settings.historyCap.toString(),
                onChange = { onUpdate { current -> current.copy(historyCap = it.toInt()) } },
            )

            Spacer(Modifier.height(24.dp))

            SettingLabel("Recording limit")
            SettingHint("Recording stops automatically at this length so it cannot run away.")
            SliderRow(
                value = settings.maxRecordingMinutes.toFloat(),
                range = AppSettings.MIN_MAX_MINUTES.toFloat()..AppSettings.MAX_MAX_MINUTES.toFloat(),
                valueLabel = "${settings.maxRecordingMinutes} min",
                onChange = { onUpdate { current -> current.copy(maxRecordingMinutes = it.toInt()) } },
            )

            Spacer(Modifier.height(24.dp))

            SettingLabel("Model")
            SettingHint("Larger is more accurate and slower. Reloads on change.")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MODELS.forEachIndexed { index, model ->
                    SegmentedButton(
                        selected = settings.modelName == model,
                        onClick = { onUpdate { it.copy(modelName = model) } },
                        shape = SegmentedButtonDefaults.itemShape(index, MODELS.size),
                    ) { Text(model) }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SettingHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SliderRow(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.padding(horizontal = 8.dp))
        Text(valueLabel, style = MaterialTheme.typography.labelLarge)
    }
}

private val MODELS = listOf("tiny", "base", "small")
