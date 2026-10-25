package com.deekshith.droidserve.tv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deekshith.droidserve.tv.ThumbLoader
import kotlinx.coroutines.delay

@Composable
fun DsIcon(vector: ImageVector, modifier: Modifier = Modifier, tint: Color = DsColor.Cream, size: Dp = 22.dp) {
    Icon(vector, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Full-screen Ink surface with overscan-safe margins. */
@Composable
fun DsScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(DsColor.Ink)
            .padding(horizontal = DsDim.ScreenH, vertical = DsDim.ScreenV),
        content = content,
    )
}

/**
 * The single focus language for the whole app: a focused element scales up, lifts on an
 * amber-tinted shadow, swaps to the raised surface, and gains an amber ring. The scale/shadow run
 * purely on a graphics layer (no relayout), and snap instead of spring when the OS asks to reduce
 * motion.
 */
@Composable
fun FocusContainer(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(DsDim.RadiusTile),
    scale: Float = DsDim.FocusScale,
    resting: Color = DsColor.Ink2,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val target = if (focused) scale else 1f
    val animated by animateFloatAsState(target, spring(dampingRatio = 0.72f, stiffness = 430f), label = "focusScale")
    val s = if (LocalReduceMotion.current) target else animated
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .then(if (focused) Modifier.shadow(20.dp, shape, clip = false, spotColor = DsColor.Amber, ambientColor = DsColor.Amber) else Modifier)
            .clip(shape)
            .background(if (focused) DsColor.Ink3 else resting)
            .border(if (focused) 2.5.dp else 1.dp, if (focused) DsColor.Amber else DsColor.Line, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        content = { content(focused) },
    )
}

/** Amber call-to-action. The one filled, highest-emphasis control on a screen. */
@Composable
fun PrimaryButton(label: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val animated by animateFloatAsState(if (focused) 1.05f else 1f, spring(dampingRatio = 0.7f, stiffness = 430f), label = "btn")
    val s = if (LocalReduceMotion.current) (if (focused) 1.05f else 1f) else animated
    val shape = RoundedCornerShape(DsDim.RadiusChip)
    Row(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .then(if (focused) Modifier.shadow(16.dp, shape, clip = false, spotColor = DsColor.Amber, ambientColor = DsColor.Amber) else Modifier)
            .clip(shape)
            .background(DsColor.Amber)
            .border(if (focused) 2.dp else 0.dp, DsColor.Cream, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { DsIcon(icon, tint = DsColor.OnAmber, size = 19.dp); Spacer(Modifier.width(9.dp)) }
        Text(label, style = DsType.Label.copy(color = DsColor.OnAmber))
    }
}

/** Icon+label chip used for top-bar navigation, sort, and toggles. [active] marks a selected state. */
@Composable
fun NavChip(label: String, icon: ImageVector?, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val on = active || focused
    val shape = RoundedCornerShape(DsDim.RadiusChip)
    Row(
        modifier
            .clip(shape)
            .background(if (focused) DsColor.Ink3 else if (active) DsColor.Amber.copy(alpha = 0.12f) else DsColor.Ink2)
            .border(1.dp, if (on) DsColor.Amber else DsColor.Line, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            DsIcon(icon, tint = if (on) DsColor.Amber else DsColor.Muted, size = 18.dp)
            if (label.isNotEmpty()) Spacer(Modifier.width(8.dp))
        }
        if (label.isNotEmpty())
            Text(label, style = DsType.Meta.copy(color = if (on) DsColor.Cream else DsColor.Muted, fontWeight = FontWeight.Medium))
    }
}

@Composable
fun DsTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(DsDim.RadiusChip)
    Box(
        modifier
            .clip(shape)
            .background(DsColor.Ink)
            .border(if (focused) 2.dp else 1.5.dp, if (focused) DsColor.Amber else DsColor.Line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = DsType.Body.copy(color = DsColor.Muted))
        BasicTextField(
            value = value, onValueChange = onChange, interactionSource = interaction, singleLine = true,
            textStyle = DsType.Body, cursorBrush = SolidColor(DsColor.Amber),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Thumbnail tile: paints the cached bitmap instantly, loads off-main, falls back to a type icon. */
@Composable
fun Thumb(url: String?, fallback: ImageVector, modifier: Modifier = Modifier, fallbackTint: Color = DsColor.Muted, iconSize: Dp = 34.dp) {
    var bmp by remember(url) { mutableStateOf(url?.let { ThumbLoader.cached(it) }) }
    LaunchedEffect(url) { if (url != null && bmp == null) bmp = ThumbLoader.load(url) }
    Box(modifier.background(DsColor.Ink2), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DsIcon(fallback, tint = fallbackTint, size = iconSize)
    }
}

/**
 * Immersive backdrop behind the Browse hero: the focused item's artwork, blurred (API 31+) and
 * drawn at low alpha over Ink, then scrimmed to Ink so overlaid text is legible. Crossfades as
 * focus moves. With no artwork it is a quiet amber→ink wash, so an item never looks unfinished.
 */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        Crossfade(targetState = url, animationSpec = tween(360), label = "backdrop") { u ->
            if (u == null) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to DsColor.AmberDeep.copy(alpha = 0.18f),
                            0.5f to DsColor.Ink, 1f to DsColor.Ink,
                        ),
                    ),
                )
            } else {
                var bmp by remember(u) { mutableStateOf(ThumbLoader.cached(u)) }
                LaunchedEffect(u) { if (bmp == null) bmp = ThumbLoader.load(u) }
                val b = bmp
                if (b != null) {
                    Image(
                        b.asImageBitmap(), null,
                        Modifier.fillMaxSize().blur(28.dp),
                        contentScale = ContentScale.Crop, alpha = 0.5f,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(DsColor.Ink))
                }
            }
        }
        Box(Modifier.fillMaxSize().background(scrimBrush()))
    }
}

