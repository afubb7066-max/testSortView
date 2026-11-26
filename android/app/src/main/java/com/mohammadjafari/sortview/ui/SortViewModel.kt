package com.mohammadjafari.sortview.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohammadjafari.sortview.R
import com.mohammadjafari.sortview.data.AppConfigStore
import com.mohammadjafari.sortview.data.Category
import com.mohammadjafari.sortview.data.SortConfig
import com.mohammadjafari.sortview.data.UndoCoordinator
import com.mohammadjafari.sortview.data.normalizeCategories
import com.mohammadjafari.sortview.fs.Paths
import com.mohammadjafari.sortview.fs.TransferEngine
import com.mohammadjafari.sortview.fs.TransferEvent
import com.mohammadjafari.sortview.media.MediaGroup
import com.mohammadjafari.sortview.media.ThumbnailEngine
import com.mohammadjafari.sortview.media.groupForExtension
import com.mohammadjafari.sortview.scan.FolderScanner
import com.mohammadjafari.sortview.scan.ScanError
import com.mohammadjafari.sortview.scan.ScanException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * One file row in the grid. [group] comes from the desktop grouping tables
 * (media/MediaTypes.kt, main.py L308-315/L835-853) and decides whether the
 * tile shows a real thumbnail (video/PDF/archive) or a gradient type card.
 */
data class SortFile(val path: String, val name: String, val group: MediaGroup) {
    val ext: String = Paths.splitName(name).second.drop(1)
}

data class SortUiState(
    // config-backed state (config.json schema, contract #1)
    val candidateFolder: String = "",
    val folderUserSelected: Boolean = false,
    val lastBrowseDir: String = "",
    val includeSubfolders: Boolean = false,
    val categories: List<Category> = emptyList(),
    // grid
    val files: List<SortFile> = emptyList(),
    val scanning: Boolean = false,
    val foundCount: Int = 0,
    // thumbnail generation — bumped on every rescan like the desktop's
    // _thumbnail_generation (main.py L2846); produceState keys on it
    val thumbGeneration: Int = 0,
    // status line
    val status: StatusMessage? = null,
    // selection
    val selection: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    // tile tints (desktop backgrounds #fff5cc / #ffdddd)
    val movingPaths: Set<String> = emptySet(),
    val errorPaths: Set<String> = emptySet(),
    // transfer counters (desktop status bar)
    val done: Int = 0,
    val failed: Int = 0,
    val queued: Int = 0,
    val active: Int = 0,
    val busy: Boolean = false,
    val canUndo: Boolean = false,
    // dialogs / menus
    val showFolderPicker: Boolean = false,
    val pickerError: StatusMessage? = null,
    val categoryDialogFor: Int? = null, // -1 = add, >= 0 = edit index
    val categoryDialogError: StatusMessage? = null,
    val categoryMenuFor: Int? = null,
)

/**
 * Phase 1 core (folder scanning, image grid, category management with the
 * 0-9 shortcuts, the safe transfer engine and the undo round) plus the
 * Phase 2 thumbnail engine — all behavior mirrored from desktop/main.py
 * (upstream v1.10.3), see the per-method comments for the exact line
 * references.
 */
class SortViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppConfigStore(app)
    private var rawConfig: kotlinx.serialization.json.JsonObject = store.load()
    private var cfg: SortConfig = store.parse(rawConfig)

    private val engine = TransferEngine(viewModelScope, app)
    private val undo = UndoCoordinator()
    private val thumbs = ThumbnailEngine(app)

    private val _state = MutableStateFlow(SortUiState())
    val state: StateFlow<SortUiState> = _state

    private var scanJob: Job? = null
    private var lastMoveRequestAt = 0.0
    private var lastKeyShortcutAt = 0.0
    private var moveEnqueuePending = false
    private var statusAfterRescan: StatusMessage? = null

    init {
        viewModelScope.launch {
            engine.events.collect { onEngineEvent(it) }
        }
        // Desktop __init__: restore the saved folder under the same guards
        // (L1963-1978), save the config once (L2062), rescan (L2063).
        val (folder, userSelected) = applySavedFolderRules()
        _state.update {
            it.copy(
                candidateFolder = folder,
                folderUserSelected = userSelected,
                lastBrowseDir = cfg.lastBrowseDir,
                includeSubfolders = cfg.includeSubfolders,
                categories = normalizeCategories(cfg.categories),
            )
        }
        saveConfig()
        if (folder.isNotEmpty()) {
            scanFolder()
        } else {
            _state.update { it.copy(status = statusOf(R.string.msg_please_select_folder)) }
        }
    }

    private fun applySavedFolderRules(): Pair<String, Boolean> {
        var saved = cfg.candidateFolder.trim()
        var selected = cfg.folderUserSelected
        // Old builds wrote a default path without a user action — ignore it
        // unless the config explicitly says the user selected it.
        if (!selected) saved = ""
        if (saved.isNotEmpty()) {
            // Guard shared configs that came from a Windows desktop.
            val f = File(saved)
            if (f.name.lowercase() == "to_sort" && f.parentFile?.name?.lowercase() == "desktop") {
                saved = ""
                selected = false
            }
        }
        return saved to selected
    }

    // ---------------- config (main.py L2091) ----------------

    private fun effectiveLanguage(): String {
        val saved = cfg.language
        if (saved == "fa" || saved == "en") return saved
        val loc = getApplication<Application>().resources.configuration.locales.get(0)
        return if (loc.language == "fa") "fa" else "en"
    }

    private fun saveConfig() {
        val st = _state.value
        val current = SortConfig(
            // candidate_folder stays empty unless the user actually picked a
            // folder (desktop L2093-2094).
            candidateFolder = if (st.folderUserSelected && st.candidateFolder.isNotEmpty()) st.candidateFolder else "",
            folderUserSelected = st.folderUserSelected && st.candidateFolder.isNotEmpty(),
            lastBrowseDir = st.lastBrowseDir,
            includeSubfolders = st.includeSubfolders,
            categories = normalizeCategories(st.categories),
            thumbSize = cfg.thumbSize,
            theme = cfg.theme,
            language = effectiveLanguage(),
        )
        rawConfig = store.merge(rawConfig, store.knownKeys(current, current.categories))
        val snapshot = rawConfig
        viewModelScope.launch(Dispatchers.IO) { store.save(snapshot) }
    }

    private fun rememberBrowseDir(folder: String) {
        try {
            val trimmed = folder.trim()
            if (trimmed.isEmpty()) return
            var p = File(trimmed)
            if (p.isFile) p = p.parentFile ?: p
            // The path text is stored even when it does not exist yet, so the
            // next dialog starts near it (desktop L2115-2133).
            _state.update { it.copy(lastBrowseDir = p.path) }
            saveConfig()
        } catch (_: Exception) {
        }
    }

    // ---------------- scanning (main.py L2843) ----------------

    fun refresh() {
        scanFolder()
    }

    private fun scanFolder() {
        val folder = _state.value.candidateFolder
        scanJob?.cancel()
        // Desktop load_files bumps _scan_generation and _thumbnail_generation
        // here (L2846): in-flight thumbnails from the old list must not leak
        // into the new one.
        thumbs.invalidate()
        _state.update { it.copy(thumbGeneration = it.thumbGeneration + 1) }
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            if (folder.isEmpty()) {
                _state.update {
                    it.copy(
                        files = emptyList(),
                        scanning = false,
                        selection = emptySet(),
                        selectionMode = false,
                        movingPaths = emptySet(),
                        errorPaths = emptySet(),
                        status = statusOf(R.string.msg_please_select_folder),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    scanning = true,
                    files = emptyList(),
                    selection = emptySet(),
                    selectionMode = false,
                    movingPaths = emptySet(),
                    errorPaths = emptySet(),
                    foundCount = 0,
                    status = statusOf(R.string.msg_scanning_folder),
                )
            }
            try {
                val buffer = mutableListOf<SortFile>()
                var found = 0
                FolderScanner().scan(File(folder), _state.value.includeSubfolders) { f ->
                    found++
                    if (found % 100 == 0) {
                        // "Found: {n}" every 100 files (desktop L2893).
                        _state.update { s ->
                            s.copy(foundCount = found, status = statusOf(R.string.status_found_n, "n" to found))
                        }
                    }
                    buffer.add(f.toSortFile())
                }
                // Natural name sort, desktop default "Name" mode (L1304/L2902).
                val files = buffer.sortedWith { a, b -> Paths.compareNatural(a.name, b.name) }
                _state.update { it.copy(scanning = false, files = files) }
                val after = statusAfterRescan
                statusAfterRescan = null
                _state.update {
                    // The list is populated in one step on Android, so the
                    // desktop's "populating..." hop collapses into Ready.
                    it.copy(status = after ?: statusOf(R.string.msg_ready))
                }
            } catch (e: ScanException) {
                _state.update {
                    it.copy(
                        scanning = false,
                        status = when (e.kind) {
                            ScanError.NOT_A_DIRECTORY -> statusOf(R.string.msg_folder_not_exist)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(scanning = false, status = statusOf(R.string.msg_scan_failed)) }
            }
        }
    }

    private fun File.toSortFile(): SortFile {
        val suffix = Paths.splitName(name).second
        return SortFile(path = absolutePath, name = name, group = groupForExtension(suffix.drop(1)))
    }

    // ---------------- thumbnails (Phase 2, main.py L1152-1273) ----------------

    /**
     * Lazy thumbnail loader for the grid tiles. Compose only composes
     * visible tiles, so per-tile on-demand loading IS the desktop's
     * "visible thumbnails first, rest in background" queue
     * (main.py L3002-3051) collapsed into one mechanism. Images are not
     * routed through here — Coil covers them with its own caches.
     */
    suspend fun loadThumbnail(file: SortFile, reqPx: Int): Bitmap? =
        thumbs.thumbnailFor(file.path, file.ext, reqPx)

    // ---------------- folder picker (main.py L3919) ----------------

    fun openFolderPicker() {
        _state.update { it.copy(showFolderPicker = true, pickerError = null) }
    }

    fun dismissFolderPicker() {
        _state.update { it.copy(showFolderPicker = false, pickerError = null) }
    }

    /** Start dir for the picker: candidate folder, else last browse dir (desktop L3925). */
    fun pickerStartPath(): String {
        val st = _state.value
        return st.candidateFolder.ifEmpty {
            st.lastBrowseDir.ifEmpty { DEFAULT_PICKER_PATH }
        }
    }

    fun applyFolderSelection(path: String, recursive: Boolean) {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(pickerError = statusOf(R.string.err_path_not_folder)) }
            return
        }
        val f = File(trimmed)
        if (!f.isDirectory) {
            // "Invalid folder" / "Selected path is not a folder." — the
            // desktop shows both as a modal; here the message sits inside
            // the picker next to the field.
            _state.update {
                it.copy(pickerError = if (f.exists()) statusOf(R.string.err_path_not_folder) else statusOf(R.string.err_invalid_folder))
            }
            return
        }
        _state.update {
            it.copy(
                candidateFolder = f.path,
                folderUserSelected = true,
                includeSubfolders = recursive,
                showFolderPicker = false,
                pickerError = null,
            )
        }
        rememberBrowseDir(f.path)
        saveConfig()
        scanFolder()
    }

    // ---------------- categories (main.py L2632-2783) ----------------

    fun startAddCategory() {
        _state.update { it.copy(categoryDialogFor = ADD_CATEGORY, categoryDialogError = null) }
    }

    fun startEditCategory(idx: Int) {
        _state.update {
            if (idx in it.categories.indices) it.copy(categoryDialogFor = idx, categoryDialogError = null) else it
        }
    }

    fun dismissCategoryDialog() {
        _state.update { it.copy(categoryDialogFor = null, categoryDialogError = null) }
    }

    fun submitCategoryDialog(name: String, path: String) {
        val st = _state.value
        val idx = st.categoryDialogFor ?: return
        val app = getApplication<Application>()
        val n = name.trim().ifEmpty { app.getString(R.string.untitled) }
        val p = path.trim()
        if (p.isEmpty()) {
            _state.update { it.copy(categoryDialogError = statusOf(R.string.err_path_empty)) }
            return
        }
        // Only absolute paths are accepted, or a relative path would scatter
        // files who knows where (desktop L2659-2667).
        if (!File(p).isAbsolute) {
            _state.update {
                it.copy(categoryDialogError = statusOf(R.string.err_path_relative, "example" to ABSOLUTE_EXAMPLE))
            }
            return
        }
        val cat = Category(n, p)
        val newCats: List<Category> = if (idx == ADD_CATEGORY) {
            normalizeCategories(st.categories + cat)
        } else {
            val list = st.categories.toMutableList()
            list[idx] = cat
            normalizeCategories(list)
        }
        _state.update {
            it.copy(
                categories = newCats,
                categoryDialogFor = null,
                categoryDialogError = null,
                status = statusOf(if (idx == ADD_CATEGORY) R.string.msg_category_added else R.string.msg_category_edited),
            )
        }
        saveConfig()
    }

    fun removeCategory(idx: Int) {
        val st = _state.value
        if (idx !in st.categories.indices) return
        _state.update {
            it.copy(
                categories = it.categories.filterIndexed { i, _ -> i != idx },
                status = statusOf(R.string.msg_category_removed),
            )
        }
        saveConfig()
    }

    private fun moveCategory(idx: Int, delta: Int) {
        val st = _state.value
        val j = idx + delta
        if (idx !in st.categories.indices || j !in st.categories.indices) return
        val list = st.categories.toMutableList()
        val tmp = list[idx]
        list[idx] = list[j]
        list[j] = tmp
        _state.update {
            it.copy(
                categories = normalizeCategories(list),
                status = statusOf(R.string.msg_category_order_changed),
            )
        }
        saveConfig()
    }

    fun openCategoryMenu(idx: Int) {
        _state.update { it.copy(categoryMenuFor = idx) }
    }

    fun dismissCategoryMenu() {
        _state.update { it.copy(categoryMenuFor = null) }
    }

    fun onCategoryMenuAction(action: CategoryMenuAction, idx: Int) {
        dismissCategoryMenu()
        when (action) {
            CategoryMenuAction.EDIT -> startEditCategory(idx)
            CategoryMenuAction.REMOVE -> removeCategory(idx)
            CategoryMenuAction.MOVE_UP -> moveCategory(idx, -1)
            CategoryMenuAction.MOVE_DOWN -> moveCategory(idx, +1)
        }
    }

    // ---------------- selection ----------------

    fun onTileTap(path: String) {
        _state.update {
            if (it.selectionMode) {
                val sel = if (path in it.selection) it.selection - path else it.selection + path
                it.copy(selection = sel)
            } else {
                it.copy(selection = setOf(path))
            }
        }
    }

    fun onTileLongPress(path: String) {
        _state.update { it.copy(selectionMode = true, selection = it.selection + path) }
    }

    fun exitSelectionMode() {
        _state.update { it.copy(selectionMode = false, selection = emptySet()) }
    }

    fun selectAll() {
        _state.update {
            it.copy(selectionMode = true, selection = it.files.map { f -> f.path }.toSet())
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    // ---------------- moving files (main.py L3598-3693) ----------------

    fun onCategoryTap(idx: Int) {
        moveSelectedToCategory(idx)
    }

    fun moveSelectedToCategory(idx: Int) {
        val st = _state.value
        if (st.selection.isEmpty()) {
            _state.update { it.copy(status = statusOf(R.string.msg_no_files_selected)) }
            return
        }
        if (idx < 0 || idx >= st.categories.size) {
            _state.update { it.copy(status = statusOf(R.string.msg_invalid_category_index)) }
            return
        }
        // One-shot: ignore hold/repeat and a second press while enqueueing.
        val now = System.nanoTime() / 1_000_000_000.0
        if (now - lastMoveRequestAt < MOVE_DEBOUNCE_S) return
        if (moveEnqueuePending) return
        lastMoveRequestAt = now
        val dst = st.categories[idx].path.trim()
        if (dst.isEmpty()) {
            _state.update { it.copy(status = statusOf(R.string.msg_category_path_empty)) }
            return
        }
        moveEnqueuePending = true
        _state.update { it.copy(status = statusOf(R.string.msg_preparing_transfer)) }
        try {
            // The desktop waits 200 ms here to let VLC release file handles;
            // Phase 1 has no preview player, so the enqueue runs directly.
            doEnqueue(st.selection.toList(), dst)
        } finally {
            moveEnqueuePending = false
        }
    }

    private fun doEnqueue(srcList: List<String>, dst: String) {
        var queued = 0
        var skipped = 0
        val dstF = File(dst)
        for (src in srcList) {
            val srcF = File(src)
            if (Paths.samePath(srcF.parentFile, dstF)) {
                skipped++
                continue
            }
            if (engine.enqueue(src, dst)) {
                queued++
            } else {
                skipped++
            }
        }
        if (queued > 0) {
            var msg = statusOf(R.string.msg_queued_n, "n" to queued)
            if (skipped > 0) msg = msg + statusOf(R.string.msg_skipped_n, "n" to skipped)
            _state.update { it.copy(status = msg) }
            // Open a new undo batch; if a round is still in flight the two
            // merge into one undo entry (desktop L3674-3685).
            undo.openBatchIfClosed()
            undo.expect(queued)
        } else if (skipped > 0) {
            _state.update { it.copy(status = statusOf(R.string.msg_no_new_files_queued)) }
        } else {
            _state.update { it.copy(status = statusOf(R.string.msg_no_files_queued)) }
        }
    }

    private fun onEngineEvent(e: TransferEvent) {
        when (e) {
            is TransferEvent.Started -> _state.update {
                it.copy(
                    movingPaths = it.movingPaths + e.src,
                    status = statusOf(R.string.msg_moving_name, "name" to File(e.src).name),
                )
            }
            is TransferEvent.Finished -> onTransferFinished(e.src, e.newPath)
            is TransferEvent.Error -> onTransferError(e.src, e.message)
            is TransferEvent.QueueUpdated -> onQueueUpdated(e.queued, e.active)
        }
    }

    private fun onTransferFinished(src: String, newPath: String) {
        undo.record(src, newPath)
        val oldName = File(src).name
        val newName = File(newPath).name
        _state.update {
            val sel = it.selection - src
            it.copy(
                done = it.done + 1,
                files = it.files.filterNot { f -> f.path == src },
                selection = sel,
                selectionMode = it.selectionMode && sel.isNotEmpty(),
                movingPaths = it.movingPaths - src,
                status = if (newName != oldName) {
                    statusOf(R.string.msg_moved_as_name, "name" to newName)
                } else {
                    statusOf(R.string.msg_transfer_finished)
                },
            )
        }
    }

    private fun onTransferError(src: String, errmsg: String) {
        undo.reportBackWithoutPair()
        _state.update {
            it.copy(
                failed = it.failed + 1,
                movingPaths = it.movingPaths - src,
                errorPaths = it.errorPaths + src,
                status = statusOf(R.string.msg_error_moving, "name" to File(src).name, "err" to errmsg),
            )
        }
    }

    private fun onQueueUpdated(queued: Int, active: Int) {
        _state.update { it.copy(queued = queued, active = active, busy = active > 0 || queued > 0) }
        if (queued == 0 && active == 0) {
            // Queue drained — seal the open undo batch only when every
            // expected file reported back (desktop L3737-3745).
            if (undo.sealIfQueueIdle()) refreshUndoState()
        }
    }

    fun cancelTransfers() {
        val dropped = engine.cancelAll()
        // Dropped files never started and will never report back.
        undo.adjustDropped(dropped)
        // cancel_all() emitted queue_updated(0, 0) before the expected count
        // was fixed up — re-run the seal check now (desktop L3747-3763).
        if (engine.isIdle() && undo.sealIfQueueIdle()) refreshUndoState()
        _state.update { it.copy(status = statusOf(R.string.msg_cancellation_requested)) }
    }

    // ---------------- undo (main.py L3766-3884) ----------------

    private fun refreshUndoState() {
        _state.update { it.copy(canUndo = undo.hasRounds && !undo.isActive) }
    }

    fun undoLastMove() {
        if (undo.isActive) return
        val batch = undo.popRound()
        if (batch == null) {
            _state.update { it.copy(status = statusOf(R.string.msg_nothing_to_undo)) }
            refreshUndoState()
            return
        }
        undo.setBusy(true)
        refreshUndoState()
        _state.update { it.copy(status = statusOf(R.string.msg_undoing_last_move)) }
        viewModelScope.launch(Dispatchers.IO) {
            val restored = mutableListOf<Pair<String, String>>()
            val failed = mutableListOf<Pair<String, String>>()
            val gone = mutableListOf<Pair<String, String>>()
            for ((src, dst) in batch) {
                try {
                    val dstF = File(dst)
                    val srcF = File(src)
                    // The file left the category folder (moved/deleted by
                    // hand) — nothing to restore, never re-queued.
                    if (!dstF.isFile) {
                        gone.add(src to dst)
                        continue
                    }
                    srcF.parentFile?.mkdirs()
                    var target = srcF
                    if (target.exists()) target = Paths.uniqueDestination(srcF)
                    restoreMove(dstF, target)
                    restored.add(src to target.path)
                } catch (_: Exception) {
                    failed.add(src to dst)
                }
            }
            withContext(Dispatchers.Main.immediate) {
                onUndoFinished(restored, failed, gone)
            }
        }
    }

    /**
     * Move the category copy back without ever overwriting: rename first,
     * and when rename fails (different filesystems) an exclusive copy + size
     * check + delete, like the desktop's PermissionError fallback (L3840).
     */
    private fun restoreMove(dstF: File, target: File) {
        if (dstF.renameTo(target)) return
        try {
            FileChannel.open(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
                FileInputStream(dstF).use { input ->
                    val buf = ByteArray(Paths.TRANSFER_CHUNK_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        var w = 0
                        while (w < n) w += ch.write(ByteBuffer.wrap(buf, w, n - w))
                    }
                }
                ch.force(true)
                if (ch.size() != dstF.length()) throw IOException("undo copy size mismatch")
            }
            Paths.forceUnlink(dstF)
        } catch (e: Throwable) {
            try {
                if (target.exists()) Paths.forceUnlink(target)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun onUndoFinished(
        restored: List<Pair<String, String>>,
        failed: List<Pair<String, String>>,
        gone: List<Pair<String, String>>,
    ) {
        undo.setBusy(false)
        if (failed.isNotEmpty()) {
            // Re-queue only pairs whose file is still in the category folder;
            // gone pairs would fail forever and jam the stack (L3855-3863).
            undo.requeueRetry(failed.filter { File(it.second).isFile() })
        }
        val n = restored.size
        val m = failed.size
        val g = gone.size
        statusAfterRescan = when {
            n > 0 && m > 0 -> statusOf(R.string.msg_undone_restored_kept, "n" to n, "m" to m)
            n > 0 && g > 0 -> statusOf(R.string.msg_undone_back_moved, "n" to n, "m" to g)
            n > 0 -> statusOf(R.string.msg_undone_back_n, "n" to n)
            m > 0 -> statusOf(R.string.msg_nothing_still_in_category)
            g > 0 -> statusOf(R.string.msg_nothing_no_longer_in_category)
            else -> null
        }
        refreshUndoState()
        // Rescan so the restored files reappear; the summary is shown when
        // the rescan finishes (desktop _status_after_rescan).
        scanFolder()
    }

    // ---------------- digit shortcuts (main.py L4022) ----------------

    /** Digit 0-9 pressed (on-screen category chips or a hardware keyboard). */
    fun onDigit(k: Int) {
        val now = System.nanoTime() / 1_000_000_000.0
        if (now - lastKeyShortcutAt < KEY_DEBOUNCE_S) return
        lastKeyShortcutAt = now
        val st = _state.value
        if (k in st.categories.indices) {
            moveSelectedToCategory(k)
        } else {
            // The desktop shows an information dialog here; on a phone the
            // status line is the equivalent surface.
            _state.update { it.copy(status = statusOf(R.string.err_no_category_assigned, "k" to k)) }
        }
    }

    companion object {
        /** Sentinel index for the "Add Category" dialog. */
        const val ADD_CATEGORY = -1

        /** Android example path for the "enter an absolute path" warning. */
        const val ABSOLUTE_EXAMPLE = "/storage/emulated/0/Pictures"

        /** Last resort start dir for the picker (primary storage root). */
        const val DEFAULT_PICKER_PATH = "/storage/emulated/0"

        private const val MOVE_DEBOUNCE_S = 0.30
        private const val KEY_DEBOUNCE_S = 0.25
    }
}

enum class CategoryMenuAction { EDIT, REMOVE, MOVE_UP, MOVE_DOWN }
