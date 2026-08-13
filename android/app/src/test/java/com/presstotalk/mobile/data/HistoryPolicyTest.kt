package com.presstotalk.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryPolicyTest {

    private fun transcript(id: String) =
        Transcript(id = id, createdAt = 0L, text = "text $id", durationMs = 1000L)

    private fun ids(list: List<Transcript>) = list.map { it.id }

    @Test
    fun `adding puts the newest first`() {
        val result = HistoryPolicy.add(
            existing = listOf(transcript("b"), transcript("a")),
            transcript = transcript("c"),
            cap = 25,
        )
        assertEquals(listOf("c", "b", "a"), ids(result))
    }

    @Test
    fun `adding past the cap drops the oldest`() {
        val existing = listOf(transcript("c"), transcript("b"), transcript("a"))
        val result = HistoryPolicy.add(existing, transcript("d"), cap = 3)
        assertEquals(listOf("d", "c", "b"), ids(result))
    }

    @Test
    fun `lowering the cap trims existing entries`() {
        val existing = (1..10).map { transcript("t$it") }
        val result = HistoryPolicy.applyCap(existing, cap = 4)
        assertEquals(listOf("t1", "t2", "t3", "t4"), ids(result))
    }

    @Test
    fun `raising the cap does not resurrect trimmed entries`() {
        val existing = (1..10).map { transcript("t$it") }
        val trimmed = HistoryPolicy.applyCap(existing, cap = 3)
        val raised = HistoryPolicy.applyCap(trimmed, cap = 50)
        assertEquals(3, raised.size)
    }

    @Test
    fun `cap is clamped to the supported range`() {
        assertEquals(HistoryPolicy.MIN_CAP, HistoryPolicy.clampCap(0))
        assertEquals(HistoryPolicy.MIN_CAP, HistoryPolicy.clampCap(-5))
        assertEquals(HistoryPolicy.MAX_CAP, HistoryPolicy.clampCap(1000))
        assertEquals(25, HistoryPolicy.clampCap(25))
    }

    @Test
    fun `an out of range cap still bounds the list`() {
        val existing = (1..10).map { transcript("t$it") }
        // A stored 0 must not wipe the list, and a stored 9999 must not be trusted.
        assertEquals(1, HistoryPolicy.applyCap(existing, cap = 0).size)
        assertEquals(10, HistoryPolicy.applyCap(existing, cap = 9999).size)
    }

    @Test
    fun `empty history stays empty`() {
        assertEquals(emptyList<Transcript>(), HistoryPolicy.applyCap(emptyList(), cap = 25))
    }
}
