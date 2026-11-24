package com.arabrock.tvlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Point
import android.graphics.Rect
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.arabrock.tvlauncher.data.AppRepository
import com.arabrock.tvlauncher.data.SettingsStore
import com.arabrock.tvlauncher.prayer.PrayerAlarmReceiver
import com.arabrock.tvlauncher.security.PinManager
import com.arabrock.tvlauncher.ui.LauncherRoot
import com.arabrock.tvlauncher.ui.PinMode
import com.arabrock.tvlauncher.update.Updater
import com.arabrock.tvlauncher.window.WindowController
import com.arabrock.tvlauncher.workspace.Workspace
import com.arabrock.tvlauncher.workspace.WorkspaceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Screen {
    object Home : Screen()
    class Library(val assignSlot: Int?) : Screen()
    object Layout : Screen()
    object Profiles : Screen()
    object Settings : Screen()
    class Pin(val mode: PinMode, val then: () -> Unit) : Screen()
    object Diagnostics : Screen()
    object PrayerFull : Screen()
    class WindowMenu(val slot: Int) : Screen()
}

class MainActivity : ComponentActivity() {
    lateinit var controller: LauncherController
        private set

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lifecycleScope.launch { AppRepository.get(context).refresh() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller = LauncherController(this)
        setContent { LauncherRoot(controller) }
        // A launcher must not close on Back.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = controller.back()
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(packageReceiver, filter)

        lifecycleScope.launch { AppRepository.get(this@MainActivity).refresh() }
        PrayerAlarmReceiver.schedule(this)
        controller.onFirstStart()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        controller.visible = true
        controller.refreshWindowStates()
    }

    override fun onStop() {
        controller.visible = false
        // Leaving the launcher always locks administrator settings.
        controller.lockIfInProtectedScreen()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while the launcher is shown: return to the workspace
        // (except during a compatibility test, whose results must stay on screen).
        if (!controller.keepScreenOnHome) controller.goHome()
    }


    @Suppress("DEPRECATION")
    fun displaySize(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            Point().also { windowManager.defaultDisplay.getRealSize(it) }
        }
    }

    fun requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7)
        } else {
            applyDeviceLocation()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) applyDeviceLocation()
    }

    /** Uses the last known network location if the TV offers one. Never required. */
    @SuppressLint("MissingPermission")
    fun applyDeviceLocation(): Boolean {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            controller.toast("لم يُمنح إذن الموقع. استخدم اختيار المدينة يدوياً.")
            return false
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()
        if (loc == null) {
            controller.toast("التلفاز لا يوفر موقعاً موثوقاً. اختر المدينة يدوياً.")
            return false
        }
        SettingsStore.get(this).update {
            it.copy(prayer = it.prayer.copy(cityName = "الموقع الحالي", latitude = loc.latitude, longitude = loc.longitude, useDeviceLocation = true))
        }
        PrayerAlarmReceiver.schedule(this)
        controller.toast("تم تحديث الموقع")
        return true
    }
}

/** Launcher state and actions shared by all screens. */
class LauncherController(val activity: MainActivity) {
    val settings = SettingsStore.get(activity)
    val workspaces = WorkspaceStore.get(activity)
    val apps = AppRepository.get(activity)
    val pins = PinManager.get(activity)

    var screen by mutableStateOf<Screen>(Screen.Home)
    var maximizedSlot by mutableStateOf<Int?>(null)
    var visible by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var keepScreenOnHome = false
    private var restoredOnce = false

    // ---------- Geometry ----------
    val topBarFraction = 0.085f
    val bottomBarFraction = 0.12f

    fun screenRect(): Rect {
        val p = activity.displaySize()
        return Rect(0, 0, p.x, p.y)
    }

    /** Screen area used by application windows. */
    fun area(): Rect {
        val s = screenRect()
        if (!settings.value.reserveBars) return s
        val top = (s.height() * topBarFraction).toInt()
        val bottom = s.height() - (s.height() * bottomBarFraction).toInt()
        return Rect(0, top, s.width(), bottom)
    }

    fun slotRect(i: Int, ws: Workspace = workspaces.current.value): Rect {
        if (maximizedSlot == i) return area()
        val rects = ws.layout.rects(area())
        return rects.getOrElse(i) { rects.last() }
    }

    // ---------- Windows ----------
    private fun isDuplicate(ws: Workspace, slot: Int): Boolean {
        val pkg = ws.slot(slot) ?: return false
        return (0 until slot).any { ws.slot(it) == pkg }
    }

