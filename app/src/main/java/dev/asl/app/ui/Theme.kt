package dev.asl.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark, Windows 11-inspired palette (mica-like greys, light-blue accent). Original colours, no Microsoft assets. */
object AslColors {
    val Bg = Color(0xFF1C1C1C)
    val Surface = Color(0xFF2B2B2B)
    val SurfaceHigh = Color(0xFF353535)
    val Border = Color(0xFF3F3F3F)
    val Accent = Color(0xFF60CDFF)
    val OnAccent = Color(0xFF00202E)
    val Text = Color(0xFFF2F2F2)
    val TextDim = Color(0xFFA6A6A6)
    val Good = Color(0xFF6CCB5F)
    val Bad = Color(0xFFFF99A4)
    val Warn = Color(0xFFFCE100)
    val TerminalBg = Color(0xFF0C0C0C)
}

@Composable
fun AslTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AslColors.Accent,
            onPrimary = AslColors.OnAccent,
            secondary = AslColors.Accent,
            background = AslColors.Bg,
            onBackground = AslColors.Text,
            surface = AslColors.Surface,
            onSurface = AslColors.Text,
            surfaceVariant = AslColors.SurfaceHigh,
            onSurfaceVariant = AslColors.TextDim,
            outline = AslColors.Border,
            error = AslColors.Bad,
        ),
        content = content,
    )
}
