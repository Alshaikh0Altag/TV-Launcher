package com.arabrock.tvlauncher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isLeanback: Boolean,
    /** ActivityInfo.launchMode of the entry activity (standard, singleTop, singleTask, singleInstance). */
    val launchMode: Int,
) {
    /** singleTask / singleInstance activities refuse to run inside another app's task. */
    val canBeHosted: Boolean
        get() = launchMode != ActivityInfo.LAUNCH_SINGLE_TASK && launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE
}

/** Installed application discovery. Runs once per launcher start and on package changes. */
class AppRepository private constructor(private val context: Context) {
    private val pm: PackageManager = context.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps
    private val iconCache = object : LruCache<String, ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    @Volatile private var loading = false

    suspend fun refresh() {
        if (loading) return
        loading = true
        try {
            _apps.value = withContext(Dispatchers.IO) { query() }
        } finally {
            loading = false
        }
    }

    private fun query(): List<AppEntry> {
        val result = LinkedHashMap<String, AppEntry>()
        fun collect(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            @Suppress("DEPRECATION")
            val list = pm.queryIntentActivities(intent, 0)
            for (ri in list) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == context.packageName) continue
                if (result.containsKey(ai.packageName)) continue
                result[ai.packageName] = AppEntry(
                    packageName = ai.packageName,
                    activityName = ai.name,
                    label = ri.loadLabel(pm)?.toString() ?: ai.packageName,
                    isLeanback = leanback,
                    launchMode = ai.launchMode,
                )
            }
        }
        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)
        return result.values.sortedBy { it.label.lowercase() }
    }

    fun find(pkg: String): AppEntry? = _apps.value.firstOrNull { it.packageName == pkg }

    /** Launch intent without task flags (the caller decides the flags). */
    fun launchIntent(app: AppEntry): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setClassName(app.packageName, app.activityName)

    /** Icon (or TV banner) as an ImageBitmap, cached in memory. Call from a background thread. */
    fun icon(pkg: String): ImageBitmap? {
        iconCache.get(pkg)?.let { return it }
        val drawable: Drawable = try {
            pm.getApplicationBanner(pkg) ?: pm.getApplicationIcon(pkg)
        } catch (e: Exception) {
            return null
        }
        val bmp = drawableToBitmap(drawable) ?: return null
        val img = bmp.asImageBitmap()
        iconCache.put(pkg, img)
        return img
    }

    private fun drawableToBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable && d.bitmap != null) {
            val b = d.bitmap
            // Downscale very large icons to keep memory low.
            return if (b.width > 320) Bitmap.createScaledBitmap(b, 320, 320 * b.height / b.width, true) else b
        }
        var w = d.intrinsicWidth.takeIf { it > 0 } ?: 160
        var h = d.intrinsicHeight.takeIf { it > 0 } ?: 160
        if (w > 320) { h = 320 * h / w; w = 320 }
        return try {
            val bmp = Bitmap.createBitmap(w, h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, c.width, c.height)
            d.draw(c)
            bmp
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile private var instance: AppRepository? = null
        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