    fun openSlot(slot: Int): Boolean {
        val ws = workspaces.current.value
        val pkg = ws.slot(slot) ?: return false
        val app = apps.find(pkg)
        if (app == null) {
            toast("التطبيق غير مثبت: $pkg")
            return false
        }
        if (!WindowController.freeformStatus(activity).usable) {
            toast("وضع النوافذ غير مفعّل. افتح التشخيص لمعرفة أوامر ADB.")
        }
        settings.markRecent(pkg)
        return WindowController.open(activity, slot, app, slotRect(slot, ws), settings.value.launchMode, isDuplicate(ws, slot))
    }

    /** Opens (or brings forward) every assigned window, then focuses [focusSlot]. */
    fun openAll(focusSlot: Int? = null) {
        val ws = workspaces.current.value
        val slots = ws.activeSlots
        if (slots.isEmpty()) { toast("لا توجد تطبيقات في النوافذ. اختر تطبيقاً لكل نافذة."); return }
        activity.lifecycleScope.launch {
            for (s in slots) {
                openSlot(s)
                delay(450)
            }
            val f = focusSlot ?: slots.first()
            delay(300)
            focusSlot(f)
            refreshWindowStates()
        }
    }

    fun focusSlot(slot: Int) {
        val ws = workspaces.current.value
        val app = ws.slot(slot)?.let { apps.find(it) }
        if (!WindowController.focus(activity, slot, app, slotRect(slot, ws))) openSlot(slot)
    }

    fun closeSlot(slot: Int) {
        if (!WindowController.close(activity, slot)) {
            toast("هذه النافذة فتحت مباشرة؛ أغلقها من داخل التطبيق (زر الرجوع).")
        }
        if (maximizedSlot == slot) maximizedSlot = null
    }

    fun closeAll() {
        WindowController.closeAll(activity)
        maximizedSlot = null
    }

    /** Maximize / restore re-opens the window with new bounds (Android does not let a launcher resize other tasks). */
    fun toggleMaximize(slot: Int) {
        maximizedSlot = if (maximizedSlot == slot) null else slot
        WindowController.close(activity, slot)
        activity.lifecycleScope.launch {
            delay(350)
            openSlot(slot)
        }
    }

    fun assign(slot: Int, pkg: String?) {
        val ws = workspaces.current.value
        WindowController.close(activity, slot)
        workspaces.setCurrent(ws.withSlot(slot, pkg))
        if (pkg != null) {
            activity.lifecycleScope.launch { delay(300); openSlot(slot) }
        }
    }

    /** Applies a new layout / profile and re-opens the windows in their new positions. */
    fun applyWorkspace(ws: Workspace, relaunch: Boolean = true) {
        closeAll()
        workspaces.setCurrent(ws)
        if (relaunch && ws.activeSlots.isNotEmpty()) {
            activity.lifecycleScope.launch { delay(500); openAll() }
        }
    }

    fun refreshWindowStates() {
        val ws = workspaces.current.value
        WindowController.refreshStates(activity, ws.slots, ws.layout.type.slots)
    }

    fun launchFullScreen(pkg: String) {
        val app = apps.find(pkg) ?: return
        settings.markRecent(pkg)
        try {
            activity.startActivity(apps.launchIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("تعذر فتح ${app.label}")
        }
    }

    // ---------- Navigation & security ----------
    fun navigate(s: Screen) { screen = s }

    fun goHome() {
        keepScreenOnHome = false
        if (screen !is Screen.Home) {
            lockIfInProtectedScreen()
            screen = Screen.Home
        }
    }

    fun back() {
        when (screen) {
            is Screen.Home -> Unit
            else -> goHome()
        }
    }

    fun lockIfInProtectedScreen() {
        when (screen) {
            is Screen.Settings, is Screen.Layout, is Screen.Diagnostics -> pins.lock()
            else -> Unit
        }
    }

    /** Runs [action] after successful PIN entry (or PIN creation the first time). */
    fun requirePin(action: () -> Unit) {
        when {
            !pins.isPinSet -> screen = Screen.Pin(PinMode.SETUP, action)
            pins.isUnlocked() -> { pins.touch(settings.value.lockTimeoutSec); action() }
            else -> screen = Screen.Pin(PinMode.VERIFY, action)
        }
    }

    fun toast(msg: String) {
        message = msg
        activity.lifecycleScope.launch {
            delay(4000)
            if (message == msg) message = null
        }
    }

    fun onFirstStart() {
        if (restoredOnce) return
        restoredOnce = true
        activity.lifecycleScope.launch {
            apps.refresh()
            val ws = workspaces.current.value
            if (settings.value.autoRestore && ws.activeSlots.isNotEmpty() && !WindowController.isAnyWindowOpen(activity)) {
                delay(800)
                openAll()
            }
            if (settings.value.autoUpdateCheck) {
                delay(3000)
                Updater.check()
            }
        }
    }
}
