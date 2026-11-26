package com.mohammadjafari.sortview.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.mohammadjafari.sortview.R
import com.mohammadjafari.sortview.data.Category
import com.mohammadjafari.sortview.media.MediaGroup
import java.io.File
import kotlin.math.roundToInt

/**
 * Main sorting screen — the three desktop panels folded into one mobile
 * layout: folder header + status bar on top, the file grid in the middle,
 * and the category panel (with the 0-9 shortcuts) at the bottom.
 * Hardware keyboards keep the desktop digit/Ctrl+Z behavior.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SortScreen(vm: SortViewModel) {
    val st by vm.state.collectAsState()
    val context = LocalContext.current

    // Requested thumbnail resolution in real pixels: the desktop generates
    // thumbs at its thumb_size setting (default 168) and scales them into
    // the tile; the tile height in pixels is the same intent on mobile.
    val density = LocalDensity.current
    val thumbPx = remember(density) { with(density) { 132.dp.toPx().roundToInt() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (e.isCtrlPressed && e.key == Key.Z) {
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
                TextButton(onClick = { vm.selectAll() }) { Text(stringResource(R.string.sel_select_all)) }
                TextButton(onClick = { vm.clearSelection() }) { Text(stringResource(R.string.sel_clear)) }
                IconButton(onClick = { vm.exitSelectionMode() }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_cancel))
                }
            }
        }

        // --- file grid ---
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            modifier = Modifier.fillMaxWidth().weight(1f),
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
                    onClick = { vm.onTileTap(file.path) },
                    onLongClick = { vm.onTileLongPress(file.path) },
                )
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
                        onClick = { vm.undoLastMove() },
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    st.categories.forEachIndexed { idx, cat ->
                        CategoryChip(
                            idx = idx,
                            cat = cat,
                            menuOpen = st.categoryMenuFor == idx,
                            onDismissMenu = { vm.dismissCategoryMenu() },
                            onAction = { action -> vm.onCategoryMenuAction(action, idx) },
                            onClick = { vm.onCategoryTap(idx) },
                            onLongClick = { vm.openCategoryMenu(idx) },
                        )
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
 * pix.scaled(size, size, KEEP_ASPECT, SMOOTH).
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
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) {
                    Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier
                }
            )
            .height(132.dp),
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
            val bmpState = produceState<Bitmap?>(null, file.path, thumbGeneration) {
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
private fun TypeCard(file: SortFile, modifier: Modifier = Modifier) {
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

@Composable
private fun CategoryDialog(vm: SortViewModel, st: SortUiState) {
    val idx = st.categoryDialogFor ?: return
    val editing = idx >= 0
    val context = LocalContext.current
    var name by remember(idx) { mutableStateOf(if (editing) st.categories[idx].name else "") }
    var path by remember(idx) { mutableStateOf(if (editing) st.categories[idx].path else "") }
    AlertDialog(
        onDismissRequest = { vm.dismissCategoryDialog() },
        title = { Text(stringResource(if (editing) R.string.dlg_edit_category else R.string.dlg_add_category)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.field_path)) },
                    singleLine = true,
                )
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
