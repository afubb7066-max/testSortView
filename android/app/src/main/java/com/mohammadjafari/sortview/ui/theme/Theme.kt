package com.mohammadjafari.sortview.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Phase 1 theme: follow the system light/dark mode. The desktop's six
 * themes (light/paper/graphite/dark/forest/amoled) arrive in Phase 4;
 * config.json's "theme" value is preserved untouched in the meantime.
 */
@Composable
fun SortViewTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
