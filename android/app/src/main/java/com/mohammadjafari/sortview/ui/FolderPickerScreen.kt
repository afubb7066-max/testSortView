package com.mohammadjafari.sortview.ui

import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mohammadjafari.sortview.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Folder picker — the Android counterpart of the desktop "Select folder"
 * dialog (main.py L3919).
 *
 * v1.0.1 rework after the first real-device test: the desktop-parity
 * free-text path field is unusable as the PRIMARY input on a phone (nobody
 * types "/storage/emulated/0/..." on touch), so the screen now leads with
 * one-tap shortcuts — internal storage, the storage list (which also
 * reveals SD cards / OTG volumes), camera DCIM, Downloads, Pictures,
 * Documents, Videos, Music, plus any SECONDARY_STORAGE volumes the vendor
 * exports — and keeps the editable path field as the desktop-parity
 * fallback. The subfolder browser stays (tap to descend, arrow to go one
 * level up), and a silent empty list is replaced by clear states: how many
 * subfolders the current folder has, "no subfolders here — tap Select",
 * or "cannot read this folder".
 *
 * v1.0.2: the picker also serves the category dialog (PickerTarget.CATEGORY)
 * — the recursive checkbox is hidden there (it only applies to the browse
 * folder) and the selection lands back in the open dialog. The hardware
 * back button closes the picker instead of leaving the app, and the screen
 * respects the system bars on Android 15 (enforced edge-to-edge).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderPickerScreen(vm: SortViewModel) {
    val st by vm.state.collectAsState()
    val context = LocalContext.current
    var pathText by remember { mutableStateOf(vm.pickerStartPath()) }
    var recursive by remember { mutableStateOf(st.includeSubfolders) }
    var subdirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var readable by remember { mutableStateOf(true) }

    // Hardware back closes the picker instead of leaving the app (v1.0.2).
    BackHandler { vm.dismissFolderPicker() }

    val forCategory = st.pickerTarget == PickerTarget.CATEGORY

    LaunchedEffect(pathText) {
        val result = withContext(Dispatchers.IO) {
            try {
                val dir = File(pathText)
                val list = dir.listFiles { d -> d.isDirectory }
                if (list != null) {
                    Pair(list.sortedBy { it.name.lowercase() }, true)
                } else {
                    // listFiles == null: IO error. Distinguish "does not
                    // exist" from "unreadable" for the hint text below.
                    Pair(emptyList<File>(), dir.exists())
                }
            } catch (_: Exception) {
                Pair(emptyList<File>(), false)
            }
        }
        subdirs = result.first
        readable = result.second
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Android 15 edge-to-edge: keep clear of the system bars.
                .safeDrawingPadding()
                .padding(12.dp),
        ) {
            // Title row: close, title, confirm
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.dismissFolderPicker() }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                Text(
                    text = stringResource(R.string.picker_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { vm.applyFolderSelection(pathText, recursive) }) {
                    Text(stringResource(R.string.btn_select))
                }
            }

            // One-tap quick access — the phone-first replacement for typing
            // a path. "Storage list" (/storage) also shows SD cards / OTG
            // volumes, which then appear as regular subfolder entries.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickAccessTargets().forEach { target ->
                    AssistChip(
                        onClick = { pathText = target.dir.absolutePath },
                        label = { Text(stringResource(target.labelRes), maxLines = 1) },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Editable path + one level up (desktop parity fallback)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = pathText,
                    onValueChange = { pathText = it },
                    label = { Text(stringResource(R.string.field_path)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val parent = File(pathText).parentFile
                        if (parent != null) pathText = parent.path
                    },
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.picker_parent))
                }
            }

            st.pickerError?.let { err ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = context.resolveStatus(err),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!readable) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.picker_cannot_read),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Current folder name + subfolder count.
            Text(
                text = File(pathText).name.ifEmpty { pathText } +
                    "  •  " + context.resolveStatus(
                        statusOf(R.string.picker_subfolder_count, "n" to subdirs.size),
                    ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            // Subfolder ancestry list with explicit empty states.
            if (!readable) {
                Box(modifier = Modifier.weight(1f))
            } else if (subdirs.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.picker_no_subdirs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(subdirs, key = { it.absolutePath }) { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pathText = dir.absolutePath }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = dir.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // The recursive option only applies to the browse-folder target
            // (a category destination never scans recursively) — hidden in
            // CATEGORY mode (v1.0.2). Desktop L3937-3949.
            if (!forCategory) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(14.dp, 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = recursive, onCheckedChange = { recursive = it })
                            Text(
                                text = stringResource(R.string.chk_recursive),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = stringResource(R.string.chk_recursive_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { vm.dismissFolderPicker() }) {
                    Text(stringResource(R.string.action_cancel))
                }
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = { vm.applyFolderSelection(pathText, recursive) }) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.btn_select))
                }
            }
        }
    }
}

/** One shortcut chip: a localized label plus the folder it jumps to. */
private data class QuickTarget(@StringRes val labelRes: Int, val dir: File)

/**
 * The shortcut set. Environment.getExternalStoragePublicDirectory is
 * deprecated since API 29 but still functional (and unrestricted with
 * MANAGE_EXTERNAL_STORAGE, which this app requires); vendor strings like
 * SECONDARY_STORAGE are the practical way to reach removable volumes on
 * the many devices that do not expose /storage listings. Every entry is
 * best-effort: a missing directory just lands on the "cannot read" hint.
 */
@Suppress("DEPRECATION")
private fun quickAccessTargets(): List<QuickTarget> = buildList {
    add(QuickTarget(R.string.qa_internal, File("/storage/emulated/0")))
    add(QuickTarget(R.string.qa_storage_list, File("/storage")))
    try {
        System.getenv("SECONDARY_STORAGE")
            ?.split(":")
            ?.filter { it.isNotBlank() }
            ?.forEach { path -> add(QuickTarget(R.string.qa_sd_card, File(path))) }
    } catch (_: Exception) {
    }
    QUICK_TYPES.forEachIndexed { i, type ->
        try {
            add(QuickTarget(QUICK_LABELS[i], Environment.getExternalStoragePublicDirectory(type)))
        } catch (_: Exception) {
        }
    }
}

private val QUICK_TYPES = listOf(
    Environment.DIRECTORY_DCIM,
    Environment.DIRECTORY_DOWNLOADS,
    Environment.DIRECTORY_PICTURES,
    Environment.DIRECTORY_DOCUMENTS,
    Environment.DIRECTORY_MOVIES,
    Environment.DIRECTORY_MUSIC,
)

private val QUICK_LABELS = listOf(
    R.string.qa_dcim,
    R.string.qa_downloads,
    R.string.qa_pictures,
    R.string.qa_documents,
    R.string.qa_movies,
    R.string.qa_music,
)
