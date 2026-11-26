package com.mohammadjafari.sortview.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Phase 2 thumbnail engine — behavioral port of the desktop thumbnail
 * pipeline (generate_thumbnail_bytes, main.py L1152-1273) adapted to
 * Android decoders:
 *  - video frame: MediaMetadataRetriever with the desktop's stepped seek
 *    (1s -> 0.4s -> first frame, main.py L734-743) so very short clips
 *    still get a real thumbnail;
 *  - PDF: PdfRenderer page 0 (the desktop uses PyMuPDF, main.py L1233-1249);
 *  - archives: the FIRST image entry inside the archive (desktop zip L979,
 *    tar L1051, 7z L1011); entries above MAX_ARCHIVE_IMAGE_BYTES are
 *    skipped (archive-bomb guard); RAR stays on the type card exactly like
 *    the portable desktop build without unrar (main.py L1094) — junrar is
 *    an optional later upgrade (ROADMAP mapping table);
 *  - everything else yields null and the UI draws the gradient type card,
 *    the equivalent of generate_type_card_bytes (main.py L921-976).
 *
 * Caching mirrors the desktop two levels (main.py L791-823):
 *  - memory: an LRU of decoded bitmaps (the desktop keeps Qt pixmaps per
 *    item; an LRU keeps phone memory bounded);
 *  - disk: cacheDir/thumbs/<sha1>.png with the SAME key composition
 *    (resolved path | mtime | size | requested size), written atomically
 *    via tmp file + fsync + rename. The SHA1 is only a cache key, never a
 *    security primitive (desktop L797-799).
 *
 * Lazy loading: the desktop queues visible tiles first, then the rest in
 * the background (main.py L3002-3051). A LazyGrid only composes visible
 * tiles, so on-demand loading per composed tile IS the visible-first
 * policy — off-screen tiles load when scrolled into view.
 */
class ThumbnailEngine(context: Context) {

    private val cacheDir: File = File(context.applicationContext.cacheDir, "thumbs")

