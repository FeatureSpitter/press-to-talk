package com.presstotalk.mobile.data

import kotlinx.serialization.Serializable

@Serializable
data class Transcript(
    val id: String,
    val createdAt: Long,
    val text: String,
    val durationMs: Long,
    /** Whisper's detected language for the first utterance, when it reported one. */
    val language: String? = null,
    /** True when recording was cut short by the app going to the background. */
    val interrupted: Boolean = false,
)

/**
 * The history's retention rule, kept separate from storage so it can be tested
 * without a DataStore or an Android runtime.
 */
object HistoryPolicy {

    const val MIN_CAP = 1
    const val MAX_CAP = 100
    const val DEFAULT_CAP = 25

    fun clampCap(cap: Int): Int = cap.coerceIn(MIN_CAP, MAX_CAP)

    /**
     * Newest first, capped. Applied on insert *and* whenever the cap changes,
     * so lowering it trims immediately rather than leaving orphans that
     * reappear if the user raises it again.
     */
    fun applyCap(transcripts: List<Transcript>, cap: Int): List<Transcript> =
        transcripts.take(clampCap(cap))

    fun add(existing: List<Transcript>, transcript: Transcript, cap: Int): List<Transcript> =
        applyCap(listOf(transcript) + existing, cap)

    fun remove(existing: List<Transcript>, id: String): List<Transcript> =
        existing.filterNot { it.id == id }
}
