package com.presstotalk.mobile.ui

/**
 * What each model actually costs and gives you, for the settings picker.
 *
 * The speed figures are measured on a Pixel 8 Pro (4 threads, CPU, int8 - see
 * android/README.md), not vendor claims. A name alone tells nobody which one to
 * choose, so the trade-off is spelled out where the choice is made.
 */
data class ModelInfo(
    val name: String,
    /** Concrete, in the unit that matters: how long a real recording takes. */
    val speed: String,
    val size: String,
    val accuracy: String,
    val detail: String,
    val recommended: Boolean = false,
)

object ModelCatalog {

    val all = listOf(
        ModelInfo(
            name = "tiny",
            speed = "3 min of speech in ~10 s",
            size = "104 MB",
            accuracy = "Roughest",
            detail = "Fastest by a wide margin and the least accurate. Drops words, " +
                "mangles names and punctuates badly. Weakest of the three on " +
                "Portuguese. Fine for a note you are going to re-read anyway.",
        ),
        ModelInfo(
            name = "base",
            speed = "3 min of speech in ~17 s",
            size = "161 MB",
            accuracy = "Decent",
            detail = "Three and a half times faster than small. Clearly better than " +
                "tiny, but still behind small on Portuguese - expect a few wrong " +
                "words per paragraph and softer punctuation.",
        ),
        ModelInfo(
            name = "small",
            speed = "3 min of speech in ~61 s",
            size = "375 MB",
            accuracy = "Best",
            detail = "The most accurate and much the best at Portuguese. Slowest on " +
                "paper, but it transcribes while you speak, so what you actually " +
                "wait for at the end is the last sentence, not the whole recording.",
            recommended = true,
        ),
    )

    fun infoFor(name: String): ModelInfo? = all.firstOrNull { it.name == name }

    /** Catalogue order (fastest first), filtered to what is installed. */
    fun installed(available: List<String>): List<ModelInfo> =
        all.filter { it.name in available }
}
