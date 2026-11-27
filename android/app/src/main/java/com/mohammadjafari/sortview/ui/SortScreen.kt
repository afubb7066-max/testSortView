package com.mohammadjafari.sortview.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mohammadjafari.sortview.R
import com.mohammadjafari.sortview.SortViewApp
import com.mohammadjafari.sortview.data.Category
import com.mohammadjafari.sortview.fs.Paths
import com.mohammadjafari.sortview.media.MediaGroup
import com.mohammadjafari.sortview.media.groupForExtension
import com.mohammadjafari.sortview.ui.theme.SortThemes
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/**
 * Main sorting screen — the three desktop panels folded into one mobile
 * layout: folder header + status bar on top, the file grid in the middle,
 * and the category panel (with the 0-9 shortcuts) at the bottom.
 * Hardware keyboards keep the desktop digit/Ctrl+Z behavior.
 *
 * Phase 4 desktop header rows (main.py L2259-2283) are the two rows under
 * the folder header: the filter field + Name/Type toggle, and the thumb
 * slider + theme circle + tools menu (sets / cache / about). The theme
 * circle redraws theme_color_icon (L395-433) as a two-colour circle; the
 * desktop combo boxes map to dropdown menus and dialogs.
 *
 * v1.0.2: safeDrawing padding for Android 15 (enforced edge-to-edge on
 * targetSdk 35), hardware back leaves selection mode first, the keyboard
 * shortcuts step aside when the filter field has focus (desktop focus
 * check), one tap opens the preview (the old combinedClickable
 * onDoubleClick delayed every single tap ~300 ms — "files need several
 * clicks"), the category chips scroll when the panel overflows, and the
 * category dialog gained the desktop Browse button backed by the picker
 * (PickerTarget.CATEGORY).
 */
