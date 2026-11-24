package com.arabrock.tvlauncher.prayer

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.arabrock.tvlauncher.R
import com.arabrock.tvlauncher.data.SettingsStore

/**
 * Optional prayer reminder. One alarm is scheduled for the next prayer only (no background loop);
 * the receiver shows a notification and schedules the following one.
 */
class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cfg = SettingsStore.get(context).value.prayer
        if (!cfg.reminders) return
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "تنبيه الصلاة", NotificationManager.IMPORTANCE_HIGH)
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("حان وقت صلاة $name")
            .setContentText(cfg.cityName)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(1001, n) }
        schedule(context)
    }

    companion object {
        private const val CHANNEL = "prayer"
        private const val EXTRA_NAME = "name"

        fun schedule(context: Context) {
            val cfg = SettingsStore.get(context).value.prayer
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val base = Intent(context, PrayerAlarmReceiver::class.java)
            val cancelPi = PendingIntent.getBroadcast(
                context, 0, base, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(cancelPi)
            if (!cfg.reminders) return
            val status = PrayerEngine.status(cfg)
            val pi = PendingIntent.getBroadcast(
                context, 0, base.putExtra(EXTRA_NAME, status.next.arabic),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, status.nextAt, pi)
        }
    }
}
