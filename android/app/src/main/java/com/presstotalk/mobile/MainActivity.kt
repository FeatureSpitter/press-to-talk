package com.presstotalk.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.presstotalk.mobile.ui.RecordScreen
import com.presstotalk.mobile.ui.RecordViewModel
import com.presstotalk.mobile.ui.theme.PressToTalkTheme

class MainActivity : ComponentActivity() {

    private val viewModel: RecordViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PressToTalkTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                // A screen timeout mid-recording would otherwise end the session.
                // This is why the app needs no foreground service.
                val view = LocalView.current
                DisposableEffect(state.isRecording) {
                    view.keepScreenOn = state.isRecording
                    onDispose { view.keepScreenOn = false }
                }

                RecordScreen(viewModel)
            }
        }
    }

    /**
     * Recording only happens while the app is on screen. Stopping here is a
     * graceful stop, not a cancellation, so the VAD is still flushed and
     * whatever was already said gets saved rather than thrown away.
     */
    override fun onStop() {
        super.onStop()
        viewModel.onMovedToBackground()
    }
}
