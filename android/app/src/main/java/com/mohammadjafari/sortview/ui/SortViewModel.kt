package com.mohammadjafari.sortview.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mohammadjafari.sortview.R
import com.mohammadjafari.sortview.data.AppConfigStore
import com.mohammadjafari.sortview.data.AndroidPrefs
import com.mohammadjafari.sortview.data.Category
import com.mohammadjafari.sortview.data.CategorySets
import com.mohammadjafari.sortview.data.FeedPrefs
import com.mohammadjafari.sortview.data.SortConfig
import com.mohammadjafari.sortview.data.UndoCoordinator
import com.mohammadjafari.sortview.data.normalizeCategories
import com.mohammadjafari.sortview.fs.Filters
import com.mohammadjafari.sortview.fs.Paths
import com.mohammadjafari.sortview.fs.TransferEvent
import com.mohammadjafari.sortview.fs.TransferHub
import com.mohammadjafari.sortview.media.AUDIO_EXTS
import com.mohammadjafari.sortview.media.MediaGroup
import com.mohammadjafari.sortview.media.ThumbnailEngine
import com.mohammadjafari.sortview.media.groupForExtension
import com.mohammadjafari.sortview.scan.FolderScanner
import com.mohammadjafari.sortview.scan.ScanError
import com.mohammadjafari.sortview.scan.ScanException
import com.mohammadjafari.sortview.transfer.TransferService
import com.mohammadjafari.sortview.ui.theme.SortThemes
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
 * Which surface the folder picker is open FOR: the browse folder (source)
 * or the destination path of the open category dialog (v1.0.2 — the
 * desktop Browse button parity; nobody types /storage/emulated/0/...
 * by hand on a phone).
 */
