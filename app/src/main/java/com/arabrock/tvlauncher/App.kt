package com.arabrock.tvlauncher

import android.app.Application
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Only exempt the single class we need (ActivityOptions).
            try {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityOptions;")
            } catch (t: Throwable) {
                Log.w("TVL", "Hidden API exemption failed: ${t.javaClass.simpleName}")
            }
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
