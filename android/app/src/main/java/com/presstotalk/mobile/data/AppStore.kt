package com.presstotalk.mobile.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "press_to_talk")

/**
 * Transcript history and settings, stored as two JSON strings in one DataStore.
 *
 * A hundred short transcripts is tens of kilobytes, so rewriting the whole blob
 * on every append costs nothing and avoids Room - which would mean a KSP
 * annotation-processing round on every build, plus schemas and migrations, for
 * a list that fits comfortably in memory.
 */
class AppStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true // tolerate fields added by a newer build
        encodeDefaults = true
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        decode(prefs[SETTINGS_KEY], AppSettings()) { json.decodeFromString<AppSettings>(it) }
            .sanitized()
    }

    val history: Flow<List<Transcript>> = context.dataStore.data.map { prefs ->
        val stored = decode(prefs[HISTORY_KEY], emptyList()) {
            json.decodeFromString<List<Transcript>>(it)
        }
        val cap = decode(prefs[SETTINGS_KEY], AppSettings()) {
            json.decodeFromString<AppSettings>(it)
        }.sanitized().historyCap
        // Cap on read too: a settings change and a history write are separate
        // edits, so this is what makes lowering the cap take effect at once.
        HistoryPolicy.applyCap(stored, cap)
    }

    suspend fun addTranscript(transcript: Transcript) {
        context.dataStore.edit { prefs ->
            val cap = decode(prefs[SETTINGS_KEY], AppSettings()) {
                json.decodeFromString<AppSettings>(it)
            }.sanitized().historyCap
            val existing = decode(prefs[HISTORY_KEY], emptyList<Transcript>()) {
                json.decodeFromString<List<Transcript>>(it)
            }
            prefs[HISTORY_KEY] = json.encodeToString(HistoryPolicy.add(existing, transcript, cap))
        }
    }

    suspend fun deleteTranscript(id: String) {
        context.dataStore.edit { prefs ->
            val existing = decode(prefs[HISTORY_KEY], emptyList<Transcript>()) {
                json.decodeFromString<List<Transcript>>(it)
            }
            prefs[HISTORY_KEY] = json.encodeToString(HistoryPolicy.remove(existing, id))
        }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { prefs -> prefs[HISTORY_KEY] = json.encodeToString(emptyList<Transcript>()) }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[SETTINGS_KEY], AppSettings()) {
                json.decodeFromString<AppSettings>(it)
            }
            val updated = transform(current).sanitized()
            prefs[SETTINGS_KEY] = json.encodeToString(updated)

            // Trim straight away so the stored blob matches what is displayed.
            val existing = decode(prefs[HISTORY_KEY], emptyList<Transcript>()) {
                json.decodeFromString<List<Transcript>>(it)
            }
            val trimmed = HistoryPolicy.applyCap(existing, updated.historyCap)
            if (trimmed.size != existing.size) {
                prefs[HISTORY_KEY] = json.encodeToString(trimmed)
            }
        }
    }

    /** Corrupt or unreadable stored data falls back to the default rather than crashing. */
    private inline fun <T> decode(raw: String?, fallback: T, parse: (String) -> T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { parse(raw) }
            .onFailure { Log.w(TAG, "Discarding unreadable stored value", it) }
            .getOrDefault(fallback)
    }

    private companion object {
        const val TAG = "AppStore"
        val SETTINGS_KEY: Preferences.Key<String> = stringPreferencesKey("settings")
        val HISTORY_KEY: Preferences.Key<String> = stringPreferencesKey("history")
    }
}
