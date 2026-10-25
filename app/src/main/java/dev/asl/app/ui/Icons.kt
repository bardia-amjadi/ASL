package dev.asl.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asl.app.terminal.DistroInfo

/** ">_" prompt. */
val TerminalIcon: ImageVector = ImageVector.Builder("Terminal", 24.dp, 24.dp, 24f, 24f).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(4f, 7f)
        lineTo(10f, 12f)
        lineTo(4f, 17f)
        moveTo(12f, 17f)
        lineTo(20f, 17f)
    }
}.build()

/** Cube, used for the distributions tab. */
val CubeIcon: ImageVector = ImageVector.Builder("Cube", 24.dp, 24.dp, 24f, 24f).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(12f, 3f)
        lineTo(20f, 7.5f)
        lineTo(20f, 16.5f)
        lineTo(12f, 21f)
        lineTo(4f, 16.5f)
        lineTo(4f, 7.5f)
        close()
        moveTo(4f, 7.5f)
        lineTo(12f, 12f)
        lineTo(20f, 7.5f)
        moveTo(12f, 12f)
        lineTo(12f, 21f)
    }
}.build()

/** Small heartbeat/pulse-style icon for diagnostics. */
val PulseIcon: ImageVector = ImageVector.Builder("Pulse", 24.dp, 24.dp, 24f, 24f).apply {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(3f, 12f)
        lineTo(8f, 12f)
        lineTo(10f, 6f)
        lineTo(14f, 18f)
        lineTo(16f, 12f)
        lineTo(21f, 12f)
    }
}.build()

/** Rounded letter badge identifying a distro (D = Debian, A = Alpine). */
@Composable
fun DistroBadge(distro: DistroInfo, size: Dp = 22.dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(Color(distro.colorArgb)),
        contentAlignment = Alignment.Center,
    ) {
        Text(distro.letter, color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold)
    }
}

/** The app mark used in the title bar. */
@Composable
fun AppMark(size: Dp = 24.dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(7.dp)).background(AslColors.Accent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(TerminalIcon, contentDescription = null, tint = AslColors.OnAccent, modifier = Modifier.size(size * 0.72f))
    }
}
