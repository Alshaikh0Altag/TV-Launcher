package com.arabrock.tvlauncher.prayer

import android.icu.util.IslamicCalendar
import android.icu.util.ULocale
import com.arabrock.tvlauncher.data.PrayerConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/** Calculation methods: Fajr angle, Isha angle (or fixed minutes after Maghrib). */
enum class CalcMethod(val label: String, val fajrAngle: Double, val ishaAngle: Double?, val ishaMinutes: Int?) {
    UMM_AL_QURA("أم القرى (مكة)", 18.5, null, 90),
    MUSLIM_WORLD_LEAGUE("رابطة العالم الإسلامي", 18.0, 17.0, null),
    EGYPTIAN("الهيئة المصرية العامة للمساحة", 19.5, 17.5, null),
    ISNA("أمريكا الشمالية (ISNA)", 15.0, 15.0, null),
    KARACHI("جامعة العلوم الإسلامية - كراتشي", 18.0, 18.0, null),
    DUBAI("دبي", 18.2, 18.2, null),
    KUWAIT("الكويت", 18.0, 17.5, null),
    QATAR("قطر", 18.0, null, 90),
}

enum class HighLatRule(val label: String) {
    MIDDLE_OF_THE_NIGHT("منتصف الليل"),
    SEVENTH_OF_THE_NIGHT("سُبع الليل"),
    TWILIGHT_ANGLE("حسب الزاوية"),
    NONE("بدون"),
}

enum class PrayerName(val arabic: String, val isPrayer: Boolean) {
    FAJR("الفجر", true), SUNRISE("الشروق", false), DHUHR("الظهر", true),
    ASR("العصر", true), MAGHRIB("المغرب", true), ISHA("العشاء", true),
}

data class DayTimes(val date: LocalDate, val times: List<Long>) {
    fun at(p: PrayerName) = times[p.ordinal]
}

data class PrayerStatus(
    val today: DayTimes,
    val current: PrayerName?,
    val next: PrayerName,
    val nextAt: Long,
)

/**
 * Offline prayer-time calculation (standard astronomical method used by PrayTimes.org).
 * Pure math, no network, no GPS. Results are cached per day and configuration.
 */
object PrayerEngine {
    private var cacheKey: String? = null
    private var cacheValue: DayTimes? = null

    fun zone(cfg: PrayerConfig): ZoneId = runCatching { ZoneId.of(cfg.timeZoneId) }.getOrDefault(ZoneId.systemDefault())

    fun day(cfg: PrayerConfig, date: LocalDate): DayTimes {
        val key = "$date|$cfg"
        if (key == cacheKey) cacheValue?.let { return it }
        val result = compute(cfg, date)
        if (date == LocalDate.now(zone(cfg))) { cacheKey = key; cacheValue = result }
        return result
    }

    fun status(cfg: PrayerConfig, nowMillis: Long = System.currentTimeMillis()): PrayerStatus {
        val z = zone(cfg)
        val todayDate = Instant.ofEpochMilli(nowMillis).atZone(z).toLocalDate()
        val today = day(cfg, todayDate)
        val prayers = PrayerName.values().filter { it.isPrayer }
        val next = prayers.firstOrNull { today.at(it) > nowMillis }
        val current = prayers.lastOrNull { today.at(it) <= nowMillis } ?: PrayerName.ISHA
        return if (next != null) {
            PrayerStatus(today, current, next, today.at(next))
        } else {
            val tomorrow = day(cfg, todayDate.plusDays(1))
            PrayerStatus(today, current, PrayerName.FAJR, tomorrow.at(PrayerName.FAJR))
        }
    }

    // ---------------- Hijri ----------------
    private val hijriMonths = listOf(
        "محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة",
    )

    /** Returns (day, month 0-based, year) using the Umm al-Qura calendar (+ admin adjustment). */
    fun hijri(cfg: PrayerConfig, date: LocalDate): Triple<Int, Int, Int>? = try {
        val cal = IslamicCalendar(ULocale("ar_SA"))
        cal.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        val millis = date.plusDays(cfg.hijriAdjustDays.toLong()).atTime(12, 0).atZone(zone(cfg)).toInstant().toEpochMilli()
        cal.timeInMillis = millis
        Triple(
            cal.get(android.icu.util.Calendar.DAY_OF_MONTH),
            cal.get(android.icu.util.Calendar.MONTH),
            cal.get(android.icu.util.Calendar.YEAR),
        )
    } catch (t: Throwable) {
        null
    }

