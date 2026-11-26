package com.mohammadjafari.sortview.scan

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

enum class ScanError {
    /** "Folder does not exist or is not a directory" (desktop L1451). */
    NOT_A_DIRECTORY,
}

class ScanException(val kind: ScanError) : Exception()

/**
 * Folder scanner — behavioral port of the desktop ScannerThread
 * (main.py L1397-1459):
 *  - files only (directories are traversed, never listed);
 *  - unreadable directories/entries are skipped silently;
 *  - recursive mode uses a stack so the walk stops cleanly when cancelled;
 *  - cooperative cancellation at every entry (desktop checks its stop event
 *    the same way);
 *  - the emission order does not matter: the caller re-sorts the final list
 *    (the desktop sorts after scanning too, main.py L2901-2902).
 */
class FolderScanner {

    suspend fun scan(
        root: File,
        recursive: Boolean,
        onFound: (File) -> Unit,
    ) {
        if (!root.exists() || !root.isDirectory) {
            throw ScanException(ScanError.NOT_A_DIRECTORY)
        }
        if (recursive) {
            val stack = ArrayDeque<File>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val dir = stack.removeLast()
                val entries = try {
                    dir.listFiles()?.sortedByDescending { it.name.lowercase() }
                } catch (_: Exception) {
                    null
                } ?: continue
                // Pushed in descending order so the LIFO pop visits
                // subdirectories in ascending name order (desktop L1414-1431).
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    try {
                        if (entry.isFile) {
                            onFound(entry)
                        } else if (entry.isDirectory) {
                            stack.addLast(entry)
                        }
                    } catch (_: Exception) {
                        continue
                    }
                }
            }
        } else {
            val entries = try {
                root.listFiles()?.sortedBy { it.name.lowercase() }
            } catch (_: Exception) {
                null
            } ?: return
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                try {
                    if (entry.isFile) onFound(entry)
                } catch (_: Exception) {
                    continue
                }
            }
        }
    }
}
