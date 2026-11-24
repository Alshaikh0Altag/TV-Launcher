package com.arabrock.tvlauncher.window

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Root activity of one workspace window. It starts the target application inside its own task
 * (no NEW_TASK flag), so the launcher owns the task and can focus or close it through
 * ActivityManager.AppTask, and several copies of the same app can each live in their own window.
 *
 * Applications whose entry activity is singleTask / singleInstance leave this task; in that case
 * the window is reported as ESCAPED and this host removes itself.
 */
class HostActivity : Activity() {
    private var slot = -1
    private var started = false
    private var startTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot = intent.getIntExtra(EXTRA_SLOT, -1)
        started = savedInstanceState?.getBoolean("started") ?: false
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        setContentView(TextView(this).apply {
            text = "جارٍ تشغيل $label …"
            setTextColor(Color.LTGRAY)
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        })
        if (!started) startTarget()
    }

    private fun startTarget() {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finishAndRemoveTask()
        val cls = intent.getStringExtra(EXTRA_ACTIVITY) ?: return finishAndRemoveTask()
        val leanback = intent.getBooleanExtra(EXTRA_LEANBACK, true)
        val target = Intent(Intent.ACTION_MAIN)
            .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setClassName(pkg, cls)
        // Same-task launch. If the app forces its own task, these options still place it
        // in this window's rectangle.
        val bounds: Rect? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_BOUNDS, Rect::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BOUNDS)
        }
        try {
            if (bounds != null) startActivity(target, WindowController.windowOptions(bounds))
            else startActivity(target)
            started = true
            startTime = System.currentTimeMillis()
        } catch (e: Exception) {
            WindowController.setState(slot, WindowState.FAILED)
            WindowController.reportError("تعذر تشغيل التطبيق في النافذة ${slot + 1}: ${e.javaClass.simpleName}")
            finishAndRemoveTask()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!started || isFinishing) return
        val task = WindowController.hostedTask(this, slot)
        @Suppress("DEPRECATION")
        val count = runCatching { task?.taskInfo?.numActivities ?: 0 }.getOrDefault(0)
        if (task != null && count <= 1 && System.currentTimeMillis() - startTime < 15_000) {
            // The application started in its own task: this host is not needed.
            WindowController.setState(slot, WindowState.ESCAPED)
            finishAndRemoveTask()
        }
    }

    override fun onRestart() {
        super.onRestart()
        // The hosted application was closed (Back) and we are visible again: close the window.
        if (started) finishAndRemoveTask()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("started", started)
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_ACTIVITY = "cls"
        const val EXTRA_LEANBACK = "leanback"
        const val EXTRA_LABEL = "label"
        const val EXTRA_BOUNDS = "bounds"
    }
}
