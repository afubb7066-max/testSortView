package com.mohammadjafari.sortview.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * "Open with system player" — the Android port of the desktop
 * open_selected_with_system_player / open_file path (main.py L3407 /
 * L3974): QDesktopServices.openUrl becomes ACTION_VIEW with a
 * FileProvider content URI + FLAG_GRANT_READ_URI_PERMISSION, because the
 * browsed files live on shared storage outside the app sandbox (ROADMAP
 * mapping table: «پخش با برنامه‌ی خود سیستم» → Implicit Intent ACTION_VIEW).
 *
 * Returns null on success, or the error text for the desktop status line
 * "Cannot open: {e}" (main.py L3984).
 */
object SystemPlayer {

    private const val AUTHORITY_SUFFIX = ".files"

    fun openWithSystemApp(context: Context, path: String, ext: String): String? {
        return try {
            val file = File(path)
            if (!file.isFile) return "no such file"
            val uri: Uri = FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file,
            )
            // MIME from the extension; unknown extensions fall back to
            // octet-stream (Android then lets the user pick a handler).
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext.lowercase().removePrefix("."))
                ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
}