enum class PickerTarget { SOURCE, CATEGORY }

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
    val pickerTarget: PickerTarget = PickerTarget.SOURCE,
    val pickerError: StatusMessage? = null,
    val categoryDialogFor: Int? = null, // -1 = add, >= 0 = edit index
    // v1.0.2: the category dialog fields live in the state (not remember{})
    // so typing survives the folder-picker detour (RootApp swaps screens)
    // and rotation; null = dialog closed.
    val categoryDialogName: String? = null,
    val categoryDialogPath: String? = null,
    val categoryDialogError: StatusMessage? = null,
    val categoryMenuFor: Int? = null,
    // Phase 3: full-screen preview dialog (desktop preview panel parity).
    val previewPath: String? = null,
    // Phase 4: category sets + thumbnail cache + about (tool dialogs).
    val setSaveDialog: Boolean = false,
    val setLoadDialog: Boolean = false,
    val setFiles: List<String> = emptyList(),
    val pendingLoadSet: String? = null,
    val confirmClearThumbs: Boolean = false,
    val showAbout: Boolean = false,
    // Phase 4: session view state. The desktop keeps the filter text and
    // the sort mode on its widgets and does NOT persist them in config.json
    // (main.py L2260-2265); thumb_size IS persisted (L2098).
    val filterText: String = "",
    val sortByName: Boolean = true,
    val thumbSize: Int = Paths.DEFAULT_THUMB,
    // Phase 4: config-backed theme (desktop THEMES keys, default "dark").
    val theme: String = "dark",
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

    // App-lifetime engine owned by TransferHub: its 2 workers and their
    // queue live in the process, not in this ViewModel, so a move survives
    // the UI being closed or backgrounded (TransferService keeps the
    // process alive). Pairs finished while no UI listened are drained in
    // the init block below.
    private val engine = TransferHub.engine(app)
    private val undo = UndoCoordinator()
    private val thumbs = ThumbnailEngine(app)
    private val sets = CategorySets(app)

    // v1.1.0: Android-only feed preferences (quick dock pins, swipe
    // targets) — they live in filesDir/android_prefs.json via [AndroidPrefs],
    // NEVER in config.json: the desktop normalize drops unknown keys
    // (contract #1) and would erase them on the next desktop save.
    private val androidPrefs = AndroidPrefs(app)
    private val _feedPrefs = MutableStateFlow(androidPrefs.load())
    val feedPrefs: StateFlow<FeedPrefs> = _feedPrefs

    private val _state = MutableStateFlow(SortUiState())
    val state: StateFlow<SortUiState> = _state

    private var scanJob: Job? = null
    private var lastMoveRequestAt = 0.0
    private var lastKeyShortcutAt = 0.0
    private var moveEnqueuePending = false
    private var statusAfterRescan: StatusMessage? = null

    init {
        viewModelScope.launch {
            engine.events.collect { ev ->
                // Retire pairs a live collector sees so the hub backlog
                // cannot double-record them (TransferHub.acknowledge).
                if (ev is TransferEvent.Finished) {
                    TransferHub.acknowledge(ev.src, ev.newPath)
                }
                onEngineEvent(ev)
            }
        }
        // v1.0.2: queue counters ride TransferHub.progress — a lossless
        // StateFlow fed synchronously by the engine (the old QueueUpdated
        // events shared a finite DROP_OLDEST buffer with the per-item
        // outcomes and could silently drop, stalling the undo seal).
        viewModelScope.launch {
            TransferHub.progress.collect { p ->
                _state.update {
                    it.copy(queued = p.queued, active = p.active, busy = !p.idle)
                }
                if (p.idle) {
                    // Queue drained — seal the open undo batch only when
                    // every expected file reported back (desktop L3737-3745).
                    // reconcile first: a Finished event that ever overflowed
                    // the bounded flow is still in the hub backlog, so the
                    // bookkeeping self-heals instead of locking Undo.
                    reconcileOrphanedTransfers()
                    if (undo.sealIfQueueIdle()) refreshUndoState()
                }
            }
        }
        // v1.0.2: files dropped by the notification Stop action reach the
        // undo bookkeeping IMMEDIATELY while this ViewModel is alive —
        // previously only a fresh ViewModel attach (takeDropped in init)
        // or a UI-side cancel reconciled them, so a Stop pressed on the
        // notification locked the current batch forever.
        viewModelScope.launch {
            TransferHub.droppedVersion.collect {
                val dropped = TransferHub.takeDropped()
                if (dropped > 0) {
                    undo.openBatchIfClosed()
                    undo.adjustDropped(dropped)
                    if (engine.isIdle() && undo.sealIfQueueIdle()) refreshUndoState()
                }
            }
        }
        // Moves finished while the UI was gone (the service kept the
        // process alive) re-enter the undo bookkeeping here; drops
        // counted by the notification Stop action reduce the expected
        // count exactly like a UI-side cancel.
        reconcileOrphanedTransfers()
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
                thumbSize = cfg.thumbSize.coerceIn(THUMB_MIN, THUMB_MAX),
                theme = if (SortThemes.isKnown(cfg.theme)) cfg.theme else "dark",
            )
        }
        saveConfig()
        if (folder.isNotEmpty()) {
            scanFolder()
        } else {
            _state.update { it.copy(status = statusOf(R.string.msg_please_select_folder)) }
        }
    }

    /**
     * Finished (src, dst) pairs the hub buffered while no ViewModel was
     * subscribed, plus drops counted by the notification Stop action.
     * The pairs become one sealed undo round so the moves stay undoable
     * (TransferHub KDoc); drops reduce the expected count like the
     * desktop cancel path (main.py L3753).
     *
     * v1.0.3 race fix: the idle tick of TransferHub.progress can beat the
     * last Finished event of engine.events. The drained pair was already
     * counted by doEnqueue's expect() whenever this session's batch was
     * open — expecting it again made expected permanently exceed done, the
     * batch never sealed and the Undo button locked forever (the v1.0.2
     * regression). Only a still-CLOSED batch (fresh attach, the previous
     * session's leftovers) books new expectations here; an open batch gets
     * the drained pairs recorded WITHOUT touching the counters, because
     * record() dedups against the pairs the live event already booked.
     */
    private fun reconcileOrphanedTransfers() {
        val orphans = TransferHub.drainCompleted()
        val dropped = TransferHub.takeDropped()
        if (orphans.isEmpty() && dropped == 0) return
        val batchWasOpen = undo.hasOpenBatch
        undo.openBatchIfClosed()
        // Pairs the live event collector already recorded (drain-vs-event
        // race) must not re-enter the bookkeeping — expect() counts only
        // the fresh ones or the batch could never seal (v1.0.2 dedup).
        val fresh = orphans.filterNot { undo.alreadyRecorded(it.first, it.second) }
        if (!batchWasOpen) undo.expect(fresh.size)
        for ((src, dst) in fresh) onTransferFinished(src, dst)
        if (dropped > 0) undo.adjustDropped(dropped)
        if (undo.sealIfQueueIdle()) refreshUndoState()
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
                // Desktop parses the filter at scan start (L2876) and drops
                // non-matching files at found-time (L2891) — "Found: {n}"
                // counts only kept files, exactly like len(_scan_buffer).
                val exts = Filters.parseExtensionFilter(_state.value.filterText)
                FolderScanner().scan(File(folder), _state.value.includeSubfolders) { f ->
                    if (exts.isNotEmpty() && Filters.suffixOf(f.name) !in exts) return@scan
                    found++
                    if (found % 100 == 0) {
                        // "Found: {n}" every 100 files (desktop L2893).
                        _state.update { s ->
                            s.copy(foundCount = found, status = statusOf(R.string.status_found_n, "n" to found))
                        }
                    }
                    buffer.add(f.toSortFile())
                }
                // Desktop L2900-2902: Name = natural key; Type = suffix
                // first, natural name inside the group. The sort mode also
                // triggers a full rescan when switched (L2437).
                val mode = if (_state.value.sortByName) Filters.Mode.NAME else Filters.Mode.TYPE
                val files = buffer.sortedWith(Comparator { a, b -> Filters.compare(a.name, b.name, mode) })
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

    // ---------------- view state: filter / sort / thumb size (Phase 4) ----------------

    /**
     * Desktop filter row (main.py L2259-2266) with its 350 ms single-shot
     * timer (L2440-2442): every committed change re-runs load_files —
     * here a full [scanFolder]. Equal text is ignored (the first UI
     * emission after composition carries the restored value).
     */
    fun applyFilter(text: String) {
        if (text == _state.value.filterText) return
        _state.update { it.copy(filterText = text) }
        scanFolder()
    }

    /**
     * Desktop sort combo (main.py L2264, L2437): switching Name/Type runs
     * load_files() — a full rescan, not an in-place re-sort.
     */
    fun setSortByName(name: Boolean) {
        if (name == _state.value.sortByName) return
        _state.update { it.copy(sortByName = name) }
        scanFolder()
    }

    /**
     * Desktop thumb slider (main.py L2271-2277, apply timer L2443-2445,
     * 280 ms): update the tile size and persist thumb_size. No rescan is
     * needed — the grid recomposes and thumbnails regenerate at the
     * requested size (their cache key includes reqPx).
     */
    fun setThumbSize(size: Int) {
        val v = size.coerceIn(THUMB_MIN, THUMB_MAX)
        if (v == cfg.thumbSize) return
        cfg = cfg.copy(thumbSize = v)
        _state.update { it.copy(thumbSize = v) }
        saveConfig()
    }

    /**
     * Desktop theme combo (main.py L2212, L2506): apply + persist. Unknown
     * keys fall back to "dark" exactly like apply_theme (L2452-2453).
     */
    fun setTheme(key: String) {
        val v = if (SortThemes.isKnown(key)) key else "dark"
        if (v == cfg.theme) return
        cfg = cfg.copy(theme = v)
        _state.update { it.copy(theme = v) }
        saveConfig()
    }

    // ---------------- category sets (main.py L2728-2781) ----------------

    fun startSaveCategorySet() {
        _state.update { it.copy(setSaveDialog = true) }
    }

    fun dismissSaveCategorySet() {
        _state.update { it.copy(setSaveDialog = false) }
    }

    /**
     * save_category_set (L2728): writes filesDir/category_sets/<stem>.json
     * with the SAME JSON shape the desktop writes (contract #3); the saved
     * file name is reported in the status like the desktop message.
     */
    fun saveCategorySet(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) {
            _state.update { it.copy(setSaveDialog = false) }
            return
        }
        try {
            val fp = sets.save(clean, _state.value.categories)
            _state.update {
                it.copy(
                    setSaveDialog = false,
                    status = statusOf(R.string.msg_set_saved, "name" to fp.name),
                )
            }
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    setSaveDialog = false,
                    status = statusOf(R.string.err_set_save, "e" to (e.message ?: e.toString())),
                )
            }
        }
    }

    /**
     * load_category_set opening step (L2738-2744): no saved sets -> the
     * "No saved category sets found." message; otherwise the picker opens.
     */
    fun startLoadCategorySet() {
        val files = sets.list()
        if (files.isEmpty()) {
            _state.update { it.copy(status = statusOf(R.string.err_no_sets)) }
            return
        }
        _state.update { it.copy(setLoadDialog = true, setFiles = files) }
    }

    fun dismissLoadCategorySet() {
        _state.update { it.copy(setLoadDialog = false, pendingLoadSet = null) }
    }

    /** A set was chosen — next comes "Replace existing categories or merge?" (L2750-2751). */
    fun chooseCategorySet(fileName: String) {
        _state.update { it.copy(pendingLoadSet = fileName) }
    }

    fun cancelReplaceMerge() {
        _state.update { it.copy(pendingLoadSet = null) }
    }

    /**
     * load_category_set apply step (L2746-2757): normalize decides validity,
     * then Replace (list swap) or Merge (normalize(existing + new)); the
     * result is persisted like the desktop _save_config.
     */
    fun applyCategorySet(replace: Boolean) {
        val st = _state.value
        val fileName = st.pendingLoadSet ?: return
        try {
            val newCats = sets.read(fileName)
            if (newCats.isEmpty()) {
                _state.update {
                    it.copy(
                        pendingLoadSet = null,
                        setLoadDialog = false,
                        status = statusOf(R.string.err_set_no_valid),
                    )
                }
                return
            }
            val merged = if (replace) newCats else normalizeCategories(st.categories + newCats)
            _state.update {
                it.copy(
                    categories = merged,
                    pendingLoadSet = null,
                    setLoadDialog = false,
                    status = statusOf(R.string.msg_set_loaded),
                )
            }
            saveConfig()
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    pendingLoadSet = null,
                    setLoadDialog = false,
                    status = statusOf(R.string.err_set_load, "e" to (e.message ?: e.toString())),
                )
            }
        }
    }

    /** export_category_set_to_file (L2761): SAF CREATE_DOCUMENT target. */
    fun exportCategorySetTo(uri: Uri) {
        try {
            sets.exportTo(uri, _state.value.categories)
            val name = uri.lastPathSegment ?: uri.toString()
            _state.update { it.copy(status = statusOf(R.string.msg_exported_to, "path" to name)) }
        } catch (e: Exception) {
            _state.update {
                it.copy(status = statusOf(R.string.err_set_export, "e" to (e.message ?: e.toString())))
            }
        }
    }

    /**
     * import_category_set_from_file (L2771): always MERGES into the current
     * list (normalize(existing + imported)) — desktop parity.
     */
    fun importCategorySetFrom(uri: Uri) {
        try {
            val newCats = sets.importFrom(uri)
            if (newCats.isEmpty()) {
                _state.update { it.copy(status = statusOf(R.string.err_set_no_valid_file)) }
                return
            }
            val merged = normalizeCategories(_state.value.categories + newCats)
            _state.update {
                it.copy(
                    categories = merged,
                    status = statusOf(R.string.msg_imported_n, "n" to newCats.size),
                )
            }
            saveConfig()
        } catch (e: Exception) {
            _state.update { it.copy(status = statusOf(R.string.err_set_import, "e" to (e.message ?: e.toString()))) }
        }
    }

    // ---------------- thumbnail cache + about (main.py L3885) ----------------

    fun startClearThumbnailCache() {
        _state.update { it.copy(confirmClearThumbs = true) }
    }

    fun dismissClearThumbnailCache() {
        _state.update { it.copy(confirmClearThumbs = false) }
    }

    /**
     * clear_thumbnail_cache apply step (L3895-3913): wipe both cache
     * levels — [ThumbnailEngine.invalidate] for the memory LRU plus the
     * disk level at the engine's documented layout (cacheDir/thumbs,
     * ThumbnailEngine.kt) — report "Thumbnail cache cleared: {n} files,
     * {mb} MB" and reload the list so thumbs regenerate (desktop L3913).
     */
    fun clearThumbnailCache() {
        _state.update { it.copy(confirmClearThumbs = false) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cacheDir = File(getApplication<Application>().cacheDir, "thumbs")
                var deleted = 0
                var total = 0L
                cacheDir.mkdirs()
                for (f in cacheDir.listFiles() ?: emptyArray()) {
                    try {
                        if (f.isFile) {
                            total += f.length()
                            if (f.delete()) deleted++
                        }
                    } catch (_: Exception) {
                    }
                }
                thumbs.invalidate()
                val mb = String.format(java.util.Locale.US, "%.1f", total / (1024.0 * 1024.0))
                withContext(Dispatchers.Main.immediate) {
                    _state.update {
                        it.copy(status = statusOf(R.string.msg_thumb_cleared, "n" to deleted, "mb" to mb))
                    }
                }
                // Desktop reloads the list afterwards (L3913).
                scanFolder()
            } catch (e: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    _state.update {
                        it.copy(
                            status = statusOf(
                                R.string.err_thumb_clear,
                                "e" to (e.message ?: e.toString()),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun showAbout() {
        _state.update { it.copy(showAbout = true) }
    }

    fun dismissAbout() {
        _state.update { it.copy(showAbout = false) }
    }

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

    /**
     * Open the picker. [target] decides what the selected folder means:
     * SOURCE switches the browse folder, CATEGORY fills the path field of
     * the open category dialog (v1.0.2 Browse-button parity).
     */
    fun openFolderPicker(target: PickerTarget = PickerTarget.SOURCE) {
        _state.update { it.copy(showFolderPicker = true, pickerError = null, pickerTarget = target) }
    }

    /** The Browse button inside the category dialog (v1.0.2). */
    fun openCategoryFolderPicker() {
        openFolderPicker(PickerTarget.CATEGORY)
    }

    fun dismissFolderPicker() {
        _state.update { it.copy(showFolderPicker = false, pickerError = null) }
    }

    /**
     * Start dir for the picker: candidate folder, else last browse dir
     * (desktop L3925). v1.0.3: the CATEGORY dialog Browse starts from the
     * dialog's own path (categoryDialogPath) — opening from the browse
     * folder forced the user to re-navigate to the category's folder on
     * every edit.
     */
    fun pickerStartPath(): String {
        val st = _state.value
        if (st.pickerTarget == PickerTarget.CATEGORY) {
            st.categoryDialogPath?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return st.candidateFolder.ifEmpty {
            st.lastBrowseDir.ifEmpty { DEFAULT_PICKER_PATH }
        }
    }

    fun applyFolderSelection(path: String, recursive: Boolean) {
        val st = _state.value
        val trimmed = path.trim()
        if (st.pickerTarget == PickerTarget.CATEGORY) {
            // Destination pick for the category dialog: hand the path back
            // to the dialog (it is still open underneath) instead of
            // switching the browse folder (v1.0.2).
            if (trimmed.isEmpty()) {
                _state.update { it.copy(pickerError = statusOf(R.string.err_path_not_folder)) }
                return
            }
            val f = File(trimmed)
            if (!f.isDirectory) {
                _state.update {
                    it.copy(
                        pickerError = if (f.exists()) statusOf(R.string.err_path_not_folder) else statusOf(R.string.err_invalid_folder),
                    )
                }
                return
            }
            _state.update {
                it.copy(
                    categoryDialogPath = f.path,
                    // v1.0.3: pre-fill an empty name with the picked folder
                    // name — one less field to type on a phone (the dialog
                    // name stays untouched when the user already typed one).
                    categoryDialogName = st.categoryDialogName
                        ?.takeIf { it.isNotBlank() }
                        ?: f.name,
                    showFolderPicker = false,
                    pickerError = null,
                )
            }
            return
        }
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
                it.copy(
                    pickerError = if (f.exists()) statusOf(R.string.err_path_not_folder) else statusOf(R.string.err_invalid_folder),
                )
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
        _state.update {
            it.copy(
                categoryDialogFor = ADD_CATEGORY,
                categoryDialogError = null,
                categoryDialogName = "",
                categoryDialogPath = "",
            )
        }
    }

    fun startEditCategory(idx: Int) {
        _state.update {
            if (idx in it.categories.indices) {
                it.copy(
                    categoryDialogFor = idx,
                    categoryDialogError = null,
                    categoryDialogName = it.categories[idx].name,
                    categoryDialogPath = it.categories[idx].path,
                )
            } else {
                it
            }
        }
    }

    fun dismissCategoryDialog() {
        _state.update {
            it.copy(
                categoryDialogFor = null,
                categoryDialogError = null,
                categoryDialogName = null,
                categoryDialogPath = null,
            )
        }
    }

    /** Live field edits (v1.0.2): the dialog fields bind to the state. */
    fun updateCategoryDialogName(name: String) {
        _state.update { it.copy(categoryDialogName = name) }
    }

    fun updateCategoryDialogPath(path: String) {
        _state.update { it.copy(categoryDialogPath = path) }
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
                categoryDialogName = null,
                categoryDialogPath = null,
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

    // ---------------- preview (Phase 3, desktop show_preview L3446) ----------------

    /**
     * Open the full-screen preview dialog. Video/audio auto-play immediately
     * like the desktop preview panel (show_preview L3449-3464).
     *
     * v1.0.2: one tap on a grid tile opens the preview (the old flow needed
     * a double-tap, which made Compose delay EVERY single tap ~300 ms and
     * felt like "files need several clicks"). The previewed file IS the
     * active selection — desktop parity, where a list click previews AND
     * selects — so category taps / digit shortcuts move exactly this file.
     * The "playing" status is only set for media that actually plays; the
     * old code stamped "Playing (embedded player)" on every PDF and image.
     */
    fun openPreview(path: String) {
        val f = File(path)
        if (!f.isFile) return
        val ext = Paths.splitName(f.name).second.drop(1).lowercase()
        val group = groupForExtension(ext)
        _state.update {
            it.copy(
                previewPath = path,
                selection = setOf(path),
                status = when (group) {
                    MediaGroup.AUDIO -> statusOf(R.string.msg_playing_audio_embedded)
                    MediaGroup.VIDEO -> statusOf(R.string.msg_playing_embedded)
                    else -> it.status
                },
            )
        }
    }

    /**
     * Close the preview. The dialog leaving composition releases the
     * Media3 player and its file handle (desktop _reset_preview L3146).
     */
    fun closePreview() {
        _state.update { it.copy(previewPath = null) }
    }

    /** Desktop L3401: the user answered No to the "Run this file?" guard. */
    fun notifyOpeningCancelled() {
        _state.update { it.copy(status = statusOf(R.string.msg_opening_cancelled)) }
    }

    /** Desktop L3422 (open_selected_with_system_player success path). */
    fun notifyOpenedWithSystemPlayer() {
        _state.update { it.copy(status = statusOf(R.string.msg_opened_with_system)) }
    }

    /** Desktop L3984 (open_file catch) and L3427: "Cannot open: {e}". */
    fun notifyCannotOpen(err: String) {
        _state.update { it.copy(status = statusOf(R.string.err_cannot_open, "e" to err)) }
    }

    // ---------------- selection ----------------

    /** Only used in selection mode (multi-select toggling); a plain tap opens the preview. */
    fun onTileTap(path: String) {
        _state.update {
            val sel = if (path in it.selection) it.selection - path else it.selection + path
            it.copy(selection = sel)
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
        // Desktop L3615-3635: snapshot the selection FIRST — it may change
        // during the short release delay — close the preview so the media
        // player drops the file handle, then wait 200 ms before enqueueing
        // (player stop/release is asynchronous; a locked file would fail the
        // move). The 200 ms QTimer.singleShot parity lives in the coroutine.
        val srcList = st.selection.toList()
        if (st.previewPath != null) closePreview()
        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            doEnqueue(srcList, dst)
            moveEnqueuePending = false
        }
    }

    private fun doEnqueue(srcList: List<String>, dst: String) {
        // Start (or re-attach) the transfer foreground service BEFORE the
        // queue fills: it must be started while the app is foregrounded
        // (Android background-start restriction) and it shows the live
        // progress notification with the Stop action while the queue runs.
        // A quick backgrounding between the tap and this enqueue throws
        // ForegroundServiceStartNotAllowedException — catch it: the queue
        // still runs in-process, only the progress notification is lost
        // (v1.0.1 hardening).
        if (srcList.isNotEmpty()) {
            try {
                TransferService.start(getApplication())
            } catch (_: Exception) {
            }
        }
        // v1.0.3: open the undo batch BEFORE the first enqueue. A very fast
        // worker could otherwise report Finished while the batch was still
        // closed — record() would drop the pair (no batch open) and the
        // expect(queued) afterwards would then over-count: done could never
        // reach expected and Undo locked. Opening first makes a hyper-fast
        // finish book itself through record(), and expect(queued) after the
        // loop lands on the exact number actually enqueued.
        undo.openBatchIfClosed()
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
            undo.expect(queued)
            var msg = statusOf(R.string.msg_queued_n, "n" to queued)
            if (skipped > 0) msg = msg + statusOf(R.string.msg_skipped_n, "n" to skipped)
            _state.update { it.copy(status = msg) }
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
        }
    }

    private fun onTransferFinished(src: String, newPath: String) {
        // record() returns false for a duplicate pair (backlog drain vs
        // live event, v1.0.2) or when no batch is open at all.
        if (!undo.record(src, newPath)) {
            if (!undo.hasOpenBatch) {
                // v1.0.3: a Finished for a move THIS session never expected —
                // an in-flight round left over from the previous ViewModel
                // attach — must not be dropped silently. Book it as its own
                // single-file round so the move stays undoable.
                undo.openBatchIfClosed()
                undo.expect(1)
                if (!undo.record(src, newPath)) return
            } else {
                // Duplicate (already booked) — skip the counters so a file
                // is accounted exactly once.
                return
            }
        }
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
        // v1.0.2: tell MediaStore/launcher gallery about BOTH folders so the
        // moved file shows up in the destination and disappears from the
        // source immediately (the engine moves raw files, so the media index
        // otherwise holds stale entries for a while).
        scanMedia(listOf(src, newPath))
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

    /** Best-effort MediaStore refresh (v1.0.2); never fatal. */
    private fun scanMedia(paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(getApplication(), paths.toTypedArray(), null, null)
        } catch (_: Exception) {
        }
    }

    fun cancelTransfers() {
        val dropped = engine.cancelAll() + TransferHub.takeDropped()
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
        // v1.0.2: refresh the media index for every touched folder (files
        // reappeared in the source, vanished from the categories).
        scanMedia((restored + failed + gone).flatMap { listOf(it.first, it.second) })
        refreshUndoState()
        // Rescan so the restored files reappear; the summary is shown when
        // the rescan finishes (desktop _status_after_rescan).
        scanFolder()
    }

    // ---------------- feed mode (v1.1.0) ----------------

    /** Swipe-left send target (null = off). Persisted in android_prefs.json. */
    fun setSwipeLeftCategory(path: String?) {
        saveFeedPrefs(_feedPrefs.value.copy(swipeLeftPath = path))
    }

    /** Swipe-right send target (null = off). Persisted in android_prefs.json. */
    fun setSwipeRightCategory(path: String?) {
        saveFeedPrefs(_feedPrefs.value.copy(swipeRightPath = path))
    }

    /**
     * Pin/unpin a category on the feed quick dock (max 6 — the oldest pin
     * falls off, tap order is kept). Persisted in android_prefs.json.
     */
    fun toggleQuickCategory(path: String) {
        val current = _feedPrefs.value.quickCategories.toMutableList()
        if (!current.remove(path)) {
            if (current.size >= FEED_QUICK_MAX) current.removeAt(0)
            current.add(path)
        }
        saveFeedPrefs(_feedPrefs.value.copy(quickCategories = current))
    }

    private fun saveFeedPrefs(p: FeedPrefs) {
        _feedPrefs.value = p
        viewModelScope.launch(Dispatchers.IO) { androidPrefs.save(p) }
    }

    /**
     * Feed-mode single-file send (v1.1.0). The FeedScreen releases the
     * shared player BEFORE calling this — the 200 ms settle matches
     * [moveSelectedToCategory] (player stop/release is asynchronous; a
     * locked file would fail the move) — then the standard [doEnqueue]
     * runs: FGS start, same-folder skip, and the undo batch + expect with
     * the v1.0.3 ordering (batch opens before the first enqueue).
     */
    fun sendPathToCategory(path: String, categoryPath: String) {
        val f = File(path)
        if (!f.isFile) return
        val dst = categoryPath.trim()
        if (dst.isEmpty()) return
        _state.update { it.copy(status = statusOf(R.string.msg_preparing_transfer)) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            doEnqueue(listOf(path), dst)
        }
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

        /** Feed quick dock capacity (v1.1.0). */
        const val FEED_QUICK_MAX = 6

        private const val MOVE_DEBOUNCE_S = 0.30
        private const val KEY_DEBOUNCE_S = 0.25

        /** Desktop slider bounds (main.py L2272-2273). */
        const val THUMB_MIN = 72
        const val THUMB_MAX = 420
    }
}

enum class CategoryMenuAction { EDIT, REMOVE, MOVE_UP, MOVE_DOWN }