@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun SortScreen(vm: SortViewModel) {
    val st by vm.state.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current

    // Hardware back: leave selection mode first; only when nothing is
    // active does back leave the app. The preview dialog intercepts back
    // itself via onDismissRequest (v1.0.2 UX follow-up).
    BackHandler(enabled = st.selectionMode) { vm.exitSelectionMode() }

    // Applied tile size: the desktop applies the slider value after its
    // 280 ms timer (L2443), so the grid and the thumbnail decode size
    // follow the persisted thumb_size, not the in-drag slider position.
    val thumbDp = st.thumbSize.dp

    // Requested thumbnail resolution in real pixels: the desktop generates
    // thumbs at its thumb_size setting (default 168) and scales them into
    // the tile; the tile height in pixels is the same intent on mobile.
    val density = LocalDensity.current
    val thumbPx = remember(st.thumbSize, density) {
        with(density) { thumbDp.toPx().roundToInt() }
    }

    // v1.0.2: while the filter text field holds focus, the root shortcut
    // handler must NOT steal digits/keys — the desktop checks the focus
    // widget before dispatching (main.py L4022 equivalents).
    var filterFieldFocused by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Android 15 edge-to-edge: keep the header clear of the status
            // and navigation bars (a no-op on older devices).
            .safeDrawingPadding()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (filterFieldFocused) return@onPreviewKeyEvent false
                if (e.isCtrlPressed && e.key == Key.Z) {
                    confirmHaptic(view)
                    vm.undoLastMove()
                    return@onPreviewKeyEvent true
                }
                val digit = when (e.key) {
                    Key.Zero -> 0
                    Key.One -> 1
                    Key.Two -> 2
                    Key.Three -> 3
                    Key.Four -> 4
                    Key.Five -> 5
                    Key.Six -> 6
                    Key.Seven -> 7
                    Key.Eight -> 8
                    Key.Nine -> 9
                    else -> -1
                }
                if (digit >= 0) {
                    confirmHaptic(view)
                    vm.onDigit(digit)
                    true
                } else {
                    false
                }
            },
    ) {
        // --- header: folder label + refresh + change folder ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folderLabel(st),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = statsSummary(st, context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            IconButton(onClick = { vm.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.btn_refresh))
            }
            TextButton(onClick = { vm.openFolderPicker() }) {
                Text(stringResource(R.string.btn_change_folder))
            }
        }

        if (st.scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }

        // --- desktop header row2: filter field + Name/Type toggle ---
        // rememberSaveable: the typed filter survives rotation and process
        // death (the desktop field keeps its text across redraws too).
        var filterUi by rememberSaveable { mutableStateOf(st.filterText) }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filterUi,
                onValueChange = { filterUi = it },
                modifier = Modifier.weight(1f).onFocusChanged { filterFieldFocused = it.hasFocus },
                placeholder = {
                    Text(
                        text = stringResource(R.string.filter_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
            )
            // Desktop sort combo (main.py L2264): switching Name/Type runs
            // a full rescan (L2437) — vm.setSortByName owns that.
            FilterChip(
                selected = st.sortByName,
                onClick = { vm.setSortByName(true) },
                label = { Text(stringResource(R.string.sort_name)) },
            )
            FilterChip(
                selected = !st.sortByName,
                onClick = { vm.setSortByName(false) },
                label = { Text(stringResource(R.string.sort_type)) },
            )
        }
        // The desktop filter applies on a 350 ms single-shot timer
        // (main.py L2440-2442): only the value typed last is rescanned.
        // LaunchedEffect restarts on every keystroke, so the delay acts
        // as exactly that debounce; applyFilter ignores equal text, which
        // makes the first composition a no-op.
        LaunchedEffect(filterUi) {
            delay(350)
            vm.applyFilter(filterUi)
        }

        // --- desktop header row3: thumb slider + theme circle + tools ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.lbl_thumb),
                style = MaterialTheme.typography.labelMedium,
            )
            var thumbUi by remember(st.thumbSize) { mutableFloatStateOf(st.thumbSize.toFloat()) }
            Slider(
                value = thumbUi,
                onValueChange = { thumbUi = it },
                onValueChangeFinished = { vm.setThumbSize(thumbUi.roundToInt()) },
                valueRange = SortViewModel.THUMB_MIN.toFloat()..SortViewModel.THUMB_MAX.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
            ThemeCircleButton(vm, st.theme)
            ToolsMenu(vm)
        }

        // --- status line ---
        Text(
            text = context.resolveStatus(st.status),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // --- selection bar ---
        if (st.selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.resolveStatus(statusOf(R.string.sel_count, "n" to st.selection.size)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                // Exactly one file picked: its preview is one tap away
                // (mobile stand-in for the always-visible desktop preview
                // panel; a plain tile tap opens it as well).
                if (st.selection.size == 1) {
                    TextButton(onClick = { vm.openPreview(st.selection.first()) }) {
                        Text(stringResource(R.string.preview_title))
                    }
                }
                TextButton(onClick = { vm.selectAll() }) { Text(stringResource(R.string.sel_select_all)) }
                TextButton(onClick = { vm.clearSelection() }) { Text(stringResource(R.string.sel_clear)) }
                IconButton(onClick = { vm.exitSelectionMode() }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        }

        // --- file grid; pull-to-refresh is the mobile stand-in for the
        // desktop Refresh button: the drag triggers a full rescan and
        // the indicator spins until st.scanning clears ---
        PullToRefreshBox(
            isRefreshing = st.scanning,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = thumbDp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
            ) {
                items(st.files, key = { it.path }) { file ->
                    FileTile(
                        file = file,
                        selected = file.path in st.selection,
                        moving = file.path in st.movingPaths,
                        failed = file.path in st.errorPaths,
                        thumbLoader = vm::loadThumbnail,
                        thumbPx = thumbPx,
                        thumbGeneration = st.thumbGeneration,
                        tileHeight = thumbDp,
                        // v1.0.2: ONE tap opens the preview (and selects the
                        // file, desktop list-click parity) — the removed
                        // double-tap handler used to delay every single tap
                        // ~300 ms. Long-press still starts multi-select.
                        onClick = {
                            if (st.selectionMode) vm.onTileTap(file.path) else vm.openPreview(file.path)
                        },
                        onLongClick = { vm.onTileLongPress(file.path) },
                    )
                }
            }
        }

        // --- category panel ---
        Surface(tonalElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.panel_categories),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            confirmHaptic(view)
                            vm.undoLastMove()
                        },
                        enabled = st.canUndo,
                    ) { Text(stringResource(R.string.btn_undo_move)) }
                    if (st.busy) {
                        // Desktop shows "Cancel transfers" while the queue is
                        // not empty (btn_cancel_all visibility, main.py L3736).
                        TextButton(onClick = { vm.cancelTransfers() }) {
                            Text(stringResource(R.string.btn_cancel_transfers))
                        }
                    }
                    TextButton(onClick = { vm.startAddCategory() }) {
                        Text(stringResource(R.string.btn_add_category))
                    }
                }
                // v1.0.2: many categories pushed the panel over the screen
                // edge — cap it and scroll instead (desktop panel parity).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        st.categories.forEachIndexed { idx, cat ->
                            CategoryChip(
                                idx = idx,
                                cat = cat,
                                menuOpen = st.categoryMenuFor == idx,
                                onDismissMenu = { vm.dismissCategoryMenu() },
                                onAction = { action -> vm.onCategoryMenuAction(action, idx) },
                                onClick = {
                                    confirmHaptic(view)
                                    vm.onCategoryTap(idx)
                                },
                                onLongClick = { vm.openCategoryMenu(idx) },
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.hint_categories_tap),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (st.categoryDialogFor != null) {
        CategoryDialog(vm, st)
    }

    // --- Phase 4 tool dialogs (sets / cache / about) ---
    if (st.setSaveDialog) {
        SaveSetDialog(vm)
    }
    if (st.setLoadDialog) {
        LoadSetDialog(vm, st)
    }
    if (st.pendingLoadSet != null) {
        ReplaceMergeDialog(vm)
    }
    if (st.confirmClearThumbs) {
        ConfirmClearThumbsDialog(vm)
    }
    if (st.showAbout) {
        AboutDialog(vm)
    }

    // --- Phase 3: full-screen preview dialog (desktop preview panel parity) ---
    st.previewPath?.let { p ->
        val pf = st.files.firstOrNull { it.path == p } ?: run {
            // The file just left the list (e.g. a transfer finished); build
            // the row on the spot so the dialog can still render it.
            val f = File(p)
            SortFile(
                path = f.absolutePath,
                name = f.name,
                group = groupForExtension(Paths.splitName(f.name).second.drop(1)),
            )
        }
        PreviewDialog(vm, pf, st.thumbGeneration)
    }
}

