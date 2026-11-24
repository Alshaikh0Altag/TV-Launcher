package com.arabrock.tvlauncher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

enum class LaunchMode { HOSTED, DIRECT }

data class PrayerConfig(
    val cityName: String = "الرياض",
    val latitude: Double = 24.7136,
    val longitude: Double = 46.6753,
    val timeZoneId: String = "Asia/Riyadh",
    val method: String = "UMM_AL_QURA",
    val hanafiAsr: Boolean = false,
    val highLatitudeRule: String = "MIDDLE_OF_THE_NIGHT",
    /** Minutes added to Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha. */
    val offsets: List<Int> = listOf(0, 0, 0, 0, 0, 0),
    val hijriAdjustDays: Int = 0,
    val showOnHome: Boolean = true,
    val reminders: Boolean = false,
    val useDeviceLocation: Boolean = false,
)

data class Settings(
    val launchMode: LaunchMode = LaunchMode.HOSTED,
    val reserveBars: Boolean = true,
    val autoRestore: Boolean = true,
    val protectAssignments: Boolean = false,
    val lockTimeoutSec: Int = 120,
    val failDelayBaseSec: Int = 30,
    val autoUpdateCheck: Boolean = true,
    val prayer: PrayerConfig = PrayerConfig(),
    val favorites: List<String> = emptyList(),
    val recents: List<String> = emptyList(),
)

/** Small JSON-backed settings store; values are exposed as a StateFlow for Compose. */
class SettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state
    val value: Settings get() = _state.value

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        _state.value = next
        prefs.edit().putString(KEY, toJson(next).toString()).apply()
    }

    fun markRecent(pkg: String) = update { s ->
        s.copy(recents = (listOf(pkg) + s.recents.filter { it != pkg }).take(12))
    }

    fun toggleFavorite(pkg: String) = update { s ->
        s.copy(favorites = if (pkg in s.favorites) s.favorites - pkg else s.favorites + pkg)
    }

    private fun load(): Settings {
        val raw = prefs.getString(KEY, null) ?: return defaultSettings()
        return try { fromJson(JSONObject(raw)) } catch (e: Exception) { defaultSettings() }
    }

    private fun defaultSettings(): Settings {
        val tz = TimeZone.getDefault().id
        return Settings(prayer = PrayerConfig(timeZoneId = tz))
    }

    companion object {
        private const val KEY = "settings_json"
        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }

        private fun toJson(s: Settings): JSONObject = JSONObject().apply {
            put("launchMode", s.launchMode.name)
            put("reserveBars", s.reserveBars)
            put("autoRestore", s.autoRestore)
            put("protectAssignments", s.protectAssignments)
            put("lockTimeoutSec", s.lockTimeoutSec)
            put("failDelayBaseSec", s.failDelayBaseSec)
            put("autoUpdateCheck", s.autoUpdateCheck)
            put("favorites", JSONArray(s.favorites))
            put("recents", JSONArray(s.recents))
            val p = s.prayer
            put("prayer", JSONObject().apply {
                put("cityName", p.cityName)
                put("latitude", p.latitude)
                put("longitude", p.longitude)
                put("timeZoneId", p.timeZoneId)
                put("method", p.method)
                put("hanafiAsr", p.hanafiAsr)
                put("highLatitudeRule", p.highLatitudeRule)
                put("offsets", JSONArray(p.offsets))
                put("hijriAdjustDays", p.hijriAdjustDays)
                put("showOnHome", p.showOnHome)
                put("reminders", p.reminders)
                put("useDeviceLocation", p.useDeviceLocation)
            })
        }

        private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
        private fun JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }

        private fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            val pj = o.optJSONObject("prayer")
            val dp = PrayerConfig()
            val prayer = if (pj == null) dp else PrayerConfig(
                cityName = pj.optString("cityName", dp.cityName),
                latitude = pj.optDouble("latitude", dp.latitude),
                longitude = pj.optDouble("longitude", dp.longitude),
                timeZoneId = pj.optString("timeZoneId", dp.timeZoneId),
                method = pj.optString("method", dp.method),
                hanafiAsr = pj.optBoolean("hanafiAsr", dp.hanafiAsr),
                highLatitudeRule = pj.optString("highLatitudeRule", dp.highLatitudeRule),
                offsets = pj.optJSONArray("offsets")?.ints()?.takeIf { it.size == 6 } ?: dp.offsets,
                hijriAdjustDays = pj.optInt("hijriAdjustDays", 0),
                showOnHome = pj.optBoolean("showOnHome", true),
                reminders = pj.optBoolean("reminders", false),
                useDeviceLocation = pj.optBoolean("useDeviceLocation", false),
            )
            return Settings(
                launchMode = runCatching { LaunchMode.valueOf(o.optString("launchMode")) }.getOrDefault(d.launchMode),
                reserveBars = o.optBoolean("reserveBars", d.reserveBars),
                autoRestore = o.optBoolean("autoRestore", d.autoRestore),
                protectAssignments = o.optBoolean("protectAssignments", d.protectAssignments),
                lockTimeoutSec = o.optInt("lockTimeoutSec", d.lockTimeoutSec),
                failDelayBaseSec = o.optInt("failDelayBaseSec", d.failDelayBaseSec),
                autoUpdateCheck = o.optBoolean("autoUpdateCheck", d.autoUpdateCheck),
                prayer = prayer,
                favorites = o.optJSONArray("favorites")?.strings() ?: emptyList(),
                recents = o.optJSONArray("recents")?.strings() ?: emptyList(),
            )
        }
    }
}
