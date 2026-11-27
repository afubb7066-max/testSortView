package com.mohammadjafari.sortview.fs

import android.content.Context
import com.mohammadjafari.sortview.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Events the engine emits; order mirrors the desktop signals (main.py L1469). */
sealed class TransferEvent {
    data class Started(val src: String, val dstDir: String) : TransferEvent()
    data class Finished(val src: String, val newPath: String) : TransferEvent()
    data class Error(val src: String, val message: String) : TransferEvent()
    data class QueueUpdated(val queued: Int, val active: Int) : TransferEvent()
}

/** Transfer aborted because a failure leaves the source file untouched. */
open class TransferException(message: String) : Exception(message)

/** The user asked to cancel; destination leftovers are cleaned up. */
class TransferCancelled(message: String) : TransferException(message)

/**
 * Safe transfer engine — behavioral port of the desktop TransferManager
 * (main.py L1476-1693):
 *
 *  - a queue of (src, dst_dir) jobs served by 2 workers (desktop uses
 *    max_workers=2, main.py L2052), deduped by source path;
 *  - cancel_all drops every queued job without ever starting it and makes
 *    running jobs stop at the next step boundary;
 *  - the destination name is reserved up front via unique_destination so
 *    concurrent workers can never collide on "name (1).ext";
 *  - nothing is ever overwritten: a size mismatch or any error removes the
 *    partial copy and keeps the source alive.
 *
 * Move strategy, fastest first. The desktop _do_move (main.py L1652) tries
 * its os.link fast path (L1610) before the full copy
 * (_copy_exclusive_then_remove, main.py L1609); the Android port mirrors
 * that fallback chain:
 *   1. hardlink (same inode): instant, needs no extra space, and link()
 *      never replaces an existing target, so the never-overwrite rule
 *      holds by construction. Unsupported on FUSE/exFAT/FAT32 shared
 *      storage — it fails fast there and the next step takes over;
 *   2. atomic rename (same volume): atomic, no data copy. rename(2) would
 *      silently replace the target, so the name is claimed first with an
 *      exclusive zero-byte placeholder and the move replaces only that;
 *      cross-volume attempts fail and fall through;
 *   3. exclusive copy: CREATE_NEW + 2 MiB chunks + fsync + size verify,
 *      and only then the source is deleted — the universal fallback.
 * The engine lives in the application-scoped [TransferHub] (guarded by the
 * com.mohammadjafari.sortview.transfer.TransferService foreground service),
 * so transfers survive the UI being closed.
 */