/**
 * Short confirm pulse for file-moving actions (digit dispatch, category
 * tap, undo) — the mobile stand-in for the desktop status-line
 * feedback. Long-press selection already vibrates through
 * combinedClickable.
 */
private fun confirmHaptic(view: android.view.View) {
    view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        },
    )
}

/** Desktop combo labels for the six THEMES keys (app_i18n.py strings). */
private val THEME_LABEL_RES = mapOf(
    "light" to R.string.theme_light,
    "paper" to R.string.theme_paper,
    "graphite" to R.string.theme_graphite,
    "dark" to R.string.theme_dark,
    "forest" to R.string.theme_forest,
    "amoled" to R.string.theme_amoled,
)

/**
 * The theme picker: a two-colour circle redrawn from theme_color_icon
 * (desktop main.py L395-433 — the theme's background with its accent
 * colour inside), opening a dropdown with the six desktop themes.
 */
@Composable
private fun ThemeCircleButton(vm: SortViewModel, current: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            val (bg, accent) = SortThemes.previewColors(current)
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color(bg))
                    .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Color(accent)),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortThemes.KEYS.forEach { key ->
                DropdownMenuItem(
                    text = { Text(stringResource(THEME_LABEL_RES.getValue(key))) },
                    leadingIcon = {
                        val (bg, accent) = SortThemes.previewColors(key)
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(bg))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(accent)),
                            )
                        }
                    },
                    onClick = {
                        vm.setTheme(key)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * The desktop tools menu (sets save/load/export/import, thumbnail cache,
 * about). Export/import ride SAF (CreateDocument / OpenDocument) —
 * vm.exportCategorySetTo / importCategorySetFrom own the rest.
 */
@Composable
private fun ToolsMenu(vm: SortViewModel) {
    var open by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportCategorySetTo(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importCategorySetFrom(uri)
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.tools_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.set_save)) },
                onClick = { open = false; vm.startSaveCategorySet() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.set_load)) },
                onClick = { open = false; vm.startLoadCategorySet() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.set_export)) },
                onClick = {
                    open = false
                    // Desktop suggests sortview_categories.json (L2761).
                    exportLauncher.launch("sortview_categories.json")
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.set_import)) },
                onClick = {
                    open = false
                    // Desktop accepts any path and validates the content.
                    importLauncher.launch(arrayOf("*/*"))
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.thumbs_clear)) },
                onClick = { open = false; vm.startClearThumbnailCache() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.about_title)) },
                onClick = { open = false; vm.showAbout() },
            )
        }
    }
}

