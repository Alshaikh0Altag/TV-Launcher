package com.arabrock.tvlauncher.window

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import com.arabrock.tvlauncher.data.AppEntry
import com.arabrock.tvlauncher.data.AppRepository
import com.arabrock.tvlauncher.data.LaunchMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class WindowState { EMPTY, NOT_RUNNING, HOSTED, ESCAPED, DIRECT, FAILED }

data class FreeformStatus(
    val featureDeclared: Boolean,
    val freeformSetting: Boolean,
    val forceResizable: Boolean,
    val windowingModeApi: Boolean,
) {
    /** Freeform is usable when the system feature exists or it was enabled via ADB. */
    val usable: Boolean get() = (featureDeclared || freeformSetting) && windowingModeApi
}

/**
 * Starts, focuses and closes application windows using only APIs that an ordinary app may call:
 * ActivityOptions.setLaunchBounds() + setLaunchWindowingMode(FREEFORM), FLAG_ACTIVITY_NEW_DOCUMENT,
 * FLAG_ACTIVITY_MULTIPLE_TASK and ActivityManager.AppTask (for tasks owned by this launcher).
 */
object WindowController {
    private const val TAG = "TVL-Window"
    const val WINDOWING_MODE_FREEFORM = 5

    private val _states = MutableStateFlow<Map<Int, WindowState>>(emptyMap())
    val states: StateFlow<Map<Int, WindowState>> = _states
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Packages that were launched directly (not hosted) per slot. */
    private val directSlots = HashMap<Int, String>()

    fun setState(slot: Int, state: WindowState) {
        _states.value = _states.value + (slot to state)
    }

    fun reportError(message: String?) { _lastError.value = message }

    fun slotUri(slot: Int): Uri = Uri.parse("tvlauncher://window/$slot")

