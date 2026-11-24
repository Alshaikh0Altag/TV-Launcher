package com.arabrock.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale

object Palette {
    val Background = Color(0xFF0B1118)
    val Panel = Color(0xFF141C26)
    val PanelHigh = Color(0xFF1E2935)
    val Accent = Color(0xFF2DD4BF)
    val Accent2 = Color(0xFF38BDF8)
    val Text = Color(0xFFF1F5F9)
    val Muted = Color(0xFF94A3B8)
    val Danger = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)
}

/**
 * Remote-friendly click handling: OK / Enter = click, holding OK or pressing Menu = long click.
 * Must be followed by focus handling; use [tvFocusable] to get both.
 */
@Composable
fun Modifier.tvFocusable(
    onLongClick: (() -> Unit)? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    var longFired by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { onFocus?.invoke(it.isFocused) }
        .onKeyEvent { e ->
            val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
            when {
                e.key == Key.Menu && onLongClick != null -> {
                    if (e.type == KeyEventType.KeyUp) onLongClick()
                    true
                }
                select && e.type == KeyEventType.KeyDown -> {
                    if (e.nativeKeyEvent.repeatCount > 0 && !longFired && onLongClick != null) {
                        longFired = true
                        onLongClick()
                    }
                    true
                }
                select && e.type == KeyEventType.KeyUp -> {
                    if (!longFired) onClick()
                    longFired = false
                    true
                }
                else -> false
            }
        }
        .focusable()
}

@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    fontSize: TextUnit = 15.sp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> if (danger) Palette.Danger else Palette.Accent
        selected -> Palette.PanelHigh
        else -> Palette.Panel
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(10.dp))
            .border(
                if (selected && !focused) 2.dp else 0.dp,
                if (selected && !focused) Palette.Accent else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .tvFocusable(onLongClick = onLongClick, onFocus = { focused = it }, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (focused) Color.Black else Palette.Text,
            fontSize = fontSize,
            fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AppTile(
    label: String,
    icon: ImageBitmap?,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier
            .background(if (focused) Palette.PanelHigh else Palette.Panel, RoundedCornerShape(12.dp))
            .border(if (focused) 3.dp else 0.dp, if (focused) Palette.Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .tvFocusable(onLongClick = onLongClick, onFocus = { focused = it }, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().size(width = 140.dp, height = 70.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Image(icon, contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(label.take(2), color = Palette.Muted, fontSize = 24.sp)
            }
        }
        Text(
            (if (badge != null) "$badge " else "") + label,
            color = Palette.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun ScreenFrame(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .padding(horizontal = 40.dp, vertical = 24.dp),
    ) {
        Text(title, color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text(subtitle, color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.padding(top = 16.dp).fillMaxSize()) { content() }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = Palette.Accent, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
fun SettingRow(label: String, value: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (focused) Palette.PanelHigh else Palette.Panel, RoundedCornerShape(8.dp))
            .border(if (focused) 2.dp else 0.dp, if (focused) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .tvFocusable(onLongClick = onLongClick, onFocus = { focused = it }, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = if (focused) Palette.Accent else Palette.Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** A row whose value changes with Left/Right on the remote (numbers, offsets, ratios). */
@Composable
fun StepperRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(if (focused) Palette.PanelHigh else Palette.Panel, RoundedCornerShape(8.dp))
            .border(if (focused) 2.dp else 0.dp, if (focused) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Layout is RTL: Left = increase, Right = decrease feels natural to Arabic readers.
                when (e.key) {
                    Key.DirectionLeft -> { onPlus(); true }
                    Key.DirectionRight -> { onMinus(); true }
                    else -> false
                }
            }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Palette.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(if (focused) "◀  $value  ▶" else value, color = if (focused) Palette.Accent else Palette.Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Spacer8() = Box(Modifier.size(8.dp))

fun Dp.half() = this / 2
