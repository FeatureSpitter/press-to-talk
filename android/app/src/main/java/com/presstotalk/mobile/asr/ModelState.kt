package com.presstotalk.mobile.asr

/** The speech engine's readiness, as far as the UI needs to know. */
sealed interface ModelState {
    data object Loading : ModelState
    data object Ready : ModelState
    /** No model on disk - actionable, with instructions. */
    data class Missing(val message: String) : ModelState
    data class Failed(val message: String) : ModelState
}
