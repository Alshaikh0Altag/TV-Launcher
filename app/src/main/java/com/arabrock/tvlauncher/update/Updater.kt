package com.arabrock.tvlauncher.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.arabrock.tvlauncher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateState(
    val busy: Boolean = false,
    val availableBuild: Int? = null,
    val downloadUrl: String? = null,
    val progress: Int = -1,
    val message: String? = null,
)

/**
 * In-app updates from the GitHub Releases of this project. Every push builds a release named
 * "build-N" (N = versionCode). The launcher downloads the newer APK and installs it through
 * PackageInstaller; Android shows one confirmation dialog ("Install" / "Update").
 */
object Updater {
    private const val REPO = "Alshaikh0Altag/TV-Launcher"
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state

    val currentBuild: Int get() = BuildConfig.VERSION_CODE

    fun setMessage(msg: String?) { _state.value = _state.value.copy(busy = false, message = msg) }

    /** Returns true when a newer build exists. */
    suspend fun check(): Boolean = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(busy = true, message = "جارٍ البحث عن تحديث…")
        try {
            val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TV-Launcher")
            }
            val code = conn.responseCode
            if (code != 200) {
                _state.value = UpdateState(message = "تعذر الوصول إلى خادم التحديثات (HTTP $code)")
                return@withContext false
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val build = tag.substringAfterLast('-').toIntOrNull() ?: 0
            val assets = json.optJSONArray("assets")
            var url: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
                }
            }
            if (build > currentBuild && url != null) {
                _state.value = UpdateState(availableBuild = build, downloadUrl = url, message = "يتوفر تحديث: الإصدار $build")
                true
            } else {
                _state.value = UpdateState(message = "أنت على آخر إصدار ($currentBuild)")
                false
            }
        } catch (e: Exception) {
            _state.value = UpdateState(message = "فشل البحث عن تحديث: تحقق من الاتصال بالإنترنت")
            false
        }
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" permission screen (may not exist on some TV firmware). */
    fun requestInstallPermission(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }

    /** Downloads and installs the available build. */
    suspend fun downloadAndInstall(context: Context) {
        val url = _state.value.downloadUrl ?: return
        val file = File(context.cacheDir, "update.apk")
        val ok = withContext(Dispatchers.IO) {
            try {
                _state.value = _state.value.copy(busy = true, progress = 0, message = "جارٍ تنزيل التحديث…")
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "TV-Launcher")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = _state.value.copy(progress = (done * 100 / total).toInt())
                        }
                    }
                }
                true
            } catch (e: Exception) {
                _state.value = _state.value.copy(busy = false, progress = -1, message = "فشل تنزيل التحديث")
                false
            }
        }
        if (!ok) return
        withContext(Dispatchers.IO) { install(context, file) }
    }

    private fun install(context: Context, apk: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, InstallResultReceiver::class.java),
                    flags or PendingIntent.FLAG_UPDATE_CURRENT
                )
                _state.value = _state.value.copy(message = "جارٍ التثبيت… وافق على رسالة التثبيت إن ظهرت")
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, progress = -1, message = "فشل التثبيت: ${e.javaClass.simpleName}")
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.setMessage("تم التحديث")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updater.setMessage("لم يكتمل التثبيت${if (msg != null) ": $msg" else ""}")
            }
        }
    }
}
