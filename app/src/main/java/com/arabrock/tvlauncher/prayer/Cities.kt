package com.arabrock.tvlauncher.prayer

data class City(val name: String, val lat: Double, val lng: Double, val tz: String, val method: String = "UMM_AL_QURA")

object Cities {
    val all = listOf(
        City("الرياض", 24.7136, 46.6753, "Asia/Riyadh"),
        City("مكة المكرمة", 21.4225, 39.8262, "Asia/Riyadh"),
        City("المدينة المنورة", 24.4686, 39.6142, "Asia/Riyadh"),
        City("جدة", 21.4858, 39.1925, "Asia/Riyadh"),
        City("الدمام", 26.4207, 50.0888, "Asia/Riyadh"),
        City("الخبر", 26.2172, 50.1971, "Asia/Riyadh"),
        City("الأحساء", 25.3833, 49.5867, "Asia/Riyadh"),
        City("الطائف", 21.2703, 40.4158, "Asia/Riyadh"),
        City("بريدة", 26.3592, 43.9818, "Asia/Riyadh"),
        City("تبوك", 28.3835, 36.5662, "Asia/Riyadh"),
        City("حائل", 27.5114, 41.7208, "Asia/Riyadh"),
        City("أبها", 18.2164, 42.5053, "Asia/Riyadh"),
        City("خميس مشيط", 18.3000, 42.7333, "Asia/Riyadh"),
        City("جازان", 16.8892, 42.5511, "Asia/Riyadh"),
        City("نجران", 17.4917, 44.1322, "Asia/Riyadh"),
        City("الباحة", 20.0129, 41.4677, "Asia/Riyadh"),
        City("الجوف (سكاكا)", 29.9697, 40.2064, "Asia/Riyadh"),
        City("عرعر", 30.9753, 41.0381, "Asia/Riyadh"),
        City("ينبع", 24.0895, 38.0618, "Asia/Riyadh"),
        City("الجبيل", 27.0046, 49.6460, "Asia/Riyadh"),
        City("حفر الباطن", 28.4328, 45.9708, "Asia/Riyadh"),
        City("القريات", 31.3317, 37.3428, "Asia/Riyadh"),
        City("دبي", 25.2048, 55.2708, "Asia/Dubai", "DUBAI"),
        City("أبوظبي", 24.4539, 54.3773, "Asia/Dubai", "DUBAI"),
        City("الكويت", 29.3759, 47.9774, "Asia/Kuwait", "KUWAIT"),
        City("الدوحة", 25.2854, 51.5310, "Asia/Qatar", "QATAR"),
        City("المنامة", 26.2285, 50.5860, "Asia/Bahrain", "UMM_AL_QURA"),
        City("مسقط", 23.5880, 58.3829, "Asia/Muscat", "MUSLIM_WORLD_LEAGUE"),
        City("عمّان", 31.9454, 35.9284, "Asia/Amman", "MUSLIM_WORLD_LEAGUE"),
        City("القاهرة", 30.0444, 31.2357, "Africa/Cairo", "EGYPTIAN"),
    )
}
