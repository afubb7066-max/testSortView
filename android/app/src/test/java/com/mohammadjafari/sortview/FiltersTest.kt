package com.mohammadjafari.sortview

import com.mohammadjafari.sortview.fs.Filters
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the desktop extension-filter parser and the
 * Name/Type sort ordering (desktop main.py L1276 / L1304 / L1308, v1.10.3).
 * Every expected value was locked against the Python implementation run
 * locally (the same inputs fed to the reference functions).
 */
class FiltersTest {

    // ---- parse_extension_filter (main.py L1276) ----

    @Test
    fun commaSeparatedWithDots() {
        assertEquals(listOf(".jpg", ".png"), Filters.parseExtensionFilter(".jpg,.png"))
    }

    @Test
    fun spaceSeparatedWithoutDots() {
        assertEquals(listOf(".jpg", ".png"), Filters.parseExtensionFilter("jpg png"))
    }

    @Test
    fun starFormsWithSemicolons() {
        assertEquals(listOf(".jpg", ".mp4"), Filters.parseExtensionFilter("*.jpg;*.mp4"))
    }

    @Test
    fun fullWildcardsDisableFiltering() {
        assertEquals(emptyList<String>(), Filters.parseExtensionFilter("*"))
        assertEquals(emptyList<String>(), Filters.parseExtensionFilter(".*"))
        assertEquals(emptyList<String>(), Filters.parseExtensionFilter("*.*"))
    }

    @Test
    fun uppercaseNormalizesToLowercase() {
        assertEquals(listOf(".jpg"), Filters.parseExtensionFilter("JPG"))
    }

    @Test
    fun starInsideTokenIsStripped() {
        // ".jp*g" -> ".jpg" (desktop strips "*" after the leading dot)
        assertEquals(listOf(".jpg"), Filters.parseExtensionFilter(".jp*g"))
    }

    @Test
    fun starPrefixWithoutDotKeepsTheDesktopQuirk() {
        // "*png" becomes ".*png" — it matches nothing, exactly like main.py
        assertEquals(listOf(".*png"), Filters.parseExtensionFilter("*png"))
    }

    @Test
    fun blankTextMeansNoFilter() {
        assertEquals(emptyList<String>(), Filters.parseExtensionFilter("  "))
    }

    @Test
    fun duplicatesCollapseOrderPreserved() {
        assertEquals(listOf(".jpeg", ".webp"), Filters.parseExtensionFilter("jpeg;.webp,jpeg"))
    }

    @Test
    fun multiDotTokenKeepsTheWholeTail() {
        assertEquals(listOf(".a.b.c"), Filters.parseExtensionFilter("a.b.c"))
    }

    @Test
    fun dotOnlyTokenIsDropped() {
        assertEquals(listOf(".jpg"), Filters.parseExtensionFilter(". , .jpg"))
    }

    @Test
    fun starDotExtForm() {
        assertEquals(listOf(".png"), Filters.parseExtensionFilter("*png".replace("*", "*.")))
    }

    // ---- sort_key_for_path (main.py L1308) ----

    @Test
    fun nameModeIsNatural() {
        val names = listOf("10.mp4", "2.mp4", "1.mp4", "IMG_10.jpg", "IMG_9.jpg")
        val sorted = sortNames(names, Filters.Mode.NAME)
        assertEquals(listOf("1.mp4", "2.mp4", "10.mp4", "IMG_9.jpg", "IMG_10.jpg"), sorted)
    }

    @Test
    fun typeModeGroupsBySuffixThenNaturalName() {
        val names = listOf("b.txt", "a.tar", "2.mp4", "10.mp4", "1.mp4", "IMG_10.jpg", "IMG_9.jpg", "x", ".profile")
        val sorted = sortNames(names, Filters.Mode.TYPE)
        // "" suffix first (.profile before x by natural key), then .jpg,
        // .mp4, .tar, .txt — suffix first, natural name inside the group.
        assertEquals(
            listOf(".profile", "x", "IMG_9.jpg", "IMG_10.jpg", "1.mp4", "2.mp4", "10.mp4", "a.tar", "b.txt"),
            sorted,
        )
    }

    @Test
    fun bigDigitChunksDoNotOverflow() {
        val names = listOf("1700000000000.jpg", "2.jpg")
        assertEquals(listOf("2.jpg", "1700000000000.jpg"), sortNames(names, Filters.Mode.NAME))
    }

    @Test
    fun suffixCompareIsCaseInsensitive() {
        val names = listOf("B.TXT", "a.txt")
        val sorted = sortNames(names, Filters.Mode.TYPE)
        // Same suffix ".txt" -> natural name decides ("a" < "b").
        assertEquals(listOf("a.txt", "B.TXT"), sorted)
    }

    private fun sortNames(names: List<String>, mode: Filters.Mode): List<String> =
        names.sortedWith(Comparator { a, b -> Filters.compare(a, b, mode) })
}
