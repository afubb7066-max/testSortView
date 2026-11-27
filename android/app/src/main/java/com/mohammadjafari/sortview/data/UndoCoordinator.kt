package com.mohammadjafari.sortview.data

/**
 * In-memory undo bookkeeping — port of the desktop undo state machine
 * (main.py L2004-2017 + L3674-3763 + L3773-3784):
 *
 *  - every transfer round opens a batch; completed moves are recorded as
 *    (original source, final destination) pairs so undo can also restore
 *    the original name after an auto-rename ("name (1).ext");
 *  - the batch may only be sealed when the queue drained AND every expected
 *    file reported back (finished or error) — otherwise a queue tick between
 *    the worker finishing and the UI hearing about it would lock the last
 *    file out of its round;
 *  - files dropped by cancel_all never started, so they can never report —
 *    the expected count is reduced by the dropped number instead;
 *  - the stack holds at most 50 rounds (compatibility contract #5) and is
 *    lost when the app closes, exactly like the desktop.
 */
class UndoCoordinator {

    private val stack = ArrayDeque<List<Pair<String, String>>>()
    private var pending: MutableList<Pair<String, String>> = mutableListOf()
    private var batchOpen = false
    private var expected = 0
    private var done = 0
    private var busy = false

    val hasRounds: Boolean get() = stack.isNotEmpty()
    val isActive: Boolean get() = busy

    /**
     * Open a new undo batch for a transfer round. If a round is still in
     * flight its batch stays open and the rounds merge into one undo entry
     * (all files return, which is always safe — desktop comment L3674).
     */
    fun openBatchIfClosed() {
        if (!batchOpen) {
            batchOpen = true
            pending = mutableListOf()
            expected = 0
            done = 0
        }
    }

    /** Every successful enqueue while the batch is open must report back. */
    fun expect(n: Int) {
        expected += n
    }

    /**
     * A finished move: record the (src, dst) pair it produced.
     * Returns false when the pair is a duplicate (the backlog drain and
     * the live event collector can both deliver the same pair — v1.0.2)
     * or when no batch is open; the caller must then skip its own
     * counters/UI updates so a file is accounted exactly once.
     */
    fun record(src: String, dst: String): Boolean {
        if (!batchOpen) return false
        if (pending.any { it.first == src && it.second == dst }) return false
        pending.add(src to dst)
        done++
        return true
    }

    /** True when this exact pair is already booked in the open batch. */
    fun alreadyRecorded(src: String, dst: String): Boolean =
        batchOpen && pending.any { it.first == src && it.second == dst }

    /** A failed/cancelled file has nothing to undo, but it has reported back. */
    fun reportBackWithoutPair() {
        if (!batchOpen) return
        done++
    }

    /**
     * Files dropped from the queue were never started, so they will never
     * report back — remove them from the expected count (main.py L3753).
     */
    fun adjustDropped(dropped: Int) {
        expected = maxOf(0, expected - dropped)
    }

    /**
     * Seal the open batch once the queue has drained (main.py L3744 + L3773).
     * Returns true when the batch was sealed.
     */
    fun sealIfQueueIdle(): Boolean {
        if (!batchOpen) return false
        if (done >= expected) {
            close()
            return true
        }
        return false
    }

    private fun close() {
        batchOpen = false
        if (pending.isNotEmpty()) {
            stack.addLast(pending)
            if (stack.size > MAX_ROUNDS) stack.removeFirst()
            pending = mutableListOf()
        }
    }

    fun setBusy(b: Boolean) {
        busy = b
    }

    /** Pop the newest round for undo, or null when the stack is empty. */
    fun popRound(): List<Pair<String, String>>? = stack.removeLastOrNull()

    /**
     * Failed undo pairs whose file is still in the category folder get
     * re-queued as a new (newest) round so older rounds never jam
     * (main.py L3855-3863).
     */
    fun requeueRetry(retry: List<Pair<String, String>>) {
        if (retry.isEmpty()) return
        stack.addLast(retry)
        if (stack.size > MAX_ROUNDS) stack.removeFirst()
    }

    companion object {
        /** Compatibility contract #5: the last 50 rounds, in memory only. */
        const val MAX_ROUNDS = 50
    }
}
