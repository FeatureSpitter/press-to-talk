package com.presstotalk.mobile.ui.whatsapp

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.presstotalk.mobile.data.AppStore
import com.presstotalk.mobile.whatsapp.VoiceNoteGroup
import com.presstotalk.mobile.whatsapp.WhatsAppVoiceNoteScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WhatsAppUiState(
    val hasAccess: Boolean = false,
    val isScanning: Boolean = false,
    val groups: List<VoiceNoteGroup> = emptyList(),
    val message: String? = null,
)

/**
 * Owns the SAF grant for WhatsApp's media folder and the scan of it.
 *
 * Transcription itself is not duplicated here - it goes through
 * [com.presstotalk.mobile.ui.RecordViewModel.transcribeFile], the same engine
 * and pipeline the mic and the file picker use, so a voice note is transcribed
 * exactly like any other audio file, just tagged with its origin.
 */
class WhatsAppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AppStore(application)
    private val _state = MutableStateFlow(WhatsAppUiState())
    val state: StateFlow<WhatsAppUiState> = _state.asStateFlow()

    private var grantedUri: Uri? = null

    init {
        viewModelScope.launch {
            store.whatsAppFolderUri.collect { stored ->
                val uri = stored?.let(Uri::parse)
                val stillGranted = uri != null && hasPersistedPermission(uri)
                grantedUri = if (stillGranted) uri else null
                _state.value = _state.value.copy(hasAccess = stillGranted)
                if (stillGranted) rescan()
            }
        }
    }

    /** The permission can be revoked outside the app (Settings, a factory reset of grants). */
    private fun hasPersistedPermission(uri: Uri): Boolean =
        getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }

    fun grantAccess(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val took = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.e(TAG, "Could not persist folder permission", it) }.isSuccess

        if (!took) {
            _state.value = _state.value.copy(message = "Could not keep access to that folder")
            return
        }
        viewModelScope.launch { store.setWhatsAppFolderUri(uri.toString()) }
    }

    fun revokeAccess() {
        val uri = grantedUri ?: return
        runCatching {
            getApplication<Application>().contentResolver
                .releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch { store.setWhatsAppFolderUri(null) }
        _state.value = WhatsAppUiState()
    }

    fun rescan() {
        val uri = grantedUri ?: return
        _state.value = _state.value.copy(isScanning = true, message = null)
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) {
                runCatching { WhatsAppVoiceNoteScanner.scan(getApplication(), uri) }
                    .onFailure { Log.e(TAG, "Scan failed", it) }
                    .getOrDefault(emptyList())
            }
            _state.value = _state.value.copy(
                isScanning = false,
                groups = groups,
                message = if (groups.isEmpty()) "No voice notes found in this folder" else null,
            )
        }
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private companion object {
        const val TAG = "WhatsAppViewModel"
    }
}
