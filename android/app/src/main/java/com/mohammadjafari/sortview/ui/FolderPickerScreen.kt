package com.mohammadjafari.sortview.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
 * dialog (main.py L3919): an editable path (keyboard entry), folder
 * ancestry navigation (tap a subfolder to descend, the arrow to go one
 * level higher), the recursive checkbox card, and Select/Cancel.
 */
@Composable
fun FolderPickerScreen(vm: SortViewModel) {
    val st by vm.state.collectAsState()
    val context = LocalContext.current
    var pathText by remember { mutableStateOf(vm.pickerStartPath()) }
    var recursive by remember { mutableStateOf(st.includeSubfolders) }
    var subdirs by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(pathText) {
        val list = withContext(Dispatchers.IO) {
            try {
                File(pathText).listFiles { f -> f.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList<File>()
            }
        }
        subdirs = list
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
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

            // Editable path + one level up
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

            Spacer(Modifier.height(8.dp))

            // Subfolder ancestry list
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
                        )
                    }
                }
            }

            // The recursive option gets its own highlighted card (desktop L3937-3949).
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