    private val memory = object : LruCache<String, Bitmap>(MEMORY_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /**
     * Desktop VIDEO_THUMB_SEMAPHORE (main.py L327-328) caps parallel video
     * decodes; on phones the cap also keeps frame decode memory bounded.
     */
    private val videoPermits = Semaphore(
        maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2)),
    )

    /**
     * The desktop bumps its thumbnail generation on every rescan
     * (main.py L2846) so in-flight thumbnails from the old scan cannot be
     * mistaken for the new list. Here a rescan clears the memory level;
     * the disk level self-invalidates through the mtime/size cache key.
     */
    fun invalidate() {
        memory.evictAll()
    }

    /**
     * The single entry point the grid tiles call. Returns the real preview
     * bitmap for video/PDF/archive files, or null when the file belongs on
     * a type card (unknown types, unsupported codecs, corrupt archives,
     * failed decodes — same fallback chain as the desktop).
     */
    suspend fun thumbnailFor(path: String, ext: String, reqPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(path)
        val e = ext.lowercase().removePrefix(".")
        val canPreview = e == "pdf" || playsAsVideo(file, e) || e in ARCHIVE_EXTS
        if (!file.isFile || !canPreview) return@withContext null

        val key = cacheKey(file, reqPx)
        memory.get(key)?.let { return@withContext it }

        val cacheFile = File(cacheDir, "$key.png")
        if (cacheFile.isFile) {
            val cached = decodeSampled(cacheFile, reqPx)
            if (cached != null) {
                memory.put(key, cached)
                return@withContext cached
            }
            // Corrupt cache entry — drop it and regenerate (desktop treats
            // an unreadable cache file as a miss, main.py L802-810).
            cacheFile.delete()
        }
        cacheDir.mkdirs()

        val generated = when {
            playsAsVideo(file, e) -> videoFrame(file, reqPx)
            e == "pdf" -> pdfFirstPage(file, reqPx)
            else -> archiveFirstImage(file, e, reqPx)
        } ?: return@withContext null

        memory.put(key, generated)
        writeCacheSafely(File(cacheDir, "$key.png"), generated)
        generated
    }

    // ---------------- video (main.py L734-743 / L1190-1231) ----------------

    private suspend fun videoFrame(file: File, reqPx: Int): Bitmap? = videoPermits.withPermit {
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                // Stepped seek like the desktop ffmpeg attempts ("1", "0.4",
                // then the very first frame): clips shorter than the seek
                // point produce no frame, so empty results fall back. Sync
                // seeking is the fast path; the final first-frame pass is
                // exact (OPTION_CLOSEST).
                val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(400_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                frame?.let { downscale(it, reqPx) }
            } catch (_: Exception) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------------- pdf (main.py L1233-1249) ----------------

    private suspend fun pdfFirstPage(file: File, reqPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = PdfRenderer(pfd)
                try {
                    if (renderer.pageCount <= 0) return@withContext null
                    val page = renderer.openPage(0)
                    try {
                        // The desktop clamps its render zoom to 1.0..3.0
                        // (main.py L1239); here the page's longer side is
                        // rendered at up to 2x the tile size, which stays in
                        // the same quality band while bounding allocations
                        // for poster-sized pages.
                        val maxSidePts = maxOf(page.width, page.height).coerceAtLeast(1)
                        val scale = (reqPx * 2f) / maxSidePts
                        val outW = (page.width * scale).toInt().coerceIn(1, 1600)
                        val outH = (page.height * scale).toInt().coerceIn(1, 1600)
                        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                        // PdfRenderer draws onto ARGB_8888; pre-fill white so
                        // transparent PDF backgrounds look like paper (the
                        // desktop renders with alpha=False, main.py L1240).
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        downscale(bmp, reqPx)
                    } finally {
                        try {
                            page.close()
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    try {
                        renderer.close()
                    } catch (_: Exception) {
                    }
                }
            } finally {
                try {
                    pfd.close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- archives (main.py L979-1079, guard L322) ----------------

    private suspend fun archiveFirstImage(file: File, ext: String, reqPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        // A candidate entry is streamed to a temp file first, then decoded
        // with bounds+sample — the archive stream itself cannot be decoded
        // in two passes like a real file.
        var tmp: File? = null
        try {
            tmp = File.createTempFile("svthumb", ".bin", cacheDir)
            val found = when (ext) {
                "zip" -> copyFirstZipImage(file, tmp)
                "tar" -> copyFirstTarImage(file, tmp, gzip = false)
                "gz" -> copyFirstTarImage(file, tmp, gzip = true)
                "7z" -> copyFirstSevenZImage(file, tmp)
                // rar: type card, like the portable desktop build without
                // unrar (main.py L1094).
                else -> false
            }
            if (!found) return@withContext null
            decodeSampled(tmp, reqPx)
        } catch (_: Exception) {
            null
        } finally {
            tmp?.delete()
        }
    }

    private fun copyFirstZipImage(file: File, out: File): Boolean {
        ZipFile(file).use { zf ->
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (!isImageName(entry.name)) continue
                val size = entry.size
                // size < 0 = unknown central-directory size: skip rather
                // than risk an unbounded copy.
                if (size <= 0 || size > MAX_ARCHIVE_IMAGE_BYTES) continue
                zf.getInputStream(entry).use { input ->
                    if (!copyBounded(input, out, size)) return false
                }
                return true
            }
        }
        return false
    }

    /**
     * Desktop _first_image_from_tar (main.py L1051-1079): streaming
     * iteration stops at the first image entry, so a huge tar never needs a
     * full listing up front. GZIP wrapping replaces tarfile's "r:*"
     * auto-detection; a plain .gz that is not a tar raises and falls back
     * to the type card.
     */
    private fun copyFirstTarImage(file: File, out: File, gzip: Boolean): Boolean {
        FileInputStream(file).use { fis ->
            val raw: InputStream = if (gzip) GZIPInputStream(fis) else fis
            val tin = TarArchiveInputStream(raw)
            try {
                while (true) {
                    val entry = tin.nextTarEntry ?: break
                    if (!entry.isFile) continue
                    if (!isImageName(entry.name)) continue
                    val size = entry.size
                    if (size <= 0 || size > MAX_ARCHIVE_IMAGE_BYTES) continue
                    if (!copyBounded(tin, out, size)) return false
                    return true
                }
            } finally {
                try {
                    tin.close()
                } catch (_: Exception) {
                }
            }
        }
        return false
    }

    /** Desktop _first_image_from_7z (main.py L1011-1048), via commons-compress. */
    private fun copyFirstSevenZImage(file: File, out: File): Boolean {
        val sz = SevenZFile(file)
        try {
            val entries = sz.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!isImageName(entry.name)) continue
                val size = entry.size
                if (size <= 0 || size > MAX_ARCHIVE_IMAGE_BYTES) continue
                val input = sz.getInputStream(entry) ?: continue
                input.use {
                    if (!copyBounded(input, out, size)) return false
                }
                return true
            }
        } finally {
            try {
                sz.close()
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Copy at most [limit] bytes of [input] into [out]. Returns false when
     * the stream delivers more than the declared entry size (a lying
     * header) or nothing at all — the partial output is discarded.
     */
    private fun copyBounded(input: InputStream, out: File, limit: Long): Boolean {
        FileOutputStream(out).use { fos ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limit) {
                    fos.close()
                    out.delete()
                    return false
                }
                fos.write(buf, 0, n)
            }
            fos.flush()
            return total > 0
        }
    }

    /** First image entry only — IMAGE_EXTS without ANIM, like the desktop (L982). */
    private fun isImageName(name: String): Boolean {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTS
    }

    // ---------------- cache + bitmap helpers (main.py L791-832) ----------------

    /**
     * Same key composition as desktop thumb_cache_key (main.py L791-799):
     * resolved path | mtime | size | requested size, plus a scheme tag
     * instead of the desktop's "qt6-v4". The SHA1 is only a cache key,
     * never a security primitive.
     */
    private fun cacheKey(file: File, reqPx: Int): String {
        val src = try {
            "${file.canonicalPath}|${file.lastModified()}|${file.length()}|$reqPx|android-v1"
        } catch (_: Exception) {
            "$file|$reqPx|android-v1"
        }
        val digest = MessageDigest.getInstance("SHA-1").digest(src.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Atomic cache write like the desktop _write_cache_safely (main.py
     * L813-823): write a uniquely named tmp file, flush + fsync, rename.
     * A failed write only costs a cache miss.
     */
    private fun writeCacheSafely(target: File, bitmap: Bitmap) {
        var tmp: File? = null
        try {
            tmp = File(target.parentFile, target.name + ".tmp" + System.nanoTime())
            FileOutputStream(tmp).use { fos ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    throw IOException("PNG compress failed")
                }
                fos.flush()
                fos.fd.sync()
            }
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (_: Exception) {
            tmp?.delete()
        }
    }

    /**
     * Desktop decodes with PIL and downsamples in memory; on Android the
     * file is decoded twice (bounds, then sampled) so a 100-megapixel image
     * inside an archive cannot blow up the heap. The result fits the tile
     * like the desktop im.thumbnail() pass (main.py L826-832).
     */
    private fun decodeSampled(f: File, reqPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqPx * 2)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(f.absolutePath, opts) ?: return null
        return downscale(decoded, reqPx)
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var maxSide = maxOf(width, height)
        while (maxSide > target) {
            maxSide /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Fit inside the requested square, keeping aspect — the bitmap-level
     * equivalent of the desktop pix.scaled(size, size, KEEP_ASPECT, SMOOTH)
     * (main.py L2964).
     */
    private fun downscale(src: Bitmap, reqPx: Int): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= reqPx) return src
        val scale = reqPx.toFloat() / maxSide
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createScaledBitmap(src, w, h, true)
        if (out !== src) src.recycle()
        return out
    }

    companion object {
        /** ~32 MB of decoded tiles — keeps a big visible window warm. */
        private const val MEMORY_KB = 32 * 1024
    }
}