class TransferEngine(
    private val scope: CoroutineScope,
    context: Context,
) {

    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<TransferEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: kotlinx.coroutines.flow.SharedFlow<TransferEvent> = _events

    private val msgCancelled: String = context.getString(R.string.msg_cancelled)
    private val msgSourceMissing: String = context.getString(R.string.msg_source_missing)
    private val msgAlreadyInDestination: String = context.getString(R.string.msg_already_in_destination)
    private val msgSizeMismatch: String = context.getString(R.string.msg_copy_size_mismatch)

    private data class Spec(val src: String, val dstDir: String, val generation: Int)

    private val lock = Any()
    private val knownSources = HashSet<String>()
    private val reservedDestinations = HashSet<String>()
    private val channel = Channel<Spec>(Channel.UNLIMITED)
    private var cancelGeneration = 0
    private var pendingCount = 0
    private var activeCount = 0
    private var workersStarted = false

    private fun startWorkers() {
        synchronized(lock) {
            if (workersStarted) return
            workersStarted = true
        }
        repeat(WORKERS) {
            scope.launch(Dispatchers.IO) {
                for (spec in channel) runItem(spec)
            }
        }
    }

    /**
     * Queue a move. Returns false when the source is already queued or in
     * flight (dedup — desktop enqueue L1493).
     */
    fun enqueue(src: String, dstDir: String): Boolean {
        startWorkers()
        synchronized(lock) {
            if (src in knownSources) return false
            knownSources.add(src)
            pendingCount++
            channel.trySend(Spec(src, dstDir, cancelGeneration))
        }
        emitQueueUpdated()
        return true
    }

    /**
     * Drop every queued (not yet started) job and cancel the active ones at
     * the next step boundary. Returns the number of dropped jobs — the
     * caller must not wait for them (desktop cancel_all L1505).
     */
    fun cancelAll(): Int {
        val dropped = synchronized(lock) {
            var d = 0
            while (true) {
                val r = channel.tryReceive()
                if (!r.isSuccess) break
                knownSources.remove(r.getOrThrow().src)
                pendingCount--
                d++
            }
            cancelGeneration++
            d
        }
        emitQueueUpdated()
        return dropped
    }

    /** True when nothing is queued and nothing is running (desktop is_idle L1529). */
    fun isIdle(): Boolean = synchronized(lock) { pendingCount == 0 && activeCount == 0 }

    private suspend fun runItem(spec: Spec) {
        synchronized(lock) {
            pendingCount--
            activeCount++
        }
        emitQueueUpdated()
        _events.tryEmit(TransferEvent.Started(spec.src, spec.dstDir))
        // IMPORTANT: the per-item outcome must be emitted BEFORE the
        // queue-count update for that item, or the undo batch could be
        // sealed while its last file is still unreported (desktop _on_done).
        val result = try {
            Result.success(doMove(spec.src, spec.dstDir, spec.generation))
        } catch (e: Exception) {
            Result.failure(e)
        }
        when {
            result.isSuccess -> _events.tryEmit(TransferEvent.Finished(spec.src, result.getOrThrow()))
            else -> {
                val err = result.exceptionOrNull()
                _events.tryEmit(TransferEvent.Error(spec.src, err?.message ?: err?.javaClass?.simpleName ?: "Error"))
            }
        }
        synchronized(lock) {
            activeCount--
            knownSources.remove(spec.src)
        }
        emitQueueUpdated()
    }

    private fun emitQueueUpdated() {
        val (q, a) = synchronized(lock) { pendingCount to activeCount }
        _events.tryEmit(TransferEvent.QueueUpdated(q, a))
    }

    private fun isCancelled(generation: Int): Boolean =
        synchronized(lock) { generation != cancelGeneration }

    /** Reserve a unique destination name for src in dstDir (desktop L1597). */
    private fun reserveDestination(src: File, dstDir: File): File {
        dstDir.mkdirs()
        val wanted = File(dstDir, src.name)
        return synchronized(lock) {
            val dst = Paths.uniqueDestination(wanted, reservedDestinations)
            reservedDestinations.add(dst.absolutePath)
            dst
        }
    }

    private fun releaseDestination(dst: File) {
        synchronized(lock) { reservedDestinations.remove(dst.absolutePath) }
    }

    /** Port of _do_move (main.py L1652) with the desktop's fast-path chain. */
    private fun doMove(src: String, dstDir: String, generation: Int): String {
        if (isCancelled(generation)) throw TransferCancelled(msgCancelled)
        val srcF = File(src)
        val dstDirF = File(dstDir)
        if (!srcF.exists() || !srcF.isFile) throw TransferException(msgSourceMissing)
        if (Paths.samePath(srcF.parentFile, dstDirF)) throw TransferException(msgAlreadyInDestination)
        val dst = reserveDestination(srcF, dstDirF)
        try {
            if (isCancelled(generation)) throw TransferCancelled(msgCancelled)
            // Fast paths first; each falls through to the next on failure
            // and the exclusive copy stays the universal fallback.
            if (tryHardlinkThenRemove(srcF, dst, generation)) return dst.absolutePath
            if (tryRenameSameVolume(srcF, dst, generation)) return dst.absolutePath
            return copyExclusiveThenRemove(srcF, dst, generation)
        } finally {
            releaseDestination(dst)
        }
    }

    /**
     * Desktop os.link fast path (main.py L1610): a same-inode hardlink is
     * instant and needs no extra space. link() never replaces an existing
     * target, so the never-overwrite rule holds without a race. Unsupported
     * on FUSE/exFAT/FAT32 shared storage (EPERM / EOPNOTSUPP) — the caller
     * falls through to the rename path.
     */
    private fun tryHardlinkThenRemove(srcF: File, dst: File, generation: Int): Boolean {
        if (isCancelled(generation)) throw TransferCancelled(msgCancelled)
        val linked = try {
            Files.createLink(dst.toPath(), srcF.toPath())
            true
        } catch (_: Exception) {
            false
        }
        if (!linked) return false
        try {
            if (isCancelled(generation)) throw TransferCancelled(msgCancelled)
            // Same inode, so the size check is a formality — kept for parity
            // with the desktop verify step (main.py L1634).
            if (dst.length() != srcF.length()) throw TransferException(msgSizeMismatch)
            Paths.forceUnlink(srcF)
            return true
        } catch (e: Throwable) {
            // Remove the new link; the source file itself is untouched.
            try {
                if (dst.exists()) Paths.forceUnlink(dst)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    /**
     * Same-volume rename fast path: atomic, no data copy, no extra space.
     *
     * rename(2) silently REPLACES an existing target, so this path claims
     * the name first with an exclusive zero-byte placeholder
     * (Files.createFile — the atomic equivalent of CREATE_NEW). The move
     * then atomically replaces OUR OWN placeholder, never anybody else's
     * file, so the never-overwrite rule holds without any race. If the
     * claim fails, the name was taken by someone else and the copy path
     * reports it cleanly; if the move fails (cross-volume, no atomic
     * rename, ...) the placeholder is released and the copy path takes
     * over. A completed move IS the finished transfer — no cancellation
     * check after it, because there is no partial state to clean up.
     */
    private fun tryRenameSameVolume(srcF: File, dst: File, generation: Int): Boolean {
        if (isCancelled(generation)) throw TransferCancelled(msgCancelled)
        val claimed = try {
            Files.createFile(dst.toPath())
            true
        } catch (_: Exception) {
            false
        }
        if (!claimed) return false
        return try {
            Files.move(
                srcF.toPath(), dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (_: Exception) {
            // Release the claim before falling through to the copy path;
            // if even the delete fails, the copy path's CREATE_NEW fails on
            // the stray placeholder and the transfer errors with the source
            // intact — never an overwrite.
            try {
                Paths.forceUnlink(dst)
            } catch (_: Exception) {
            }
            false
        }
    }

    /**
     * Port of _copy_exclusive_then_remove (main.py L1609). The exclusive
     * create (O_EXCL on the desktop) becomes FileChannel.open(CREATE_NEW):
     * it fails if anything took the name in the meantime, so no file is
     * ever overwritten even in a race.
     */
    private fun copyExclusiveThenRemove(srcF: File, dst: File, generation: Int): String {
        // Exclusive create: fails with FileAlreadyExistsException (an
        // IOException) when something took the name — the race-safe
        // equivalent of the desktop's O_EXCL os.open.
        val ch: FileChannel = FileChannel.open(dst.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        try {
            FileInputStream(srcF).use { input ->
                val buf = ByteArray(Paths.TRANSFER_CHUNK_SIZE)
                while (true) {
                    if (isCancelled(generation)) throw TransferCancelled(msgCancelled)
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        while (bb.hasRemaining()) ch.write(bb)
                    }
                }
            }
            try {
                ch.force(true)
            } catch (_: Exception) {
            }
            ch.close()
            // Best-effort attribute copy (shutil.copystat equivalent).
            try {
                dst.setLastModified(srcF.lastModified())
            } catch (_: Exception) {
            }
            if (isCancelled(generation)) throw TransferCancelled(msgCancelled)
            // Verify the copy is complete (size) before destroying the
            // source; on a mismatch the cleanup below removes the partial
            // destination and the source survives (desktop L1634-1638).
            if (dst.length() != srcF.length()) throw TransferException(msgSizeMismatch)
            Paths.forceUnlink(srcF)
            return dst.absolutePath
        } catch (e: Throwable) {
            try {
                ch.close()
            } catch (_: Exception) {
            }
            try {
                if (dst.exists()) Paths.forceUnlink(dst)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    companion object {
        /** The desktop main window runs TransferManager(max_workers=2) (L2052). */
        const val WORKERS = 2
    }
}