    fun hijriText(cfg: PrayerConfig, date: LocalDate): String? =
        hijri(cfg, date)?.let { (d, m, y) -> "$d ${hijriMonths.getOrElse(m) { "" }} $y هـ" }

    // ---------------- Astronomy ----------------
    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1.0 / x))
    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return if (r < 0) r + b else r }
    private fun fixAngle(a: Double) = fix(a, 360.0)
    private fun fixHour(a: Double) = fix(a, 24.0)

    private fun julian(year: Int, month: Int, day: Int): Double {
        var y = year; var m = month
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + day + b - 1524.5
    }

    /** Returns (declination, equation of time). */
    private fun sunPosition(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = fixHour(darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0)
        val eqt = q / 15.0 - ra
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    private fun compute(cfg: PrayerConfig, date: LocalDate): DayTimes {
        val method = runCatching { CalcMethod.valueOf(cfg.method) }.getOrDefault(CalcMethod.UMM_AL_QURA)
        val rule = runCatching { HighLatRule.valueOf(cfg.highLatitudeRule) }.getOrDefault(HighLatRule.MIDDLE_OF_THE_NIGHT)
        val lat = cfg.latitude
        val lng = cfg.longitude
        val z = zone(cfg)
        val noonZoned = date.atTime(12, 0).atZone(z)
        val tzHours = noonZoned.offset.totalSeconds / 3600.0
        val jDate = julian(date.year, date.monthValue, date.dayOfMonth) - lng / (15.0 * 24.0)

        fun midDay(t: Double): Double = fixHour(12 - sunPosition(jDate + t).second)
        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPosition(jDate + t).first
            val noon = midDay(t)
            val tt = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
            return noon + if (ccw) -tt else tt
        }
        fun asrTime(factor: Int, t: Double): Double {
            val decl = sunPosition(jDate + t).first
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return sunAngleTime(angle, t, false)
        }

        // Initial guesses (hours), refined in a single pass as in PrayTimes.
        var fajr = sunAngleTime(method.fajrAngle, 5.0 / 24, true)
        var sunrise = sunAngleTime(0.833, 6.0 / 24, true)
        var dhuhr = midDay(12.0 / 24)
        var asr = asrTime(if (cfg.hanafiAsr) 2 else 1, 13.0 / 24)
        var sunset = sunAngleTime(0.833, 18.0 / 24, false)
        var maghrib = sunset
        var isha = method.ishaAngle?.let { sunAngleTime(it, 18.0 / 24, false) } ?: Double.NaN

        // Time zone + longitude correction.
        val shift = tzHours - lng / 15.0
        fajr += shift; sunrise += shift; dhuhr += shift; asr += shift; sunset += shift; maghrib += shift
        if (!isha.isNaN()) isha += shift

        // High latitude adjustment.
        if (rule != HighLatRule.NONE) {
            val night = fixHour(sunrise - sunset)
            fun portion(angle: Double) = when (rule) {
                HighLatRule.SEVENTH_OF_THE_NIGHT -> night / 7.0
                HighLatRule.TWILIGHT_ANGLE -> angle / 60.0 * night
                else -> night / 2.0
            }
            val fp = portion(method.fajrAngle)
            if (fajr.isNaN() || fixHour(sunrise - fajr) > fp) fajr = sunrise - fp
            if (method.ishaAngle != null) {
                val ip = portion(method.ishaAngle)
                if (isha.isNaN() || fixHour(isha - sunset) > ip) isha = sunset + ip
            }
        }

        // Fixed-interval Isha (Umm al-Qura: 90 min, 120 min during Ramadan).
        if (method.ishaMinutes != null) {
            var minutes = method.ishaMinutes
            if (method == CalcMethod.UMM_AL_QURA && hijri(cfg, date)?.second == 8) minutes = 120
            isha = maghrib + minutes / 60.0
        }

        val hours = listOf(fajr, sunrise, dhuhr, asr, maghrib, isha)
        val midnight = date.atStartOfDay(z).toInstant().toEpochMilli()
        val millis = hours.mapIndexed { i, h ->
            val offsetMin = cfg.offsets.getOrElse(i) { 0 }
            val minutes = (h * 60.0).roundToLong() + offsetMin
            midnight + minutes * 60_000L
        }
        return DayTimes(date, millis)
    }

    fun formatTime(millis: Long, cfg: PrayerConfig): String {
        val t: ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone(cfg))
        return "%02d:%02d".format(t.hour, t.minute)
    }
}
