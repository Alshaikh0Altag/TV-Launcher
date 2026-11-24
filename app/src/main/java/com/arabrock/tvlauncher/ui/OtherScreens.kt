package com.arabrock.tvlauncher.ui

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arabrock.tvlauncher.LauncherController
import com.arabrock.tvlauncher.Screen
import com.arabrock.tvlauncher.data.AppEntry
import com.arabrock.tvlauncher.prayer.PrayerEngine
import com.arabrock.tvlauncher.prayer.PrayerName
import com.arabrock.tvlauncher.window.LayoutType
import com.arabrock.tvlauncher.window.WindowController
import com.arabrock.tvlauncher.window.WorkspaceLayout
import com.arabrock.tvlauncher.workspace.Profile
import com.arabrock.tvlauncher.workspace.Workspace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

// ============================ Layout selection ============================

@Composable
fun LayoutScreen(c: LauncherController) {
    val ws by c.workspaces.current.collectAsState()
    var draft by remember { mutableStateOf(ws.layout) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    ScreenFrame("تخطيط الشاشة", "اختر التخطيط، ثم حرّك الفواصل بالسهمين يمين/يسار، ثم اضغط «تطبيق»") {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Column(Modifier.width(380.dp)) {
                LayoutType.values().forEachIndexed { i, t ->
                    val m = if (i == 0) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth()
                    TvButton("${t.label} (${t.slots})", m.padding(vertical = 3.dp), selected = draft.type == t) {
                        draft = draft.copy(type = t)
                    }
                }
                SectionTitle("الفواصل القابلة للتعديل")
                if (draft.type != LayoutType.SINGLE && draft.type != LayoutType.DUAL_HORIZONTAL) {
                    StepperRow("الفاصل العمودي", "${(draft.splitX * 100).toInt()}%",
                        onMinus = { draft = draft.copy(splitX = (draft.splitX - 0.05f).coerceIn(0.2f, 0.8f)) },
                        onPlus = { draft = draft.copy(splitX = (draft.splitX + 0.05f).coerceIn(0.2f, 0.8f)) })
                }
                if (draft.type == LayoutType.QUAD || draft.type == LayoutType.TRIPLE || draft.type == LayoutType.DUAL_HORIZONTAL) {
                    StepperRow("الفاصل الأفقي", "${(draft.splitY * 100).toInt()}%",
                        onMinus = { draft = draft.copy(splitY = (draft.splitY - 0.05f).coerceIn(0.2f, 0.8f)) },
                        onPlus = { draft = draft.copy(splitY = (draft.splitY + 0.05f).coerceIn(0.2f, 0.8f)) })
                }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("تطبيق") {
                        c.goHome()
                        c.maximizedSlot = null
                        c.applyWorkspace(ws.copy(layout = draft))
                    }
                    TvButton("إعادة الفواصل للمنتصف") { draft = draft.copy(splitX = 0.5f, splitY = 0.5f) }
                    TvButton("إلغاء") { c.goHome() }
                }
            }
            LayoutPreview(draft, ws, c, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LayoutPreview(layout: WorkspaceLayout, ws: Workspace, c: LauncherController, modifier: Modifier) {
    Box(modifier.padding(top = 10.dp)) {
        val w = 480; val h = 270
        val rects = layout.copy(gapPx = 4).rects(android.graphics.Rect(0, 0, w, h))
        Box(Modifier.size(w.dp, h.dp).background(Color.Black, RoundedCornerShape(6.dp))) {
            rects.forEachIndexed { i, r ->
                val label = ws.slot(i)?.let { p -> c.apps.find(p)?.label } ?: "فارغة"
                Box(
                    Modifier.absoluteOffset(r.left.dp, r.top.dp).size(r.width().dp, r.height().dp)
                        .background(Palette.PanelHigh, RoundedCornerShape(4.dp))
                        .border(1.dp, Palette.Accent, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("${i + 1}\n$label", color = Palette.Text, fontSize = 13.sp) }
            }
        }
    }
}

// ============================ Workspace profiles ============================

@Composable
fun ProfilesScreen(c: LauncherController) {
    val profiles by c.workspaces.profiles.collectAsState()
    val current by c.workspaces.current.collectAsState()
    var newName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Profile?>(null) }
    var renameText by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(selected) { delay(80); runCatching { first.requestFocus() } }

    fun describe(ws: Workspace) = ws.layout.type.label + " — " +
        (0 until ws.layout.type.slots).joinToString("، ") { i -> ws.slot(i)?.let { p -> c.apps.find(p)?.label ?: p } ?: "فارغة" }

    val sel = selected
    if (sel != null) {
        ScreenFrame("مساحة العمل: ${sel.name}", describe(sel.workspace)) {
            Column {
                TvButton("فتح مساحة العمل هذه", Modifier.focusRequester(first)) {
                    c.goHome(); c.maximizedSlot = null; c.applyWorkspace(sel.workspace)
                }
                Spacer8()
                TvButton("استبدالها بالنوافذ الحالية") { c.workspaces.overwriteProfile(sel.id, current); selected = null; c.toast("تم الحفظ") }
                Spacer8()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NameField(renameText) { renameText = it }
                    TvButton("إعادة التسمية") {
                        if (renameText.isNotBlank()) { c.workspaces.renameProfile(sel.id, renameText.trim()); selected = null }
                    }
                }
                Spacer8()
                TvButton("حذف (يتطلب الرمز)", danger = true) {
                    c.requirePin {
                        c.workspaces.deleteProfile(sel.id)
                        c.navigate(Screen.Profiles)
                    }
                }
                Spacer8()
                TvButton("رجوع") { selected = null }
            }
        }
        return
    }

    ScreenFrame("مساحات العمل المحفوظة", "OK لاختيار مساحة • احفظ النوافذ الحالية باسم جديد") {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("رجوع", Modifier.focusRequester(first)) { c.goHome() }
                    NameField(newName) { newName = it }
                    TvButton("حفظ الحالية") {
                        val name = newName.trim().ifEmpty { "مساحة ${profiles.size + 1}" }
                        c.workspaces.saveProfile(name, current)
                        newName = ""
                        c.toast("تم حفظ «$name»")
                    }
                }
            }
            item { SectionTitle("الحالية: " + describe(current)) }
            if (profiles.isEmpty()) item { Text("لا توجد مساحات محفوظة بعد.", color = Palette.Muted, fontSize = 15.sp) }
            items(profiles, key = { it.id }) { p ->
                SettingRow(p.name, describe(p.workspace)) { renameText = p.name; selected = p }
            }
        }
    }
}

