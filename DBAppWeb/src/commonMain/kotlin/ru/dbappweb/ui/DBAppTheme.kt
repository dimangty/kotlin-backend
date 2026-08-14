package ru.dbappweb.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра напоминает цвета psql: глубокий синий, бирюзовый акцент и тёплый фон терминала.
private val DbAppColors = lightColorScheme(
    primary = Color(0xFF2456A6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0A2D5F),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8F4EA),
    onSecondaryContainer = Color(0xFF00372F),
    tertiary = Color(0xFF8A4F08),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1B1C20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C20),
    surfaceVariant = Color(0xFFE9EDF5),
    onSurfaceVariant = Color(0xFF454A55),
    error = Color(0xFFBA1A1A),
)

/** Единая тема не даёт отдельным экранам расходиться по цветам и типографике. */
@Composable
fun DBAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DbAppColors,
        content = content,
    )
}
