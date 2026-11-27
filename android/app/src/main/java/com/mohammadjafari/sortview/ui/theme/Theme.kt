package com.mohammadjafari.sortview.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Phase 4: the exact six desktop themes — the THEMES dict of
 * desktop/main.py (upstream v1.10.3, L361-392: light / paper / graphite /
 * dark / forest / amoled) rebuilt as Material3 color schemes.
 *
 * Role mapping:
 *   bg -> background          panel -> surface
 *   panel2/card -> surface containers
 *   text -> onBackground/onSurface     muted -> onSurfaceVariant
 *   accent -> primary         accent2 -> secondary/tertiary
 *   sel -> primary/secondaryContainer (selection surfaces)
 *   border -> outline/outlineVariant
 *   danger/danger_bg -> error/errorContainer
 *   preview_bg -> inverseSurface/surfaceContainerLowest
 *
 * Unknown config values fall back to "dark" exactly like the desktop
 * (apply_theme, main.py L2452-2453). The order of [KEYS] is the desktop
 * combo order (THEMES dict iteration).
 */
object SortThemes {

    /** All colors are the exact desktop hex codes (main.py L361-392). */
    private data class Def(
        val bg: Long,
        val panel: Long,
        val panel2: Long,
        val card: Long,
        val border: Long,
        val sel: Long,
        val text: Long,
        val muted: Long,
        val accent: Long,
        val accent2: Long,
        val danger: Long,
        val dangerBg: Long,
        val previewBg: Long,
    )

    private val THEMES = mapOf(
        "light" to Def(
            bg = 0xFFF3F5F9, panel = 0xFFFFFFFF, panel2 = 0xFFF6F8FB, card = 0xFFFFFFFF,
            border = 0xFFC6CFDB, sel = 0xFFD3DEEE, text = 0xFF1C2430, muted = 0xFF4F5965,
            accent = 0xFF3D5AF1, accent2 = 0xFF6A5CFF,
            danger = 0xFFDC2626, dangerBg = 0xFFB91C1C, previewBg = 0xFFE7EBF2,
        ),
        "paper" to Def(
            bg = 0xFFF2EBDC, panel = 0xFFFBF7EC, panel2 = 0xFFF6F0E1, card = 0xFFFFFDF6,
            border = 0xFFCDBF9F, sel = 0xFFE2D3B0, text = 0xFF2F2819, muted = 0xFF5A503C,
            accent = 0xFFB4530A, accent2 = 0xFF8F4108,
            danger = 0xFFB91C1C, dangerBg = 0xFF991B1B, previewBg = 0xFFE8DFC9,
        ),
        "graphite" to Def(
            bg = 0xFF24272D, panel = 0xFF2D3138, panel2 = 0xFF343941, card = 0xFF353A42,
            border = 0xFF4B515C, sel = 0xFF34494E, text = 0xFFECEEF1, muted = 0xFFAAB0BA,
            accent = 0xFF0B7F81, accent2 = 0xFF096669,
            danger = 0xFFFF6B66, dangerBg = 0xFFDC2626, previewBg = 0xFF1B1D22,
        ),
        "dark" to Def(
            bg = 0xFF0C111A, panel = 0xFF111927, panel2 = 0xFF131B29, card = 0xFF141D2C,
            border = 0xFF34455F, sel = 0xFF22344F, text = 0xFFE7EDF6, muted = 0xFF93A1B5,
            accent = 0xFF3D5AF1, accent2 = 0xFF6A5CFF,
            danger = 0xFFEF4444, dangerBg = 0xFFDC2626, previewBg = 0xFF070A10,
        ),
        "forest" to Def(
            bg = 0xFF0E1612, panel = 0xFF142019, panel2 = 0xFF192720, card = 0xFF1A2921,
            border = 0xFF2F4538, sel = 0xFF244235, text = 0xFFE6EFE9, muted = 0xFF95AB9F,
            accent = 0xFF19804F, accent2 = 0xFF13683F,
            danger = 0xFFFF7468, dangerBg = 0xFFDC2626, previewBg = 0xFF09100C,
        ),
        "amoled" to Def(
            bg = 0xFF000000, panel = 0xFF0D0D12, panel2 = 0xFF111117, card = 0xFF13131A,
            border = 0xFF2B2B38, sel = 0xFF23233A, text = 0xFFF2F2F5, muted = 0xFF9A9AA8,
            accent = 0xFF6A5AF0, accent2 = 0xFF5A49E0,
            danger = 0xFFFF5C7A, dangerBg = 0xFFE11D48, previewBg = 0xFF000000,
        ),
    )

    /** Picker order = the desktop combo order (THEMES dict iteration). */
    val KEYS = listOf("light", "paper", "graphite", "dark", "forest", "amoled")

    fun isKnown(key: String): Boolean = key in THEMES

    fun colorSchemeFor(themeKey: String): ColorScheme {
        val t = THEMES[themeKey] ?: THEMES.getValue("dark")
        val isDark = themeKey != "light" && themeKey != "paper"
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = Color(t.accent),
            onPrimary = Color.White,
            primaryContainer = Color(t.sel),
            onPrimaryContainer = Color(t.text),
            secondary = Color(t.accent2),
            onSecondary = Color.White,
            secondaryContainer = Color(t.sel),
            onSecondaryContainer = Color(t.text),
            tertiary = Color(t.accent2),
            onTertiary = Color.White,
            background = Color(t.bg),
            onBackground = Color(t.text),
            surface = Color(t.panel),
            onSurface = Color(t.text),
            surfaceVariant = Color(t.card),
            onSurfaceVariant = Color(t.muted),
            surfaceContainerLowest = Color(t.previewBg),
            surfaceContainerLow = Color(t.panel2),
            surfaceContainer = Color(t.panel2),
            surfaceContainerHigh = Color(t.card),
            surfaceContainerHighest = Color(t.card),
            outline = Color(t.border),
            outlineVariant = Color(t.border),
            error = Color(t.danger),
            onError = Color.White,
            errorContainer = Color(t.dangerBg),
            onErrorContainer = Color(t.text),
            inverseSurface = Color(t.previewBg),
            inverseOnSurface = Color(t.bg),
        )
    }
}

/**
 * The app theme. Phase 4: explicit, config-driven — config.json "theme"
 * wins and unknown values render as "dark" (desktop parity). The system
 * light/dark mode no longer switches the palette by itself; the user picks
 * one of the six desktop themes in the header menu.
 */
@Composable
fun SortViewTheme(themeKey: String, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SortThemes.colorSchemeFor(themeKey), content = content)
}
