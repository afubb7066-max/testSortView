package com.mohammadjafari.sortview.media

import java.io.File

/**
 * File groups — port of the desktop extension tables (main.py L308-315) and
 * group_for_extension (main.py L835-853). The per-group card colors and
 * labels are the exact desktop GROUP_STYLE values (main.py L343-353); like
 * the desktop, the card labels are NOT translated.
 */
enum class MediaGroup(val cardLabel: String, val topColor: Long, val bottomColor: Long) {
    VIDEO("VIDEO", 0xFF5B7CF5, 0xFF33247E),
    AUDIO("AUDIO", 0xFF2DD4BF, 0xFF0F766E),
    IMAGE("IMAGE", 0xFF38BDF8, 0xFF1E3A8A),
    ARCHIVE("ARCHIVE", 0xFFFBBF24, 0xFF92400E),
    DOC("DOC", 0xFFFB7185, 0xFF9F1239),
    SUB("SUB", 0xFFE879A9, 0xFF9D2457),
    CODE("CODE", 0xFFA3E635, 0xFF3F6212),
    EXE("APP", 0xFF94A3B8, 0xFF334155),
    OTHER("FILE", 0xFF8B98AD, 0xFF25334A),
}

// Extension tables (desktop L308-315), dots stripped. Android adds
// heic/heif/avif to the image set: device-native photo formats that Coil
// decodes out of the box (the desktop table predates them on Android).
val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "bmp", "webp", "tiff", "tif", "jxl", "heic", "heif", "avif")

/** Animated images get the image group too (desktop L841). */
val ANIM_EXTS = setOf("gif")
val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "mpeg", "mpg", "m4v", "ts")
val AUDIO_EXTS = setOf("mp3", "wav", "flac", "aac", "ogg", "opus", "m4a", "wma")
val DOC_EXTS = setOf("pdf", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub")
val ARCHIVE_EXTS = setOf("zip", "rar", "7z", "tar", "gz")
val SUBTITLE_EXTS = setOf("srt", "ass", "ssa", "vtt", "sub", "idx")
val CODE_EXTS = setOf("py", "js", "ts", "html", "css", "json", "xml", "yaml", "yml", "cpp", "c", "h", "go", "php", "rb", "java", "rs")

/** The executable group lives inline in group_for_extension (desktop L849). */
val EXE_EXTS = setOf("exe", "msi", "bat", "cmd", "ps1", "sh")

/** Archive-bomb guard: never read more than this from one archive entry (desktop L322). */
const val MAX_ARCHIVE_IMAGE_BYTES = 64L * 1024 * 1024

private fun normalizeExt(ext: String): String = ext.lowercase().removePrefix(".")

/**
 * Port of group_for_extension (desktop L835-853) — same precedence order:
 * video, audio, image/anim, archive, sub, code, exe, doc, other. Note that
 * `.ts` sits in both VIDEO and CODE; the video check runs first here, and
 * playsAsVideo() additionally verifies the MPEG-TS content.
 */
fun groupForExtension(ext: String): MediaGroup {
    val e = normalizeExt(ext)
    return when {
        e in VIDEO_EXTS -> MediaGroup.VIDEO
        e in AUDIO_EXTS -> MediaGroup.AUDIO
        e in IMAGE_EXTS || e in ANIM_EXTS -> MediaGroup.IMAGE
        e in ARCHIVE_EXTS -> MediaGroup.ARCHIVE
        e in SUBTITLE_EXTS -> MediaGroup.SUB
        e in CODE_EXTS -> MediaGroup.CODE
        e in EXE_EXTS -> MediaGroup.EXE
        e in DOC_EXTS -> MediaGroup.DOC
        else -> MediaGroup.OTHER
    }
}

/**
 * True if a `.ts` file really is an MPEG Transport Stream — port of
 * _is_mpeg_ts_file (desktop L856-875). Every MPEG-TS packet starts with the
 * sync byte 0x47 and packets are 188 bytes, so the first byte plus the sync
 * byte of the second packet (offset 188) is a strong signal. Anything else —
 * including TypeScript source that happens to start with 'G' (0x47) — is
 * treated as code.
 */
fun isMpegTsFile(file: File): Boolean {
    return try {
        file.inputStream().use { fh ->
            val head = ByteArray(189)
            var read = 0
            while (read < head.size) {
                val n = fh.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read == 0 || head[0] != 0x47.toByte()) return false
            if (read >= 189 && head[188] != 0x47.toByte()) return false
            true
        }
    } catch (_: Exception) {
        false
    }
}

/**
 * Extension says video AND, for the ambiguous .ts, content agrees — port of
 * _plays_as_video (desktop L878-886).
 */
fun playsAsVideo(file: File, ext: String): Boolean {
    val e = normalizeExt(ext)
    if (e !in VIDEO_EXTS) return false
    if (e == "ts") return isMpegTsFile(file)
    return true
}