/** A list row carrying the row focus language (lift + amber left-edge bar). */
@Composable
fun FocusRow(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.(focused: Boolean) -> Unit) {
    FocusContainer(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(DsDim.RadiusTile), scale = DsDim.RowScale) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(4.dp).height(34.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (focused) DsColor.Amber else Color.Transparent),
            )
            Spacer(Modifier.width(14.dp))
            content(focused)
        }
    }
}

@Composable
fun DsLoading(label: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = DsColor.Amber, strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
        if (label != null) { Spacer(Modifier.width(14.dp)); Text(label, style = DsType.Body) }
    }
}

/** Centered state block for empty / error / informational screens. */
@Composable
fun StateMessage(
    icon: ImageVector,
    title: String,
    body: String? = null,
    iconTint: Color = DsColor.Muted,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(DsColor.Ink2)
                .border(1.dp, DsColor.Line, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) { DsIcon(icon, tint = iconTint, size = 34.dp) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = DsType.Section)
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(body, style = DsType.Meta)
        }
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

/** Top bar shared across screens: a display title (with optional lead icon + breadcrumb) and actions. */
@Composable
fun TopBar(
    title: String,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) { DsIcon(leadingIcon, tint = DsColor.Amber, size = 24.dp); Spacer(Modifier.width(12.dp)) }
                Text(title, style = DsType.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle != null) { Spacer(Modifier.height(5.dp)); Text(subtitle, style = DsType.Meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** A small outlined badge, e.g. a "CC" subtitle marker or a codec/quality tag. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = DsType.Caption.copy(color = DsColor.Amber, fontWeight = FontWeight.Bold),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, DsColor.Amber.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Poster/album tile: artwork on top, title + meta below, wrapped in the focus language. */
@Composable
fun MediaTile(
    title: String,
    subtitle: String,
    thumbUrl: String?,
    fallbackIcon: ImageVector,
    aspect: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FocusContainer(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(DsDim.RadiusCard)) { focused ->
        Column {
            Thumb(
                thumbUrl, fallbackIcon,
                Modifier.fillMaxWidth().aspectRatio(aspect),
                fallbackTint = if (focused) DsColor.Amber else DsColor.Muted,
                iconSize = 40.dp,
            )
            Column(Modifier.padding(12.dp)) {
                Text(title, style = DsType.Item.copy(fontSize = 15.sp, lineHeight = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = DsType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Drive the D-pad cursor onto [requester] when a screen (or its content) first appears. Retries
 * briefly: on slower TVs the target composes a frame or two after this effect first runs, so a
 * single requestFocus can land before the target exists. Pass the state keys that define "new
 * content" so focus re-lands after a navigation.
 */
@Composable
fun InitialFocus(requester: FocusRequester, enabled: Boolean, vararg keys: Any?) {
    LaunchedEffect(enabled, *keys) {
        if (!enabled) return@LaunchedEffect
        repeat(12) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(55)
        }
    }
}
