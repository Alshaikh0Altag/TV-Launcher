package com.arabrock.tvlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arabrock.tvlauncher.LauncherController
import com.arabrock.tvlauncher.Screen
import com.arabrock.tvlauncher.prayer.PrayerEngine
import com.arabrock.tvlauncher.prayer.PrayerName
import com.arabrock.tvlauncher.update.Updater
import com.arabrock.tvlauncher.window.WindowController
import com.arabrock.tvlauncher.window.WindowState
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LauncherRoot(c: LauncherController) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        when (val s = c.screen) {
            is Screen.Home -> HomeScreen(c)
            is Screen.Library -> LibraryScreen(c, s.assignSlot)
            is Screen.Layout -> LayoutScreen(c)
            is Screen.Profiles -> ProfilesScreen(c)
            is Screen.Settings -> SettingsScreen(c)
            is Screen.Pin -> PinScreen(s.mode, onSuccess = { c.screen = Screen.Home; s.then() }, onCancel = { c.goHome() })
            is Screen.Diagnostics -> DiagnosticsScreen(c)
            is Screen.PrayerFull -> PrayerFullScreen(c)
            is Screen.WindowMenu -> {
                HomeScreen(c, menuOpen = true)
                WindowMenu(c, s.slot)
            }
        }
        c.message?.let { msg ->
            Box(Modifier.fillMaxSize().padding(bottom = 90.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    msg, color = Palette.Text, fontSize = 16.sp,
                    modifier = Modifier.background(Color(0xEE1E2935), RoundedCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** Ticks once per second while the launcher is visible (no background polling). */
@Composable
fun rememberNow(c: LauncherController): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.visible) {
        while (c.visible) {
            now = System.currentTimeMillis()
            delay(1000 - (System.currentTimeMillis() % 1000))
        }
    }
    return now
}

@Composable
fun HomeScreen(c: LauncherController, menuOpen: Boolean = false) {
    val settings by c.settings.state.collectAsState()
    val ws by c.workspaces.current.collectAsState()
    val states by WindowController.states.collectAsState()
    val appList by c.apps.apps.collectAsState()
    val error by WindowController.lastError.collectAsState()
    val update by Updater.state.collectAsState()
    val now = rememberNow(c)
    val density = LocalDensity.current
    val screen = c.screenRect()
    fun pxToDp(px: Int) = with(density) { px.toDp() }

    val topH = pxToDp((screen.height() * c.topBarFraction).toInt())
    val bottomH = pxToDp((screen.height() * c.bottomBarFraction).toInt())
    val firstSlot = remember { FocusRequester() }

    LaunchedEffect(error) {
        if (error != null) { c.toast(error!!); WindowController.reportError(null) }
    }
    LaunchedEffect(menuOpen) {
        if (!menuOpen) { delay(100); runCatching { firstSlot.requestFocus() } }
    }
    LaunchedEffect(now / 5000) { c.refreshWindowStates() }

    val anyRunning = states.values.any { it == WindowState.HOSTED || it == WindowState.DIRECT || it == WindowState.ESCAPED }

    Box(Modifier.fillMaxSize().background(if (anyRunning) Color.Transparent else Palette.Background)) {
        // ---------------- Windows ----------------
        val count = ws.layout.type.slots
        for (i in 0 until count) {
            if (c.maximizedSlot != null && c.maximizedSlot != i) continue
            key(i) { SlotView(c, i, ws, states, appList, firstSlot) }
        }

        // ---------------- Top bar ----------------
        val zone = PrayerEngine.zone(settings.prayer)
        val zdt = Instant.ofEpochMilli(now).atZone(zone)
        val today = zdt.toLocalDate()
        Row(
            Modifier.fillMaxWidth().height(topH).background(Palette.Background.copy(alpha = 0.96f)).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("%02d:%02d".format(zdt.hour, zdt.minute), color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(today.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale("ar"))), color = Palette.Text, fontSize = 13.sp)
                Text(
                    (PrayerEngine.hijriText(settings.prayer, today) ?: "") + "  ·  " + settings.prayer.cityName,
                    color = Palette.Muted, fontSize = 12.sp,
                )
            }
            Box(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvButton("فتح الكل", fontSize = 13.sp) { c.openAll() }
                TvButton("إغلاق الكل", fontSize = 13.sp) { c.closeAll() }
                TvButton(ws.layout.type.label, fontSize = 13.sp) { c.requirePin { c.navigate(Screen.Layout) } }
                TvButton("التطبيقات", fontSize = 13.sp) { c.navigate(Screen.Library(null)) }
                TvButton("مساحات العمل", fontSize = 13.sp) { c.navigate(Screen.Profiles) }
            }
        }

        // ---------------- Bottom bar ----------------
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomH)
                .background(Palette.Background.copy(alpha = 0.96f)).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (settings.prayer.showOnHome) {
                val status = remember(now / 30_000, settings.prayer) { PrayerEngine.status(settings.prayer, now) }
                Row(Modifier.weight(1f).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in PrayerName.values()) {
                        val upcoming = p == status.next && status.today.date == today && status.nextAt == status.today.at(p)
                        Column(
                            Modifier
                                .background(if (upcoming) Palette.Accent.copy(alpha = 0.22f) else Palette.Panel, RoundedCornerShape(8.dp))
                                .border(if (upcoming) 2.dp else 0.dp, if (upcoming) Palette.Accent else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(p.arabic, color = if (upcoming) Palette.Accent else Palette.Muted, fontSize = 12.sp)
                            Text(PrayerEngine.formatTime(status.today.at(p), settings.prayer), color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    val left = ((status.nextAt - now) / 1000).coerceAtLeast(0)
                    Column(Modifier.padding(horizontal = 14.dp)) {
                        Text("المتبقي لصلاة ${status.next.arabic}", color = Palette.Muted, fontSize = 12.sp)
                        Text("%02d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60), color = Palette.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                Box(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update.availableBuild != null) {
                    TvButton("تحديث متوفر", fontSize = 13.sp, selected = true) { c.requirePin { c.navigate(Screen.Settings) } }
                }
                if (settings.prayer.showOnHome) TvButton("الصلاة", fontSize = 13.sp) { c.navigate(Screen.PrayerFull) }
                TvButton("⚙ الإعدادات", fontSize = 13.sp) { c.requirePin { c.navigate(Screen.Settings) } }
            }
        }
    }
}

fun openAssign(c: LauncherController, slot: Int) {
    if (c.settings.value.protectAssignments) c.requirePin { c.navigate(Screen.Library(slot)) }
    else c.navigate(Screen.Library(slot))
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))

@Composable
private fun SlotView(
    c: LauncherController,
    i: Int,
    ws: com.arabrock.tvlauncher.workspace.Workspace,
    states: Map<Int, WindowState>,
    appList: List<com.arabrock.tvlauncher.data.AppEntry>,
    firstSlot: FocusRequester,
) {
    val density = LocalDensity.current
    fun pxToDp(px: Int) = with(density) { px.toDp() }
    val r = c.slotRect(i, ws)
    val pkg = ws.slot(i)
    val app = pkg?.let { p -> appList.firstOrNull { it.packageName == p } }
    val st = states[i] ?: if (pkg == null) WindowState.EMPTY else WindowState.NOT_RUNNING
    val running = st == WindowState.HOSTED || st == WindowState.DIRECT || st == WindowState.ESCAPED
    var focused by remember { mutableStateOf(false) }
    // absoluteOffset: window geometry is in screen pixels and must not mirror in RTL.
    Box(
        Modifier
            .absoluteOffset(pxToDp(r.left), pxToDp(r.top))
            .size(pxToDp(r.width()), pxToDp(r.height()))
            .background(if (running) Color.Transparent else Palette.Panel, RoundedCornerShape(6.dp))
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) Palette.Accent else Palette.PanelHigh,
                RoundedCornerShape(6.dp),
            )
            .let { if (i == 0) it.focusRequester(firstSlot) else it }
            .tvFocusable(
                onLongClick = { c.navigate(Screen.WindowMenu(i)) },
                onFocus = { focused = it },
            ) {
                when {
                    pkg == null -> openAssign(c, i)
                    running -> c.focusSlot(i)
                    else -> c.openSlot(i)
                }
            },
    ) {
        val label = "${i + 1}" + (if (app != null) " · ${app.label}" else "")
        Text(
            label + if (focused && running) "  (OK = تحكم، مطوّل = قائمة)" else "",
            color = Palette.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .background(Color(0xCC0B1118), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        if (!running) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        pkg == null -> "＋ اختر تطبيقاً"
                        app == null -> "التطبيق غير مثبت"
                        st == WindowState.FAILED -> "تعذر الفتح — OK لإعادة المحاولة"
                        else -> "OK لفتح ${app.label}"
                    },
                    color = if (focused) Palette.Accent else Palette.Muted, fontSize = 18.sp,
                )
                if (focused) Text("اضغط مطوّلاً على OK أو زر القائمة لخيارات النافذة", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
