package com.mohammadjafari.sortview.data

import android.content.Context
import com.mohammadjafari.sortview.fs.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * One category — the exact desktop JSON shape {"name": str, "path": str}
 * (compatibility contract #2). Round-trips with desktop config.json and
 * category_sets/*.json untouched.
 */
data class Category(val name: String, val path: String)

/**
 * Port of normalize_categories (main.py L1314): empty names become
 * "Untitled", entries with an empty path are dropped, duplicates collapse
 * by the (name.lower(), path) key. Order is preserved.
 */
fun normalizeCategories(categories: List<Category>): List<Category> {
    val cleaned = mutableListOf<Category>()
    val seen = HashSet<Pair<String, String>>()
    for (item in categories) {
        val name = item.name.trim().ifEmpty { "Untitled" }
        val path = item.path.trim()
        if (path.isEmpty()) continue
        val key = name.lowercase() to path
        if (key in seen) continue
        seen.add(key)
        cleaned.add(Category(name, path))
    }
    return cleaned
}

/**
 * Parsed view of config.json — the same schema the desktop writes
 * (main.py L2091: candidate_folder, folder_user_selected, last_browse_dir,
 * include_subfolders, categories, thumb_size, theme, language).
 * Defaults match the desktop __init__ (main.py L1962-1985).
 */
data class SortConfig(
    val candidateFolder: String = "",
    val folderUserSelected: Boolean = false,
    val lastBrowseDir: String = "",
    val includeSubfolders: Boolean = false,
    val categories: List<Category> = emptyList(),
    val thumbSize: Int = Paths.DEFAULT_THUMB,
    val theme: String = "dark",
    val language: String = "",
)

/**
 * Port of the desktop config layer (main.py L2074 load / L2091 save):
 *  - the JSON object is kept RAW, so keys the Android port does not know
 *    (and future desktop keys) survive every save untouched;
 *  - a corrupt config.json is moved aside to config.json.corrupt instead of
 *    being overwritten (the user can still recover their categories);
 *  - writes are atomic: tmp file, flushed + fsync, then rename.
 */
class AppConfigStore(context: Context) {

    private val file: File = File(context.filesDir, "config.json")
    private val json = Json { prettyPrint = true }

    /** Load the raw config object; {} when missing. Corrupt -> renamed to .corrupt. */
    fun load(): JsonObject {
        return try {
            if (!file.exists()) return JsonObject(LinkedHashMap())
            val text = file.readText(Charsets.UTF_8)
            Json.parseToJsonElement(text) as? JsonObject ?: JsonObject(LinkedHashMap())
        } catch (e: Exception) {
            try {
                val backup = File(file.parentFile, file.name + ".corrupt")
                if (!file.renameTo(backup)) file.delete()
            } catch (_: Exception) {
            }
            JsonObject(LinkedHashMap())
        }
    }

    fun parse(raw: JsonObject): SortConfig {
        fun str(key: String): String = (raw[key] as? JsonPrimitive)?.content ?: ""
        fun bool(key: String, default: Boolean): Boolean =
            (raw[key] as? JsonPrimitive)?.booleanOrNull ?: default
        fun int(key: String, default: Int): Int =
            (raw[key] as? JsonPrimitive)?.intOrNull ?: default

        val cats: List<Category> = (raw["categories"] as? JsonArray)
            ?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val n = (o["name"] as? JsonPrimitive)?.content ?: ""
                val p = (o["path"] as? JsonPrimitive)?.content ?: ""
                Category(n, p)
            }
            ?: emptyList()
        val lang = str("language").trim().lowercase()
        return SortConfig(
            candidateFolder = str("candidate_folder").trim(),
            folderUserSelected = bool("folder_user_selected", false),
            lastBrowseDir = str("last_browse_dir").trim(),
            includeSubfolders = bool("include_subfolders", false),
            categories = cats,
            thumbSize = int("thumb_size", Paths.DEFAULT_THUMB),
            theme = str("theme").ifEmpty { "dark" },
            language = if (lang == "fa" || lang == "en") lang else "",
        )
    }

    /** Build the known-keys object the desktop writes (same key set + same types). */
    fun knownKeys(cfg: SortConfig, categories: List<Category>): JsonObject = buildJsonObject {
        put("candidate_folder", cfg.candidateFolder)
        put("folder_user_selected", cfg.folderUserSelected)
        put("last_browse_dir", cfg.lastBrowseDir)
        put("include_subfolders", cfg.includeSubfolders)
        put("categories", buildJsonArray {
            categories.forEach { c ->
                add(buildJsonObject {
                    put("name", c.name)
                    put("path", c.path)
                })
            }
        })
        put("thumb_size", cfg.thumbSize)
        put("theme", cfg.theme)
        put("language", cfg.language)
    }

    /** Merge the known keys into the raw object, preserving every unknown key. */
    fun merge(raw: JsonObject, update: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(raw)
        out.putAll(update)
        return JsonObject(out)
    }

    /** Atomic save: config.json.tmp -> flush + fsync -> rename (main.py L2101-2111). */
    fun save(merged: JsonObject) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                fos.write(json.encodeToString(JsonObject.serializer(), merged).toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * Quick language peek used by MainActivity.attachBaseContext before
         * anything else exists. "" = no explicit choice (follow the system,
         * like the desktop auto-detect in app_i18n.load_saved_language).
         */
        fun peekLanguage(context: Context): String {
            return try {
                val f = File(context.filesDir, "config.json")
                if (!f.exists()) return ""
                val el = Json.parseToJsonElement(f.readText(Charsets.UTF_8))
                val lang = ((el as? JsonObject)?.get("language") as? JsonPrimitive)
                    ?.content?.trim()?.lowercase()
                if (lang == "fa" || lang == "en") lang else ""
            } catch (_: Exception) {
                ""
            }
        }
    }
}
