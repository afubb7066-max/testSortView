package com.mohammadjafari.sortview.fs

import java.io.File
import java.io.IOException
import java.math.BigInteger

/**
 * Pure path / naming helpers — behavioral ports of the desktop helpers in
 * desktop/main.py (upstream v1.10.3):
 *   normalize_categories L1314, safe_filename_stem L1335,
 *   unique_destination  L1340, same_path            L1353,
 *   natural_key         L1304, file_size_text       L1378.
 * These helpers are the compatibility contract: the Android port must never
 * overwrite a file and must use the exact desktop naming pattern.
 */
object Paths {

    /** Same chunking as the desktop (TRANSFER_CHUNK_SIZE, main.py L330): 2 MiB. */
    const val TRANSFER_CHUNK_SIZE: Int = 2 * 1024 * 1024

    /** Desktop default thumbnail size (DEFAULT_THUMB, main.py L317). */
    const val DEFAULT_THUMB: Int = 168

    /**
     * Port of the stem/suffix rules behind Path.stem / Path.suffix:
     * "a.b.c" -> ("a.b", ".c"); ".profile" -> (".profile", ""); "name" -> ("name", "").
     */
    fun splitName(name: String): Pair<String, String> {
        val idx = name.lastIndexOf('.')
        return if (idx <= 0) name to "" else name.substring(0, idx) to name.substring(idx)
    }

    /**
     * Port of unique_destination (main.py L1340). Never returns an existing
     * path; duplicates become "stem (1).ext", "stem (2).ext", ... A reserved
     * set lets the caller hold names for in-flight transfers.
     */
    fun uniqueDestination(base: File, reserved: Set<String> = emptySet()): File {
        val parent = base.parentFile
        val (stem, suffix) = splitName(base.name)
        var candidate = base
        var i = 1
        while (candidate.exists() || candidate.absolutePath in reserved) {
            candidate = File(parent, "$stem ($i)$suffix")
            i++
        }
        return candidate
    }

    /** Port of same_path (main.py L1353): canonical path comparison. */
    fun samePath(a: File?, b: File?): Boolean {
        if (a == null || b == null) return false
        return try {
            a.canonicalPath == b.canonicalPath
        } catch (_: Exception) {
            a.absolutePath == b.absolutePath
        }
    }

    /**
     * Port of safe_filename_stem (main.py L1335): keep letters/digits and
     * " _-." (Unicode aware, like Python's str.isalnum); else a timestamp.
     */
    fun safeFilenameStem(stem: String): String {
        val cleaned = stem.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' || it == '.' }.trim()
        return cleaned.ifEmpty { (System.currentTimeMillis() / 1000).toString() }
    }

    /**
     * Port of natural_key (main.py L1304): digits compare as numbers, text
     * lowercased. Digit chunks become [BigInteger]: desktop Python int is
     * unbounded, but Int conversion threw NumberFormatException on digit
     * runs of 10+ digits (millisecond timestamps like "1700000000000.jpg")
     * and — swallowed by the scan's generic catch — blanked the whole
     * folder into "scan failed".
     */
    fun naturalKey(text: String): List<Any> {
        val parts = mutableListOf<Any>()
        var last = 0
        for (m in digits.findAll(text)) {
            if (m.range.first > last) parts.add(text.substring(last, m.range.first).lowercase())
            parts.add(BigInteger(m.value))
            last = m.range.last + 1
        }
        if (last < text.length) parts.add(text.substring(last).lowercase())
        return parts
    }

    /** Stable lexicographic comparison over naturalKey (desktop sort "Name" mode). */
    fun compareNatural(a: String, b: String): Int {
        val ka = naturalKey(a)
        val kb = naturalKey(b)
        val n = minOf(ka.size, kb.size)
        for (i in 0 until n) {
            val x = ka[i]
            val y = kb[i]
            val c = when {
                x is BigInteger && y is BigInteger -> x.compareTo(y)
                x is String && y is String -> x.compareTo(y)
                else -> x.toString().compareTo(y.toString())
            }
            if (c != 0) return c
        }
        return ka.size - kb.size
    }

    /** Port of file_size_text (main.py L1378): "1.2 MB" style human sizes. */
    fun fileSizeText(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var f = bytes.toFloat()
        for (u in units) {
            if (f < 1024f || u == units[units.size - 1]) {
                return if (u == "B") "${f.toInt()} B" else String.format("%.1f %s", f, u)
            }
            f /= 1024f
        }
        return ""
    }

    /**
     * Port of force_unlink (main.py L1360): retry the delete after clearing
     * the write protection; every other failure propagates to the caller so
     * the transfer is reported as failed and the source survives.
     */
    fun forceUnlink(f: File) {
        if (f.delete()) return
        try {
            f.setWritable(true)
        } catch (_: Exception) {
        }
        if (!f.delete()) {
            throw IOException("cannot delete: ${f.absolutePath}")
        }
    }

    private val digits = Regex("\\d+")
}
