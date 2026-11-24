package com.arabrock.tvlauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arabrock.tvlauncher.LauncherController
import com.arabrock.tvlauncher.Screen
import com.arabrock.tvlauncher.data.LaunchMode
import com.arabrock.tvlauncher.data.PrayerConfig
import com.arabrock.tvlauncher.prayer.CalcMethod
import com.arabrock.tvlauncher.prayer.Cities
import com.arabrock.tvlauncher.prayer.HighLatRule
import com.arabrock.tvlauncher.prayer.PrayerAlarmReceiver
import com.arabrock.tvlauncher.prayer.PrayerName
import com.arabrock.tvlauncher.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

private val timeZones = listOf(
    "Asia/Riyadh", "Asia/Dubai", "Asia/Kuwait", "Asia/Qatar", "Asia/Bahrain", "Asia/Muscat",
    "Asia/Amman", "Asia/Baghdad", "Africa/Cairo", "Europe/Istanbul", "Europe/London", "America/New_York",
)

private fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]

@Composable
fun SettingsScreen(c: LauncherController) {
    val s by c.settings.state.collectAsState()
    val upd by Updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    var cityPicker by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    // Auto-lock: leave settings when the unlock timeout expires.
    LaunchedEffect(Unit) {
        delay(80); runCatching { first.requestFocus() }
        while (true) {
            delay(2000)
            if (!c.pins.isUnlocked()) { c.goHome(); break }
        }
    }

    fun set(transform: (com.arabrock.tvlauncher.data.Settings) -> com.arabrock.tvlauncher.data.Settings) {
        c.pins.touch(s.lockTimeoutSec)
        c.settings.update(transform)
    }
    fun setPrayer(transform: (PrayerConfig) -> PrayerConfig) {
        set { it.copy(prayer = transform(it.prayer)) }
        PrayerAlarmReceiver.schedule(c.activity)
    }
    fun onOff(b: Boolean) = if (b) "مفعّل" else "متوقف"

    if (cityPicker) {
        ScreenFrame("اختر المدينة", "تُضبط المنطقة الزمنية وطريقة الحساب المناسبة تلقائياً ويمكن تغييرها بعد ذلك") {
            val pickFirst = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(80); runCatching { pickFirst.requestFocus() } }
            LazyColumn(Modifier.fillMaxSize()) {
                items(Cities.all) { city ->
                    val mod = if (city == Cities.all.first()) Modifier.focusRequester(pickFirst) else Modifier
                    Row(mod) {
                        SettingRow(city.name, "%.3f , %.3f".format(city.lat, city.lng)) {
                            setPrayer { it.copy(cityName = city.name, latitude = city.lat, longitude = city.lng, timeZoneId = city.tz, method = city.method, useDeviceLocation = false) }
                            cityPicker = false
                        }
                    }
                }
            }
        }
        return
    }

    ScreenFrame("الإعدادات", "محمية برمز المسؤول • تُقفل تلقائياً عند الخروج أو بعد ${s.lockTimeoutSec} ثانية من عدم الاستخدام") {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("رجوع", Modifier.focusRequester(first)) { c.goHome() }
                    TvButton("التشخيص والتوافق") { c.navigate(Screen.Diagnostics) }
                    TvButton("قفل الآن") { c.pins.lock(); c.goHome() }
                }
            }

            item { SectionTitle("النوافذ") }
            item {
                SettingRow("طريقة فتح النوافذ", if (s.launchMode == LaunchMode.HOSTED) "مستضافة (يُنصح بها)" else "مباشرة") {
                    set { it.copy(launchMode = if (it.launchMode == LaunchMode.HOSTED) LaunchMode.DIRECT else LaunchMode.HOSTED) }
                }
            }
            item { SettingRow("ترك مساحة للشريط العلوي والسفلي", onOff(s.reserveBars)) { set { it.copy(reserveBars = !it.reserveBars) } } }
            item { SettingRow("استعادة آخر مساحة عمل عند التشغيل", onOff(s.autoRestore)) { set { it.copy(autoRestore = !it.autoRestore) } } }
            item { SettingRow("طلب الرمز عند تغيير تطبيقات النوافذ", onOff(s.protectAssignments)) { set { it.copy(protectAssignments = !it.protectAssignments) } } }

            item { SectionTitle("أوقات الصلاة") }
            item { SettingRow("المدينة", s.prayer.cityName) { cityPicker = true } }
            item { SettingRow("استخدام موقع التلفاز (إن توفر)", "تحديث") { c.activity.requestLocation() } }
            item {
                StepperRow("خط العرض", "%.3f".format(s.prayer.latitude),
                    onMinus = { setPrayer { it.copy(latitude = (it.latitude - 0.01).coerceIn(-89.0, 89.0), cityName = "موقع يدوي") } },
                    onPlus = { setPrayer { it.copy(latitude = (it.latitude + 0.01).coerceIn(-89.0, 89.0), cityName = "موقع يدوي") } })
            }
            item {
                StepperRow("خط الطول", "%.3f".format(s.prayer.longitude),
                    onMinus = { setPrayer { it.copy(longitude = (it.longitude - 0.01).coerceIn(-180.0, 180.0), cityName = "موقع يدوي") } },
                    onPlus = { setPrayer { it.copy(longitude = (it.longitude + 0.01).coerceIn(-180.0, 180.0), cityName = "موقع يدوي") } })
            }
            item {
                val tzList = (timeZones + ZoneId.systemDefault().id).distinct()
                SettingRow("المنطقة الزمنية", s.prayer.timeZoneId) {
                    setPrayer { it.copy(timeZoneId = tzList.after(it.timeZoneId)) }
                }
            }
            item {
                val method = runCatching { CalcMethod.valueOf(s.prayer.method) }.getOrDefault(CalcMethod.UMM_AL_QURA)
                SettingRow("طريقة الحساب", method.label) {
                    setPrayer { it.copy(method = CalcMethod.values().toList().after(method).name) }
                }
            }
            item { SettingRow("حساب العصر", if (s.prayer.hanafiAsr) "حنفي" else "شافعي (الجمهور)") { setPrayer { it.copy(hanafiAsr = !it.hanafiAsr) } } }
            item {
                val rule = runCatching { HighLatRule.valueOf(s.prayer.highLatitudeRule) }.getOrDefault(HighLatRule.MIDDLE_OF_THE_NIGHT)
                SettingRow("تعديل خطوط العرض العليا", rule.label) {
                    setPrayer { it.copy(highLatitudeRule = HighLatRule.values().toList().after(rule).name) }
                }
            }
            items(PrayerName.values().toList()) { p ->
                val v = s.prayer.offsets.getOrElse(p.ordinal) { 0 }
                StepperRow("تعديل وقت ${p.arabic} (دقائق)", (if (v > 0) "+" else "") + v,
                    onMinus = { setPrayer { cfg -> cfg.copy(offsets = cfg.offsets.mapIndexed { i, o -> if (i == p.ordinal) (o - 1).coerceAtLeast(-30) else o }) } },
                    onPlus = { setPrayer { cfg -> cfg.copy(offsets = cfg.offsets.mapIndexed { i, o -> if (i == p.ordinal) (o + 1).coerceAtMost(30) else o }) } })
            }
            item {
                StepperRow("تعديل التاريخ الهجري (أيام)", (if (s.prayer.hijriAdjustDays > 0) "+" else "") + s.prayer.hijriAdjustDays,
                    onMinus = { setPrayer { it.copy(hijriAdjustDays = (it.hijriAdjustDays - 1).coerceAtLeast(-2)) } },
                    onPlus = { setPrayer { it.copy(hijriAdjustDays = (it.hijriAdjustDays + 1).coerceAtMost(2)) } })
            }
            item { SettingRow("عرض أوقات الصلاة في الشاشة الرئيسية", onOff(s.prayer.showOnHome)) { setPrayer { it.copy(showOnHome = !it.showOnHome) } } }
            item { SettingRow("تنبيه عند دخول وقت الصلاة", onOff(s.prayer.reminders)) { setPrayer { it.copy(reminders = !it.reminders) } } }

            item { SectionTitle("الأمان") }
            item { SettingRow("تغيير رمز المسؤول", "") { c.navigate(Screen.Pin(com.arabrock.tvlauncher.ui.PinMode.CHANGE) { c.navigate(Screen.Settings) }) } }
            item {
                StepperRow("القفل التلقائي بعد (ثانية)", "${s.lockTimeoutSec}",
                    onMinus = { set { it.copy(lockTimeoutSec = (it.lockTimeoutSec - 30).coerceAtLeast(30)) } },
                    onPlus = { set { it.copy(lockTimeoutSec = (it.lockTimeoutSec + 30).coerceAtMost(1800)) } })
            }
            item {
                StepperRow("مدة الانتظار بعد 3 محاولات خاطئة (ثانية)", "${s.failDelayBaseSec}",
                    onMinus = { set { it.copy(failDelayBaseSec = (it.failDelayBaseSec - 10).coerceAtLeast(10)) } },
                    onPlus = { set { it.copy(failDelayBaseSec = (it.failDelayBaseSec + 10).coerceAtMost(300)) } })
            }

            item { SectionTitle("التحديثات") }
            item { SettingRow("الإصدار الحالي", "${Updater.currentBuild}") {} }
            item { SettingRow("البحث عن تحديث تلقائياً عند التشغيل", onOff(s.autoUpdateCheck)) { set { it.copy(autoUpdateCheck = !it.autoUpdateCheck) } } }
            item {
                SettingRow("البحث عن تحديث الآن", upd.message ?: "") {
                    scope.launch { Updater.check() }
                }
            }
            if (upd.availableBuild != null) {
                item {
                    SettingRow(
                        "تنزيل وتثبيت الإصدار ${upd.availableBuild}",
                        if (upd.progress in 0..100) "${upd.progress}%" else "ابدأ",
                    ) {
                        if (!Updater.canInstall(c.activity)) {
                            if (!Updater.requestInstallPermission(c.activity)) {
                                c.toast("فعّل إذن التثبيت عبر ADB: appops set com.arabrock.tvlauncher REQUEST_INSTALL_PACKAGES allow")
                            }
                        } else {
                            scope.launch { Updater.downloadAndInstall(c.activity) }
                        }
                    }
                }
            }
            item { SettingRow("إذن تثبيت التحديثات", if (Updater.canInstall(c.activity)) "ممنوح" else "غير ممنوح") { Updater.requestInstallPermission(c.activity) } }

            item { SectionTitle("أوامر ADB لمرة واحدة (من تطبيق ATV Tools)") }
            items(adbSetupCommands) { cmd ->
                Text(cmd, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
            item { Text(" ", fontSize = 40.sp) }
        }
    }
}

val adbSetupCommands = listOf(
    "settings put global enable_freeform_support 1",
    "settings put global force_resizable_activities 1",
    "cmd package set-home-activity com.arabrock.tvlauncher/.MainActivity",
    "appops set com.arabrock.tvlauncher SYSTEM_ALERT_WINDOW allow",
    "appops set com.arabrock.tvlauncher REQUEST_INSTALL_PACKAGES allow",
    "(عند الحاجة فقط) settings put global hidden_api_policy 1",
)