/** save_category_set dialog (desktop L2728): a plain name field. */
@Composable
private fun SaveSetDialog(vm: SortViewModel) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.dismissSaveCategorySet() },
        title = { Text(stringResource(R.string.set_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.set_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.saveCategorySet(name) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissSaveCategorySet() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** load_category_set step 1 (desktop L2738-2744): pick a saved set file. */
@Composable
private fun LoadSetDialog(vm: SortViewModel, st: SortUiState) {
    AlertDialog(
        onDismissRequest = { vm.dismissLoadCategorySet() },
        title = { Text(stringResource(R.string.set_load)) },
        text = {
            Column {
                Text(stringResource(R.string.set_choose), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                st.setFiles.forEach { fileName ->
                    Text(
                        text = fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.chooseCategorySet(fileName) }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { vm.dismissLoadCategorySet() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** load_category_set step 2 (desktop L2750-2751): replace or merge. */
@Composable
private fun ReplaceMergeDialog(vm: SortViewModel) {
    AlertDialog(
        onDismissRequest = { vm.cancelReplaceMerge() },
        title = { Text(stringResource(R.string.set_load)) },
        text = { Text(stringResource(R.string.set_replace_or_merge)) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { vm.applyCategorySet(false) }) {
                    Text(stringResource(R.string.set_merge))
                }
                TextButton(onClick = { vm.applyCategorySet(true) }) {
                    Text(stringResource(R.string.set_replace))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelReplaceMerge() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** clear_thumbnail_cache confirm (desktop L3885-3913). */
@Composable
private fun ConfirmClearThumbsDialog(vm: SortViewModel) {
    AlertDialog(
        onDismissRequest = { vm.dismissClearThumbnailCache() },
        title = { Text(stringResource(R.string.thumbs_clear)) },
        text = { Text(stringResource(R.string.thumbs_confirm_body)) },
        confirmButton = {
            TextButton(onClick = { vm.clearThumbnailCache() }) {
                Text(stringResource(R.string.thumbs_confirm_clear))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissClearThumbnailCache() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * About (contract #7): version line through the {x} resolver — the
 * desktop-style "{v}" placeholder cannot ride stringResource's %1$s
 * formatter — plus the attribution body. v1.0.1: when the in-app crash
 * logger (SortViewApp) has recorded a crash, the latest report is shown
 * here with Copy/Clear buttons, so a device crash can be reported to the
 * developer straight from the phone without adb.
 */
@Composable
private fun AboutDialog(vm: SortViewModel) {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            null
        }
    }
    var crash by remember { mutableStateOf(SortViewApp.lastCrashReport(context)) }
    AlertDialog(
        onDismissRequest = { vm.dismissAbout() },
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            Column {
                Text(
                    text = context.resolveStatus(statusOf(R.string.about_version, "v" to (version ?: "?"))),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium)
                if (crash.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.crash_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = crash,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            try {
                                val cm = context.getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("SortView crash report", crash))
                            } catch (_: Exception) {
                            }
                        }) { Text(stringResource(R.string.crash_copy)) }
                        TextButton(onClick = {
                            SortViewApp.clearCrashLog(context)
                            crash = ""
                        }) { Text(stringResource(R.string.crash_clear)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.dismissAbout() }) {
                Text(stringResource(R.string.action_ok))
            }
        },
    )
}

@Composable
private fun folderLabel(st: SortUiState): String =
    // "No folder selected — click 'Change folder'" when empty; the
    // "  • recursive" suffix mirrors the desktop folder label (L2626-2629).
    if (st.candidateFolder.isEmpty()) {
        stringResource(R.string.no_folder_selected)
    } else {
        st.candidateFolder + if (st.includeSubfolders) stringResource(R.string.recursive_suffix) else ""
    }

@Composable
private fun statsSummary(st: SortUiState, context: android.content.Context): String {
    // Desktop status-bar counters (main.py L3695-3735): Total / Queued /
    // Active / Done / Failed / Busy — "Found: {n}" while scanning.
    val total = if (st.scanning) {
        context.resolveStatus(statusOf(R.string.status_found_n, "n" to st.foundCount))
    } else {
        context.resolveStatus(statusOf(R.string.status_total_n, "n" to st.files.size))
    }
    val rest = listOf(
        context.resolveStatus(statusOf(R.string.status_queued_n, "n" to st.queued)),
        context.resolveStatus(statusOf(R.string.status_active_n, "n" to st.active)),
        context.resolveStatus(statusOf(R.string.status_done_n, "n" to st.done)),
        context.resolveStatus(statusOf(R.string.status_failed_n, "n" to st.failed)),
        context.resolveStatus(statusOf(if (st.busy) R.string.status_busy_yes else R.string.status_busy_no)),
    )
    return total + "   " + rest.joinToString("   ")
}

/** Tile background tints — the exact desktop colors (#fff5cc moving, #ffdddd failed). */
private val MovingBg = Color(0xFFFFF5CC)
private val FailedBg = Color(0xFFFFDDDD)

/**
 * One grid tile. Rendering policy (desktop L2815-2838 / L2964):
 *  - image group: Coil loads the real image; while decoding or on failure
 *    the gradient type card shows first, exactly like the desktop inserts
 *    an immediate type-card icon and swaps in the real thumbnail later;
 *  - video / pdf / archive: the thumbnail engine resolves the real preview
 *    (visible tiles ask first — off-screen tiles are not composed);
 *  - everything else: the gradient type card is the final render.
 * Real previews letterbox with ContentScale.Fit = desktop
 * pix.scaled(size, size, KEEP_ASPECT, SMOOTH). The tile follows the
 * Phase-4 thumb_size setting (desktop thumb slider, L2271-2277).
 * v1.0.2: no onDoubleClick — one tap opens the preview (it also selects
 * the file, desktop list-click parity); the combinedClickable double-tap
 * used to delay EVERY single tap ~300 ms.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    file: SortFile,
    selected: Boolean,
    moving: Boolean,
    failed: Boolean,
    thumbLoader: suspend (SortFile, Int) -> Bitmap?,
    thumbPx: Int,
    thumbGeneration: Int,
    tileHeight: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        moving -> MovingBg
        failed -> FailedBg
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .padding(4.dp)
            .clip(shape)
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .height(tileHeight),
    ) {
        if (file.group == MediaGroup.IMAGE) {
            SubcomposeAsyncImage(
                model = File(file.path),
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = { TypeCard(file, Modifier.fillMaxSize()) },
                error = { TypeCard(file, Modifier.fillMaxSize()) },
            )
        } else {
            // Keyed on thumbPx too: a new slider size re-decodes the tile
            // at the requested resolution (the engine cache key includes
            // reqPx, like the desktop).
            val bmpState = produceState<Bitmap?>(null, file.path, thumbGeneration, thumbPx) {
                value = thumbLoader(file, thumbPx)
            }
            val bmp = bmpState.value
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                TypeCard(file, Modifier.fillMaxSize())
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp),
            )
        }
    }
}

/**
 * Gradient fallback card — the Compose redraw of generate_type_card_bytes
 * (desktop main.py L921-976): vertical gradient in the group colors, soft
 * white inner overlay, white border, the extension in big type and the
 * group label below it. The desktop blends pre-drawn icon assets on top of
 * this design; those PNG assets are not shipped with the Android port, so
 * the card always uses the documented gradient fallback. Like the desktop
 * GROUP_STYLE labels (L343-353), the card label is not translated, and a
 * file without an extension shows the group label as its big text (L962).
 */
@Composable
internal fun TypeCard(file: SortFile, modifier: Modifier = Modifier) {
    val group = file.group
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    0f to Color(group.topColor),
                    1f to Color(group.bottomColor),
                ),
            )
            .border(2.dp, Color.White.copy(alpha = 68f / 255f), RoundedCornerShape(10.dp)),
    ) {
        // Soft inner overlay (desktop rounded_rectangle fill white 18/255).
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.White.copy(alpha = 18f / 255f)),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = file.ext.ifEmpty { group.cardLabel }.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = group.cardLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryChip(
    idx: Int,
    cat: Category,
    menuOpen: Boolean,
    onDismissMenu: () -> Unit,
    onAction: (CategoryMenuAction) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 1.dp,
        ) {
            Text(
                // U+2066/U+2069 isolate the label so the RTL layout of the
                // Persian UI cannot flip "0: Photos" into "Photos :0"
                // (desktop L2790-2796); only the first ten carry a badge.
                text = if (idx < 10) "\u2066$idx: ${cat.name}\u2069" else "\u2066${cat.name}\u2069",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_edit)) },
                onClick = { onAction(CategoryMenuAction.EDIT) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_remove)) },
                onClick = { onAction(CategoryMenuAction.REMOVE) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move_up)) },
                onClick = { onAction(CategoryMenuAction.MOVE_UP) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_move_down)) },
                onClick = { onAction(CategoryMenuAction.MOVE_DOWN) },
            )
        }
    }
}

/**
 * Add/edit category dialog (desktop L2632). v1.0.2: the field state lives
 * in the ViewModel (st.categoryDialogName / categoryDialogPath) so typing
 * survives the folder-picker detour and rotation, and a Browse button
 * opens the picker in CATEGORY mode — the chosen path is written back
 * into the open dialog (desktop Browse-button parity; nobody types
 * /storage/emulated/0/... by hand on a phone).
 */
@Composable
private fun CategoryDialog(vm: SortViewModel, st: SortUiState) {
    val idx = st.categoryDialogFor ?: return
    val editing = idx >= 0
    val context = LocalContext.current
    val name = st.categoryDialogName
        ?: if (editing) st.categories.getOrNull(idx)?.name ?: "" else ""
    val path = st.categoryDialogPath
        ?: if (editing) st.categories.getOrNull(idx)?.path ?: "" else ""
    AlertDialog(
        onDismissRequest = { vm.dismissCategoryDialog() },
        title = { Text(stringResource(if (editing) R.string.dlg_edit_category else R.string.dlg_add_category)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { vm.updateCategoryDialogName(it) },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { vm.updateCategoryDialogPath(it) },
                        label = { Text(stringResource(R.string.field_path)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.openCategoryFolderPicker() }) {
                        Text(stringResource(R.string.btn_browse))
                    }
                }
                st.categoryDialogError?.let { err ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = context.resolveStatus(err),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.submitCategoryDialog(name, path) }) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissCategoryDialog() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
