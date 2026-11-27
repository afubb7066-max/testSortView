package com.mohammadjafari.sortview.fs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/** Queue snapshot rendered by the foreground service notification. */
data class TransferProgress(val queued: Int = 0, val active: Int = 0) {
    val idle: Boolean get() = queued == 0 && active == 0
}

/**
 * Application-scoped owner of the [TransferEngine].
 *
 * The engine used to live inside SortViewModel's viewModelScope, so its
 * workers died with the UI: backgrounding the app mid-transfer let the
 * system kill the process and cut the queue in half (source files stayed
 * safe — they are only deleted after the size verify — but the transfer
 * stopped). Now the engine and its workers live for the whole process and
 * the com.mohammadjafari.sortview.transfer.TransferService foreground
 * service keeps that process alive while work is queued or running:
 *
 *  - [engine] lazily creates the app-lifetime engine (idempotent);
 *  - [progress] mirrors the engine's queue_updated ticks for the service
 *    notification;
 *  - finished (src, dst) pairs the UI never saw (its collector died) are
 *    kept in a bounded backlog; [drainCompleted] hands them to the next
 *    ViewModel so the moves stay undoable, and [acknowledge] lets a live
 *    collector retire its own pairs so replays never duplicate them;
 *  - drops counted by the notification's cancel action are buffered
 *    ([cancelFromService] / [takeDropped]) — the undo coordinator must
 *    reduce its expected count for files that never started.
 */
object TransferHub {

    /** Process-lifetime scope: outlives every ViewModel. Never cancelled. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _progress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = _progress

    /** Finished pairs waiting to be re-attached to the undo bookkeeping. */
    private val completedBacklog = ConcurrentLinkedQueue<Pair<String, String>>()

    /** Pairs already seen by a live UI collector (skip re-queueing them). */
    private val acknowledged = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    /** Drops counted by the notification cancel action, awaiting a ViewModel. */
    @Volatile
    private var droppedPending = 0

    @Volatile
    private var engineRef: TransferEngine? = null

    /** The app-lifetime engine; created lazily on first use (idempotent). */
    fun engine(context: Context): TransferEngine {
        engineRef?.let { return it }
        return synchronized(this) {
            engineRef?.let { return it }
            val engine = TransferEngine(scope, context.applicationContext)
            engineRef = engine
            scope.launch {
                engine.events.collect { ev ->
                    when (ev) {
                        is TransferEvent.QueueUpdated ->
                            _progress.value = TransferProgress(ev.queued, ev.active)
                        is TransferEvent.Finished -> backlogAdd(ev.src to ev.newPath)
                        else -> Unit
                    }
                }
            }
            engine
        }
    }

    private fun backlogAdd(pair: Pair<String, String>) {
        // A live UI collector already retired this pair — do not queue it.
        if (!acknowledged.remove(pair)) {
            completedBacklog.add(pair)
            // Bound the backlog: undo holds 50 rounds anyway, so a
            // long-lived process without a drain cannot grow this forever.
            while (completedBacklog.size > BACKLOG_CAP) completedBacklog.poll()
        }
    }

    /**
     * The UI collector saw this Finished pair itself — retire it from the
     * backlog so a future drain cannot double-record it. Safe in both
     * orderings (see [backlogAdd]).
     */
    fun acknowledge(src: String, dst: String) {
        val pair = src to dst
        if (!completedBacklog.remove(pair)) {
            acknowledged.add(pair)
        }
    }

    /**
     * Finished (src, dst) pairs recorded while no UI was subscribed —
     * called once when a new ViewModel attaches. A pair finishing inside
     * the attach window may additionally be replayed later (non-destructive
     * duplicate: [acknowledge] retires it as soon as a collector saw it).
     */
    fun drainCompleted(): List<Pair<String, String>> {
        acknowledged.clear()
        val out = mutableListOf<Pair<String, String>>()
        while (true) {
            val p = completedBacklog.poll() ?: break
            out.add(p)
        }
        return out
    }

    /** Drops counted by the notification cancel action since the last take. */
    fun takeDropped(): Int = synchronized(this) {
        val n = droppedPending
        droppedPending = 0
        n
    }

    /** UI-side cancel: no backlog bookkeeping, the caller reconciles undo. */
    fun cancelAll(context: Context): Int = engine(context).cancelAll()

    /** Notification cancel action: buffer the dropped count for reconciliation. */
    fun cancelFromService(context: Context): Int {
        val dropped = engine(context).cancelAll()
        if (dropped > 0) {
            synchronized(this) { droppedPending += dropped }
        }
        return dropped
    }

    /** Authoritative idle state for the foreground service stop decision. */
    fun isIdle(): Boolean = engineRef?.isIdle() ?: true

    private companion object {
        const val BACKLOG_CAP = 1024
    }
}
