package com.presstotalk.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.presstotalk.mobile.R
import com.presstotalk.mobile.ui.theme.recordingAccent
import kotlin.math.sqrt

/**
 * The record button, ringed by a halo that tracks the microphone level.
 *
 * The ring exists because a static button gives no evidence the microphone is
 * alive. Over a multi-minute recording that is genuinely unnerving, and a dead
 * or muted mic is otherwise invisible until the transcript comes back empty.
 */
@Composable
fun RecordButton(
    isRecording: Boolean,
    isFinishing: Boolean,
    level: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    // RMS of speech sits around 0.01-0.3, and loudness is perceived closer to a
    // square-root curve than a linear one, so raw RMS would barely move the ring.
    //
    // GAIN was 2.2 and read as "is this thing even on?" in use. Normal speech
    // now drives the ring most of the way out, and RESTING_SCALE keeps a visible
    // halo during pauses so silence still looks like listening, not like death.
    val normalized = if (isRecording) {
        val voice = (sqrt(level.coerceAtLeast(0f)) * GAIN).coerceIn(0f, 1f)
        RESTING_SCALE + (1f - RESTING_SCALE) * voice
    } else {
        0f
    }

    val ringScale by animateFloatAsState(
        targetValue = normalized,
        // Short enough to feel immediate, long enough to smooth per-frame jitter.
        animationSpec = tween(durationMillis = 70),
        label = "ringScale",
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            isRecording -> recordingAccent
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(200),
        label = "buttonColor",
    )

    Box(
        modifier = modifier.size(TOUCH_AREA),
        contentAlignment = Alignment.Center,
    ) {
        if (isRecording) {
            AmplitudeRing(scale = ringScale, color = recordingAccent)
        }

        Surface(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            // While the tail is still being transcribed there is nothing useful a
            // tap could do, so the button stops pretending to be pressable.
            enabled = enabled && !isFinishing,
            shape = CircleShape,
            color = buttonColor,
            modifier = Modifier
                .size(BUTTON_SIZE)
                .semantics {
                    contentDescription = when {
                        isFinishing -> "Finishing transcription"
                        isRecording -> "Stop recording"
                        else -> "Start recording"
                    }
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (isFinishing) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(ICON_SIZE),
                    )
                } else {
                    Icon(
                        painter = painterResource(
                            if (isRecording) R.drawable.ic_stop else R.drawable.ic_mic,
                        ),
                        contentDescription = null,
                        tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(ICON_SIZE)
                            .alpha(if (enabled) 1f else 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun AmplitudeRing(scale: Float, color: Color) {
    Canvas(modifier = Modifier.size(TOUCH_AREA)) {
        val base = BUTTON_SIZE.toPx() / 2f
        val headroom = (size.minDimension / 2f) - base

        // Three nested rings at different fractions of the level: the inner ones
        // track the voice closely while the outer trails, which reads as a pulse
        // rather than one circle resizing. Drawn outermost first so the alphas
        // stack up towards the middle.
        drawCircle(color = color, radius = base + headroom * scale, alpha = 0.13f)
        drawCircle(color = color, radius = base + headroom * scale * 0.70f, alpha = 0.20f)
        drawCircle(color = color, radius = base + headroom * scale * 0.40f, alpha = 0.30f)
    }
}

/** Perceptual gain applied to sqrt(RMS). Tuned by ear on a Pixel 8 Pro. */
private const val GAIN = 3.4f

/** Ring size during a pause, so "listening" never looks like "stopped". */
private const val RESTING_SCALE = 0.18f

// Enlarged from 168dp: the halo needs room to be unmistakable at arm's length.
private val TOUCH_AREA = 248.dp
private val BUTTON_SIZE = 88.dp
private val ICON_SIZE = 36.dp
