package com.mohammadjafari.sortview.data

import android.app.Application
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Android-only settings that must NOT enter config.json — the desktop
 * normalize drops unknown keys (compatibility contract #1), so any new
 * Android-only preference written there would be silently erased the next
 * time the desktop app saved its config. v1.1.0 therefore keeps feed
 * preferences in a SEPARATE file (filesDir/android_prefs.json): no extra
 * DataStore dependency, an atomic tmp+rename write, and unknown keys are
 * tolerated on read so future fields stay forward-compatible.
 */
@Serializable
data class FeedPrefs(
    /** Category paths pinned to the feed quick dock (max 6, tap order). */
    val quickCategories: List<String> = emptyList(),
    /** Category path the horizontal swipe-LEFT send targets; null = off. */
    val swipeLeftPath: String? = null,
    /** Category path the horizontal swipe-RIGHT send targets; null = off. */
    val swipeRightPath: String? = null,
)

class AndroidPrefs(app: Application) {

    private val file = File(app.filesDir, "android_prefs.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): FeedPrefs = try {
        if (file.isFile) {
            json.decodeFromString(FeedPrefs.serializer(), file.readText())
        } else {
            FeedPrefs()
        }
    } catch (_: Exception) {
        // Corrupt file -> defaults; the next save rewrites it cleanly.
        FeedPrefs()
    }

    fun save(prefs: FeedPrefs) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(FeedPrefs.serializer(), prefs))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
            // Preferences are cosmetic — never let a disk error surface.
        }
    }
}
