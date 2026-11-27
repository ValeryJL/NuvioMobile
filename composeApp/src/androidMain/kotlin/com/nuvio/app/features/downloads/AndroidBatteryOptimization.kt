package com.nuvio.app.features.downloads

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.nuvio.app.core.build.AppFeaturePolicy

internal object AndroidBatteryOptimization {
    fun isSupported(): Boolean = AppFeaturePolicy.downloadForegroundServiceEnabled

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun needsExemption(context: Context): Boolean =
        isSupported() && !isIgnoringBatteryOptimizations(context)

    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        if (!isSupported()) return false
        val packageUri = Uri.parse("package:${context.packageName}")
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
        }
        return candidates.any { intent ->
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }
    }
}
