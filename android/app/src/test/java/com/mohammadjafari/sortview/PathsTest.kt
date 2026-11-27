package com.mohammadjafari.sortview

import com.mohammadjafari.sortview.fs.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for the pure helper layer (fs/Paths.kt). Regression
 * coverage for the naturalKey overflow fix: digit runs beyond Int range —
 * 10+ digits, e.g. millisecond timestamps like "1700000000000.jpg" — used
 * to throw NumberFormatException, which the scan's generic catch turned
 * into a "scan failed" with an empty list for the whole folder.
 */
class PathsTest {

    @Test
    fun naturalKeyAcceptsThirteenDigitTimestamps() {
        // Threw NumberFormatException before the fix.
        val key = Paths.naturalKey("1700000000000.jpg")
        assertEquals(2, key.size)
        assertEquals(".jpg", key[1])
    }

    @Test
    fun naturalKeyComparesLongNumbersNumerically() {
        // Numeric, not lexicographic: 999 < 1700000000000 even though "9" > "1".
        assertTrue(Paths.compareNatural("999.jpg", "1700000000000.jpg") < 0)
        assertTrue(Paths.compareNatural("1700000000000.jpg", "999.jpg") > 0)
    }

    @Test
    fun naturalKeyComparesBeyondLongRange() {
        // 20 digits — desktop Python int has no limit; BigInteger keeps parity.
        assertTrue(Paths.compareNatural("99999999999999999999.jpg", "100000000000000000000.jpg") < 0)
        assertTrue(Paths.compareNatural("100000000000000000000.jpg", "99999999999999999999.jpg") > 0)
        // Equal 21-digit numbers compare equal.
        assertEquals(0, Paths.compareNatural("123456789012345678901.jpg", "123456789012345678901.jpg"))
    }

    @Test
    fun naturalKeyStillSortsOrdinaryNamesNaturally() {
        assertTrue(Paths.compareNatural("file2.jpg", "file10.jpg") < 0)
        assertTrue(Paths.compareNatural("file10.jpg", "file2.jpg") > 0)
        assertTrue(Paths.compareNatural("a1b", "a1c") < 0)
        // Case-insensitive text chunks (lowercased like the desktop).
        assertEquals(0, Paths.compareNatural("IMG_0012.jpg", "img_0012.JPG"))
    }

    @Test
    fun splitNameFollowsStemAndSuffixRules() {
        assertEquals("a.b" to ".c", Paths.splitName("a.b.c"))
        assertEquals(".profile" to "", Paths.splitName(".profile"))
        assertEquals("name" to "", Paths.splitName("name"))
    }

    @Test
    fun uniqueDestinationNeverReturnsAnExistingPath() {
        val dir = Files.createTempDirectory("sortview-test").toFile()
        try {
            val base = File(dir, "photo.jpg")
            // Nothing exists yet — the base name itself is used.
            assertEquals(base, Paths.uniqueDestination(base))
            base.createNewFile()
            val u1 = Paths.uniqueDestination(base)
            assertEquals("photo (1).jpg", u1.name)
            assertFalse(u1.exists())
            u1.createNewFile()
            assertEquals("photo (2).jpg", Paths.uniqueDestination(base).name)
            // Reserved (in-flight) names collide like existing ones.
            val u2 = Paths.uniqueDestination(base, setOf(File(dir, "photo (3).jpg").absolutePath))
            assertEquals("photo (4).jpg", u2.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun uniqueDestinationKeepsMultiDotNames() {
        val dir = Files.createTempDirectory("sortview-test2").toFile()
        try {
            val base = File(dir, "holiday.2024.tar.gz")
            base.createNewFile()
            val u = Paths.uniqueDestination(base)
            assertEquals("holiday.2024.tar (1).gz", u.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun safeFilenameStemKeepsSafeCharactersOnly() {
        assertEquals("my photo_2", Paths.safeFilenameStem("my photo_2"))
        assertNotEquals("", Paths.safeFilenameStem("***"))
        // Unsafe characters collapse to a non-empty timestamp fallback.
        assertTrue(Paths.safeFilenameStem("***").isNotEmpty())
    }

    @Test
    fun fileSizeTextFormatsHumanSizes() {
        assertEquals("512 B", Paths.fileSizeText(512))
        assertEquals("1.0 KB", Paths.fileSizeText(1024))
    }
}
