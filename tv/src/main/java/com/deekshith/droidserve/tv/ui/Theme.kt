package com.deekshith.droidserve.tv.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deekshith.droidserve.tv.R

/**
 * DroidServe TV design system — "Cinema": warm near-black with a single marquee-gold accent.
 * The accent is spent only on the focus cursor and the one thing that matters on a screen, never
 * as decoration. All tokens live here so every screen reads as one system.
 */
object DsColor {
    val Ink = Color(0xFF0B0A0D)      // base / background (warm near-black)
    val Ink2 = Color(0xFF141218)     // raised surface (bars, resting tiles)
    val Ink3 = Color(0xFF221C27)     // focused / elevated surface
    val Line = Color(0xFF2E2834)     // warm hairline
    val Amber = Color(0xFFF2B84B)    // accent — focus + key marks ONLY
    val AmberDeep = Color(0xFFC8882E) // pressed / gradient foot
    val Cream = Color(0xFFF6EFE4)    // primary text (warm off-white)
    val Muted = Color(0xFF9A9080)    // secondary text / metadata
    val Coral = Color(0xFFE86A5C)    // errors
    val OnAmber = Color(0xFF231703)  // text on an amber fill
}

/** One display family, two weights. Body/metadata uses the device sans (left unset → system). */
val Bricolage = FontFamily(
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
)

object DsType {
    // Compact scale: TV renders at 960dp wide (1920/xhdpi) upscaled to 4K, so modest sp/dp keep
    // the 10-foot UI dense and legible rather than looking zoomed-in.
    val Hero = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp, lineHeight = 33.sp, letterSpacing = (-0.5).sp, color = DsColor.Cream)
    val Title = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp, color = DsColor.Cream)
    val Section = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp, color = DsColor.Cream)
    val Item = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, color = DsColor.Cream)
    val Body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = DsColor.Cream)
    val Meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = DsColor.Muted)
    val Caption = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, color = DsColor.Muted)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = DsColor.Cream)
    val Label = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, letterSpacing = 0.2.sp, color = DsColor.Cream)
}

object DsDim {
    val ScreenH = 36.dp              // overscan-safe horizontal margin
    val ScreenV = 22.dp              // overscan-safe vertical margin
    val Gutter = 12.dp
    val RadiusCard = 12.dp
    val RadiusTile = 10.dp
    val RadiusChip = 9.dp
    val PosterMin = 128.dp          // square album tiles
    val TileMin = 168.dp            // landscape browse tiles (suit 16:9 video thumbnails)
    val FocusScale = 1.05f   // gentle tile zoom; growth is absorbed by grid contentPadding
    val RowScale = 1f        // full-width rows don't zoom — they use the amber bar + ring instead
}

/** Vertical scrim that fades a backdrop image down into [DsColor.Ink] so overlaid text stays legible. */
fun scrimBrush(top: Float = 0.35f): Brush = Brush.verticalGradient(
    0f to DsColor.Ink.copy(alpha = top),
    0.55f to DsColor.Ink.copy(alpha = 0.82f),
    1f to DsColor.Ink,
)

/** True when the OS "remove animations" setting is on; focus transitions then snap instead of spring. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** App-wide grid density (0 cozy · 1 compact · 2 dense). Toggled on Browse, applied to every grid. */
val GridDensity = mutableIntStateOf(1)

fun densityTileMin(level: Int): Dp = when (level) { 0 -> 196.dp; 2 -> 132.dp; else -> 164.dp }
fun densityLabel(level: Int): String = when (level) { 0 -> "Cozy"; 2 -> "Dense"; else -> "Compact" }
