package com.mohammadjafari.sortview

import com.mohammadjafari.sortview.data.UndoCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM regression tests for the undo batch state machine (v1.0.3).
 *
 * The coordinator itself is pure bookkeeping; the races live in how the
 * ViewModel feeds it (the idle tick of TransferHub.progress can beat the
 * last Finished event of engine.events, and a backlog drain can double-
 * deliver a pair the live event already booked). These tests pin the
 * invariants the v1.0.2/v1.0.3 fixes depend on:
 *
 *  1. a batch seals only when done >= expected — every expected file
 *     reported back exactly once (finished, error, or dropped);
 *  2. a duplicate (src, dst) pair is booked exactly once;
 *  3. pairs drained while the session's batch is ALREADY OPEN must be
 *     recorded WITHOUT a second expect() (the ViewModel's reconcile does
 *     that since v1.0.3) — re-expecting them locked Undo forever;
 *  4. drops reduce the expected count (desktop L3753);
 *  5. at most 50 rounds are kept (compatibility contract #5).
 */
class UndoCoordinatorTest {

    @Test
    fun `batch seals when every expected file reports back`() {
        val undo = UndoCoordinator()
        undo.openBatchIfClosed()
        undo.expect(2)
        assertTrue(undo.record("/a.jpg", "/cat/a.jpg"))
        assertTrue(undo.record("/b.jpg", "/cat/b.jpg"))
        assertTrue(undo.sealIfQueueIdle())
        assertTrue(undo.hasRounds)
        assertFalse(undo.hasOpenBatch)
    }

    @Test
    fun `duplicate pair is booked exactly once`() {
        val undo = UndoCoordinator()
        undo.openBatchIfClosed()
        undo.expect(1)
        assertTrue(undo.record("/a.jpg", "/cat/a.jpg"))
        // The backlog drain and the live event can both deliver this pair.
        assertFalse(undo.record("/a.jpg", "/cat/a.jpg"))
        assertTrue(undo.sealIfQueueIdle())
    }

    @Test
    fun `drained pair in an open batch must NOT re-expect`() {
        // v1.0.3 regression: doEnqueue already counted the file (expect(1)).
        // The idle tick beats the live event, the reconcile drains the pair
        // and — before the fix — called expect() again: expected=2, done=1,
        // the seal became impossible and Undo locked forever.
        val undo = UndoCoordinator()
        undo.openBatchIfClosed()
        undo.expect(1)
        // The pair arrives via the drain while the batch is open: record()
        // books it, no second expect() — the seal must succeed immediately.
        assertTrue(undo.record("/a.jpg", "/cat/a.jpg"))
        assertTrue(undo.sealIfQueueIdle())
        assertFalse(undo.hasOpenBatch)
        // The late live event then sees the duplicate and is skipped.
        assertFalse(undo.record("/a.jpg", "/cat/a.jpg"))
    }

    @Test
    fun `orphans from a previous session book a fresh closed batch`() {
        val undo = UndoCoordinator()
        // Fresh attach (no batch open): the reconcile opens one, expects
        // the drained pairs and records them — the round stays undoable.
        assertFalse(undo.hasOpenBatch)
        undo.openBatchIfClosed()
        undo.expect(2)
        assertTrue(undo.record("/a.jpg", "/cat/a.jpg"))
        assertTrue(undo.record("/b.jpg", "/cat/b (1).jpg"))
        assertTrue(undo.sealIfQueueIdle())
        val round = undo.popRound()
        assertEquals(2, round?.size)
        assertEquals("/a.jpg" to "/cat/a.jpg", round?.get(0))
        assertNull(undo.popRound())
    }

    @Test
    fun `error report counts as reported-back`() {
        val undo = UndoCoordinator()
        undo.openBatchIfClosed()
        undo.expect(2)
        assertTrue(undo.record("/a.jpg", "/cat/a.jpg"))
        // The second file failed: nothing to undo, but it HAS reported back.
        undo.reportBackWithoutPair()
        assertTrue(undo.sealIfQueueIdle())
        // Only the finished pair is in the round.
        assertEquals(1, undo.popRound()?.size)
    }

    @Test
    fun `dropped files reduce the expected count`() {
        val undo = UndoCoordinator()
        undo.openBatchIfClosed()
        undo.expect(3)
        undo.adjustDropped(3)
        // All three were dropped (never started) — the batch seals at once
        // and stays empty: no phantom round enters the stack.
        assertTrue(undo.sealIfQueueIdle())
        assertFalse(undo.hasRounds)
    }

    @Test
    fun `at most 50 rounds are kept`() {
        val undo = UndoCoordinator()
        repeat(60) { i ->
            undo.openBatchIfClosed()
            undo.expect(1)
            undo.record("/f$i.jpg", "/cat/f$i.jpg")
            assertTrue(undo.sealIfQueueIdle())
        }
        var kept = 0
        while (undo.popRound() != null) kept++
        assertEquals(UndoCoordinator.MAX_ROUNDS, kept)
    }
}
