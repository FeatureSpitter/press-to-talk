package com.presstotalk.mobile.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.presstotalk.mobile.R
import com.presstotalk.mobile.data.TranscriptSource
import com.presstotalk.mobile.ui.whatsapp.WhatsAppScreen
import com.presstotalk.mobile.ui.whatsapp.WhatsAppViewModel

/** The two top-level destinations shown in the bottom bar. */
private enum class TopDestination(
    val route: String,
    val label: String,
    val iconRes: Int,
) {
    RECORD("record", "Record", R.drawable.ic_mic),
    WHATSAPP("whatsapp", "WhatsApp", R.drawable.ic_whatsapp),
}

@Composable
fun AppNavigation(recordViewModel: RecordViewModel) {
    val navController = rememberNavController()
    val whatsAppViewModel: WhatsAppViewModel = viewModel()
    val recordState by recordViewModel.state.collectAsStateWithLifecycle()
    val busy = recordState.isTranscribingFile || recordState.isRecording

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination

            NavigationBar {
                TopDestination.entries.forEach { destination ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        enabled = selected || !busy,
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                painterResource(destination.iconRes),
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { insets ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.RECORD.route,
            modifier = Modifier.padding(insets),
        ) {
            composable(TopDestination.RECORD.route) {
                RecordScreen(recordViewModel)
            }
            composable(TopDestination.WHATSAPP.route) {
                WhatsAppScreen(
                    viewModel = whatsAppViewModel,
                    isTranscribing = recordState.isTranscribingFile,
                    transcriptionProgress = recordState.fileProgress,
                    transcribingFileName = recordState.transcribingFileName,
                    onTranscribe = { uri, name ->
                        recordViewModel.transcribeFile(uri, name, source = TranscriptSource.WHATSAPP)
                    },
                )
            }
        }
    }
}
