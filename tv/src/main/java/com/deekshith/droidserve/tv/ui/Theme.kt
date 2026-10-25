package com.deekshith.droidserve.tv.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val Hero = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 42.sp, lineHeight = 46.sp, letterSpacing = (-0.6).sp, color = DsColor.Cream)
    val Title = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp, color = DsColor.Cream)
    val Section = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, color = DsColor.Cream)
    val Item = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 23.sp, color = DsColor.Cream)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = DsColor.Cream)
    val Meta = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, color = DsColor.Muted)
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = DsColor.Muted)
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = DsColor.Cream)
    val Label = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.2.sp, color = DsColor.Cream)
}

object DsDim {
    val ScreenH = 48.dp              // overscan-safe horizontal margin
    val ScreenV = 28.dp              // overscan-safe vertical margin
    val Gutter = 14.dp
    val RadiusCard = 16.dp
    val RadiusTile = 12.dp
    val RadiusChip = 10.dp
    val PosterMin = 158.dp
    val FocusScale = 1.08f
    val RowScale = 1.025f
}

/** Vertical scrim that fades a backdrop image down into [DsColor.Ink] so overlaid text stays legible. */
fun scrimBrush(top: Float = 0.35f): Brush = Brush.verticalGradient(
    0f to DsColor.Ink.copy(alpha = top),
    0.55f to DsColor.Ink.copy(alpha = 0.82f),
    1f to DsColor.Ink,
)

/** True when the OS "remove animations" setting is on; focus transitions then snap instead of spring. */
val LocalReduceMotion = staticCompositionLocalOf { false }
