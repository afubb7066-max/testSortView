package com.mohammadjafari.sortview.data

import android.content.Context
import android.net.Uri
import com.mohammadjafari.sortview.fs.Paths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Desktop category_sets parity (main.py: CATEGORY_SETS_DIR L274 =
 * APP_DIR / "category_sets"; save_category_set L2728; load_category_set
 * L2738; export L2761; import L2771).
 *
 * The JSON shape is compatibility contract #3 — a set saved on the desktop
 * imports here untouched and the other way around:
 *
 *     {"name": "...", "categories": [{"name": "...", "path": "..."}, ...]}
 *
 * Sets live in filesDir/category_sets (the Android APP_DIR, next to
 * config.json). Export/import run through SAF content URIs so any storage
 * provider works; saved sets are plain files, readable by the desktop too
 * when the user copies them off the device.
 */
class CategorySets(context: Context) {

    private val dir: File = File(context.filesDir, "category_sets")
    private val resolver = context.contentResolver
    private val json = Json { prettyPrint = true }

    /** save_category_set (main.py L2728-2736): safe_filename_stem(name) + ".json". */
    fun save(name: String, categories: List<Category>): File {
        val clean = name.trim()
        dir.mkdirs()
        val fp = File(dir, Paths.safeFilenameStem(clean) + ".json")
        writeSetFile(fp, clean, categories)
        return fp
    }

    /** load_category_set list step (main.py L2739): sorted by name.lowercase(). */
    fun list(): List<String> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .map { it.name }
            .sortedBy { it.lowercase() }

    /**
     * Parse a saved set (main.py L2746-2747): normalize_categories decides
     * validity — an empty result means "No valid categories in this set."
     */
    fun read(fileName: String): List<Category> =
        parseSet(File(dir, fileName).readText(Charsets.UTF_8))

    /** export_category_set_to_file (main.py L2761-2769): {"name": "export", ...}. */
    fun exportTo(uri: Uri, categories: List<Category>) {
        val payload = json.encodeToString(
            JsonObject.serializer(),
            buildSetJson("export", categories),
        )
        // "wt" truncates; the desktop overwrites the export file the same way.
        val out = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("cannot open output stream: $uri")
        out.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }

    /** import_category_set_from_file (main.py L2771-2781): read + normalize. */
    fun importFrom(uri: Uri): List<Category> {
        val text = resolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IOException("cannot open input stream: $uri")
        return parseSet(text)
    }

    private fun parseSet(text: String): List<Category> {
        val obj = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IOException("not a JSON object")
        return normalizeCategories(categoriesOf(obj))
    }

    private fun categoriesOf(obj: JsonObject): List<Category> =
        (obj["categories"] as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val n = (o["name"] as? JsonPrimitive)?.content ?: ""
            val p = (o["path"] as? JsonPrimitive)?.content ?: ""
            Category(n, p)
        } ?: emptyList()

    /** {"name", "categories"} with normalize_categories applied (desktop L2733). */
    private fun buildSetJson(name: String, categories: List<Category>): JsonObject =
        buildJsonObject {
            put("name", name)
            put("categories", buildJsonArray {
                normalizeCategories(categories).forEach { c ->
                    add(buildJsonObject {
                        put("name", c.name)
                        put("path", c.path)
                    })
                }
            })
        }

    /** Atomic write like the config store: tmp + fsync + rename. */
    private fun writeSetFile(fp: File, name: String, categories: List<Category>) {
        val payload = json.encodeToString(JsonObject.serializer(), buildSetJson(name, categories))
        val tmp = File(fp.parentFile, fp.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            fos.write(payload.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (!tmp.renameTo(fp)) {
            fp.delete()
            if (!tmp.renameTo(fp)) tmp.delete()
        }
    }
}