@Composable
private fun NameField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.take(30)) },
        singleLine = true,
        textStyle = TextStyle(color = Palette.Text, fontSize = 17.sp),
        modifier = Modifier
            .width(300.dp)
            .background(Palette.Panel, RoundedCornerShape(8.dp))
            .border(1.dp, Palette.PanelHigh, RoundedCornerShape(8.dp))
            .padding(10.dp),
    )
}

// ============================ Window menu ============================

@Composable
fun WindowMenu(c: LauncherController, slot: Int) {
    val ws by c.workspaces.current.collectAsState()
    val pkg = ws.slot(slot)
    val app = pkg?.let { c.apps.find(it) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp).background(Palette.Background, RoundedCornerShape(14.dp))
                .border(2.dp, Palette.Accent, RoundedCornerShape(14.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("النافذة ${slot + 1}" + (app?.let { " — ${it.label}" } ?: ""), color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            val full = Modifier.fillMaxWidth()
            if (app != null) {
                TvButton("التحكم بهذه النافذة", full.focusRequester(first)) { c.goHome(); c.focusSlot(slot) }
                TvButton("فتح / إعادة فتح", full) { c.goHome(); c.openSlot(slot) }
                TvButton(if (c.maximizedSlot == slot) "استعادة الحجم" else "تكبير", full) { c.goHome(); c.toggleMaximize(slot) }
                TvButton("إغلاق النافذة", full) { c.goHome(); c.closeSlot(slot) }
            }
            TvButton(if (app == null) "اختيار تطبيق" else "تغيير التطبيق", if (app == null) full.focusRequester(first) else full) {
                openAssign(c, slot)
            }
            if (app != null) TvButton("إفراغ النافذة", full) {
                if (c.settings.value.protectAssignments) c.requirePin { c.assign(slot, null) } else { c.goHome(); c.assign(slot, null) }
            }
            TvButton("رجوع", full) { c.goHome() }
        }
    }
}

// ============================ Prayer full screen ============================

@Composable
fun PrayerFullScreen(c: LauncherController) {
    val settings by c.settings.state.collectAsState()
    val now = rememberNow(c)
    val cfg = settings.prayer
    val status = remember(now / 30_000, cfg) { PrayerEngine.status(cfg, now) }
    val today = Instant.ofEpochMilli(now).atZone(PrayerEngine.zone(cfg)).toLocalDate()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
    val left = ((status.nextAt - now) / 1000).coerceAtLeast(0)

    Column(
        Modifier.fillMaxSize().background(Palette.Background).padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(cfg.cityName + "  ·  " + (PrayerEngine.hijriText(cfg, today) ?: ""), color = Palette.Muted, fontSize = 20.sp)
        Text("المتبقي لصلاة ${status.next.arabic}", color = Palette.Text, fontSize = 26.sp, modifier = Modifier.padding(top = 20.dp))
        Text("%02d:%02d:%02d".format(left / 3600, (left % 3600) / 60, left % 60), color = Palette.Accent, fontSize = 72.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (p in PrayerName.values()) {
                val upcoming = p == status.next && status.nextAt == status.today.at(p)
                Column(
                    Modifier.background(if (upcoming) Palette.Accent.copy(alpha = 0.2f) else Palette.Panel, RoundedCornerShape(12.dp))
                        .border(if (upcoming) 3.dp else 0.dp, if (upcoming) Palette.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(p.arabic, color = if (upcoming) Palette.Accent else Palette.Muted, fontSize = 20.sp)
                    Text(PrayerEngine.formatTime(status.today.at(p), cfg), color = Palette.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        TvButton("رجوع", Modifier.padding(top = 30.dp).focusRequester(first)) { c.goHome() }
    }
}

// ============================ Diagnostics & compatibility tests ============================

private data class TestResult(val name: String, val auto: String, var verdict: String)

@Composable
fun DiagnosticsScreen(c: LauncherController) {
    val ctx = c.activity
    val apps by c.apps.apps.collectAsState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var testApp by remember { mutableStateOf(apps.firstOrNull { it.packageName == "com.google.android.youtube.tv" } ?: apps.firstOrNull()) }
    var pending by remember { mutableStateOf<TestResult?>(null) }
    val results = remember { mutableStateListOf<TestResult>() }
    var savedWs by remember { mutableStateOf<Workspace?>(null) }
    LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }

    val ff = WindowController.freeformStatus(ctx)
    val overlay = Settings.canDrawOverlays(ctx)
    val hiddenPolicy = Settings.Global.getString(ctx.contentResolver, "hidden_api_policy") ?: "افتراضي"
    val home = ctx.packageManager.resolveActivity(
        android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
    )?.activityInfo?.packageName

    fun runTest(name: String, slots: List<AppEntry?>, layout: LayoutType) {
        val app0 = slots.firstOrNull() ?: return
        if (savedWs == null) savedWs = c.workspaces.current.value
        c.keepScreenOnHome = true
        val ws = Workspace(WorkspaceLayout(layout), (0 until 4).map { slots.getOrNull(it)?.packageName })
        c.closeAll()
        c.workspaces.setCurrent(ws)
        scope.launch {
            delay(500)
            c.openAll()
            delay(450L * slots.size + 3000)
            val checks = slots.indices.map { WindowController.inspect(ctx, it) }
            val inside = checks.count { it.alive && (it.direct || (it.activities > 1)) }
            val auto = "مهام تعمل: ${checks.count { it.alive }}/${slots.size} • التطبيق داخل نافذته: $inside/${slots.size}" +
                if (!app0.canBeHosted) " • هذا التطبيق يرفض الاستضافة (singleTask)" else ""
            pending = TestResult(name, auto, "")
        }
    }

    ScreenFrame("التشخيص والتوافق", "نتائج الفحص الفعلي على هذا التلفاز — لا تُعتبر النتيجة ناجحة إلا إذا ظهرت النوافذ فعلاً معاً") {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("رجوع", Modifier.focusRequester(first)) { c.goHome() }
                    if (savedWs != null) TvButton("استعادة مساحة العمل السابقة") {
                        savedWs?.let { c.applyWorkspace(it) }; savedWs = null; c.goHome()
                    }
                }
            }
            item { SectionTitle("النظام") }
            item { SettingRow("الجهاز / أندرويد", "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})") {} }
            item { SettingRow("ميزة النوافذ الحرة مدعومة رسمياً", if (ff.featureDeclared) "نعم" else "لا") {} }
            item { SettingRow("enable_freeform_support (ADB)", if (ff.freeformSetting) "مفعّل ✅" else "غير مفعّل ❌") {} }
            item { SettingRow("force_resizable_activities (ADB)", if (ff.forceResizable) "مفعّل ✅" else "غير مفعّل ❌") {} }
            item { SettingRow("واجهة setLaunchWindowingMode", if (ff.windowingModeApi) "متاحة ✅" else "محجوبة ❌ (hidden_api_policy)") {} }
            item { SettingRow("hidden_api_policy", hiddenPolicy) {} }
            item { SettingRow("الرسم فوق التطبيقات (SYSTEM_ALERT_WINDOW)", if (overlay) "ممنوح" else "غير ممنوح") {} }
            item { SettingRow("اللانشر الافتراضي", if (home == ctx.packageName) "هذا اللانشر ✅" else (home ?: "غير محدد")) {} }
            item { SettingRow("النتيجة", if (ff.usable) "النوافذ المتعددة متاحة على هذا الجهاز" else "غير متاحة — نفّذ أوامر ADB من الإعدادات") {} }

            item { SectionTitle("اختبارات التوافق") }
            item {
                SettingRow("تطبيق الاختبار", testApp?.let { it.label + if (it.canBeHosted) "" else " (singleTask)" } ?: "لا يوجد") {
                    if (apps.isNotEmpty()) {
                        val idx = testApp?.let { apps.indexOf(it) } ?: -1
                        testApp = apps[(idx + 1).mod(apps.size)]
                    }
                }
            }
            val app = testApp
            val other = apps.firstOrNull { it.packageName != app?.packageName }
            if (app != null) {
                item { SettingRow("اختبار A: فتح التطبيق في نافذة", "تشغيل") { runTest("A: نافذة واحدة", listOf(app), LayoutType.SINGLE) } }
                item { SettingRow("اختبار B/C: نسختان من نفس التطبيق معاً", "تشغيل") { runTest("B/C: نسختان", listOf(app, app), LayoutType.DUAL_VERTICAL) } }
                item { SettingRow("اختبار D: أربع نسخ من نفس التطبيق", "تشغيل") { runTest("D: أربع نسخ", listOf(app, app, app, app), LayoutType.QUAD) } }
                if (other != null) item { SettingRow("اختبار E: تطبيقان مختلفان (${other.label})", "تشغيل") { runTest("E: تطبيقان مختلفان", listOf(app, other), LayoutType.DUAL_VERTICAL) } }
                item {
                    SettingRow("اختبار F: تبديل التركيز بين النوافذ", "تشغيل") {
                        c.keepScreenOnHome = true
                        scope.launch {
                            c.focusSlot(1); delay(1500); c.focusSlot(0); delay(1500)
                            pending = TestResult("F: تبديل التركيز", "تم إرسال أوامر التركيز للنافذة 2 ثم 1", "")
                        }
                    }
                }
                item { SettingRow("اختبار H: الاستعادة بعد إعادة التشغيل", "يدوي") { c.toast("فعّل «استعادة آخر مساحة عمل» ثم أعد تشغيل التلفاز وتحقق") } }
            }
            pending?.let { p ->
                item {
                    Column(Modifier.padding(vertical = 10.dp).background(Palette.PanelHigh, RoundedCornerShape(10.dp)).padding(14.dp)) {
                        Text("${p.name}: ${p.auto}", color = Palette.Text, fontSize = 15.sp)
                        Text("انظر إلى الشاشة، ثم اضغط Home للعودة إلى هنا. هل ظهرت كل النوافذ في نفس الوقت وتعمل؟", color = Palette.Warning, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvButton("نعم كلها") { results.add(p.copy(verdict = "ناجح ✅")); pending = null }
                            TvButton("بعضها") { results.add(p.copy(verdict = "دعم جزئي ⚠️")); pending = null }
                            TvButton("لا") { results.add(p.copy(verdict = "غير مدعوم ❌")); pending = null }
                        }
                    }
                }
            }
            if (results.isNotEmpty()) item { SectionTitle("النتائج") }
            items(results.toList()) { r -> SettingRow(r.name + " — " + r.auto, r.verdict) {} }

            item { SectionTitle("توافق التطبيقات المثبتة") }
            items(apps) { a ->
                SettingRow(a.label, if (a.canBeHosted) "يمكن فتح أكثر من نسخة (مستضاف)" else "نسخة واحدة غالباً (singleTask)") {}
            }
        }
    }
}
