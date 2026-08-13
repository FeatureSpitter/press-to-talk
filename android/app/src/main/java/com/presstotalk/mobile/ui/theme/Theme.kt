package com.presstotalk.mobile.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Fallback identity for Android 10 and 11, which have no dynamic colour.
// The same indigo as the launcher icon, so the app still looks deliberate on a
// friend's older phone rather than defaulting to stock purple.
private val Indigo = Color(0xFF1F3A5F)
private val IndigoLight = Color(0xFF9EC5F0)
private val Ember = Color(0xFFD4562F) // recording state; reads as "live" in both themes

private val FallbackLight = lightColorScheme(
    primary = Indigo,
    secondary = Indigo,
    tertiary = Ember,
)

private val FallbackDark = darkColorScheme(
    primary = IndigoLight,
    secondary = IndigoLight,
    tertiary = Ember,
)

/** Colour that marks an active recording, in either theme. */
val recordingAccent: Color get() = Ember

@Composable
fun PressToTalkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Material You: the palette follows the user's wallpaper, so the app
        // looks native on a Pixel instead of like a sideloaded APK.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