    fun freeformStatus(context: Context): FreeformStatus {
        val cr = context.contentResolver
        return FreeformStatus(
            featureDeclared = context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT),
            freeformSetting = Settings.Global.getInt(cr, "enable_freeform_support", 0) != 0,
            forceResizable = Settings.Global.getInt(cr, "force_resizable_activities", 0) != 0,
            windowingModeApi = windowingModeMethod() != null,
        )
    }

    private fun windowingModeMethod() = try {
        ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
    } catch (t: Throwable) {
        null
    }

    /** ActivityOptions that ask the system for a freeform window at [bounds]. */
    fun windowOptions(bounds: Rect): Bundle? {
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(Rect(bounds))
        try {
            windowingModeMethod()?.invoke(options, WINDOWING_MODE_FREEFORM)
        } catch (t: Throwable) {
            Log.w(TAG, "setLaunchWindowingMode failed: $t")
        }
        return options.toBundle()
    }

    private fun am(context: Context) = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** The launcher-owned task that hosts window [slot], if it is still alive. */
    fun hostedTask(context: Context, slot: Int): ActivityManager.AppTask? {
        val uri = slotUri(slot)
        return try {
            am(context).appTasks.firstOrNull { t ->
                runCatching { t.taskInfo.baseIntent.data == uri }.getOrDefault(false)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** Number of activities in the hosted task (1 = only the host; >1 = target app running inside). */
    private fun hostedActivityCount(task: ActivityManager.AppTask): Int =
        runCatching {
            @Suppress("DEPRECATION")
            task.taskInfo.numActivities
        }.getOrDefault(0)

    private fun hostedTopPackage(task: ActivityManager.AppTask): String? =
        runCatching {
            @Suppress("DEPRECATION")
            task.taskInfo.topActivity?.packageName
        }.getOrNull()

    data class Inspection(val alive: Boolean, val activities: Int, val topPackage: String?, val direct: Boolean)

    /** Low-level state of one window, used by the compatibility tests. */
    fun inspect(context: Context, slot: Int): Inspection {
        val task = hostedTask(context, slot)
        return if (task != null) Inspection(true, hostedActivityCount(task), hostedTopPackage(task), false)
        else Inspection(directSlots.containsKey(slot), 0, directSlots[slot], directSlots.containsKey(slot))
    }

    /** Re-checks every window and publishes the result (used by the workspace and diagnostics). */
    fun refreshStates(context: Context, slots: List<String?>, count: Int) {
        val map = HashMap<Int, WindowState>()
        for (i in 0 until count) {
            val pkg = slots.getOrNull(i)
            if (pkg == null) { map[i] = WindowState.EMPTY; continue }
            val prev = _states.value[i]
            val task = hostedTask(context, i)
            map[i] = when {
                task != null && hostedActivityCount(task) > 1 && hostedTopPackage(task) == pkg -> WindowState.HOSTED
                task != null -> WindowState.HOSTED
                directSlots[i] == pkg -> WindowState.DIRECT
                prev == WindowState.ESCAPED || prev == WindowState.FAILED -> prev
                else -> WindowState.NOT_RUNNING
            }
        }
        _states.value = map
    }

    /**
     * Opens [app] in window [slot] at [bounds]. [duplicate] = the same app is already open in an
     * earlier window (direct mode then needs FLAG_ACTIVITY_MULTIPLE_TASK).
     */
    fun open(context: Context, slot: Int, app: AppEntry, bounds: Rect, mode: LaunchMode, duplicate: Boolean): Boolean {
        val existing = hostedTask(context, slot)
        if (existing != null) {
            val top = hostedTopPackage(existing)
            if (top == app.packageName && hostedActivityCount(existing) > 1) {
                runCatching { existing.moveToFront() }
                setState(slot, WindowState.HOSTED)
                return true
            }
            runCatching { existing.finishAndRemoveTask() }
        }
        val useHost = mode == LaunchMode.HOSTED && app.canBeHosted
        return try {
            if (useHost) {
                val intent = Intent(context, HostActivity::class.java)
                    .setData(slotUri(slot))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(HostActivity.EXTRA_SLOT, slot)
                    .putExtra(HostActivity.EXTRA_PACKAGE, app.packageName)
                    .putExtra(HostActivity.EXTRA_ACTIVITY, app.activityName)
                    .putExtra(HostActivity.EXTRA_LEANBACK, app.isLeanback)
                    .putExtra(HostActivity.EXTRA_LABEL, app.label)
                    .putExtra(HostActivity.EXTRA_BOUNDS, Rect(bounds))
                context.startActivity(intent, windowOptions(bounds))
                directSlots.remove(slot)
                setState(slot, WindowState.HOSTED)
            } else {
                var flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (duplicate) flags = flags or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                val intent = AppRepository.get(context).launchIntent(app).addFlags(flags)
                context.startActivity(intent, windowOptions(bounds))
                directSlots[slot] = app.packageName
                setState(slot, WindowState.DIRECT)
            }
            true
        } catch (e: ActivityNotFoundException) {
            setState(slot, WindowState.FAILED)
            reportError("تعذر فتح ${app.label}: التطبيق غير موجود أو تم حذفه.")
            false
        } catch (e: SecurityException) {
            setState(slot, WindowState.FAILED)
            reportError("رفض النظام فتح ${app.label} (قيود صلاحيات).")
            false
        } catch (e: Exception) {
            setState(slot, WindowState.FAILED)
            reportError("فشل فتح ${app.label}: ${e.javaClass.simpleName}")
            false
        }
    }

    /** Gives keyboard/remote focus to window [slot] by bringing its task to the front. */
    fun focus(context: Context, slot: Int, app: AppEntry?, bounds: Rect): Boolean {
        val task = hostedTask(context, slot)
        if (task != null) {
            return runCatching { task.moveToFront(); true }.getOrDefault(false)
        }
        if (app != null && directSlots[slot] == app.packageName) {
            // Direct windows: re-sending the launch intent brings the app's existing task forward.
            return try {
                val intent = AppRepository.get(context).launchIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent, windowOptions(bounds))
                true
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    /** Closes window [slot]. Only launcher-hosted windows can be closed by the launcher. */
    fun close(context: Context, slot: Int): Boolean {
        directSlots.remove(slot)
        val task = hostedTask(context, slot) ?: run {
            setState(slot, WindowState.NOT_RUNNING)
            return false
        }
        runCatching { task.finishAndRemoveTask() }
        setState(slot, WindowState.NOT_RUNNING)
        return true
    }

    fun closeAll(context: Context) {
        for (i in 0 until 4) close(context, i)
    }

    fun isAnyWindowOpen(context: Context): Boolean = (0 until 4).any { hostedTask(context, it) != null } || directSlots.isNotEmpty()

    fun canUseFreeform(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
}
