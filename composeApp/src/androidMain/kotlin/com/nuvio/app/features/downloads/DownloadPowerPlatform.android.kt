package com.nuvio.app.features.downloads

import android.content.Context

internal actual object DownloadPowerPlatform {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual fun needsBatteryOptimizationExemption(): Boolean {
        val context = appContext ?: return false
        return AndroidBatteryOptimization.needsExemption(context)
    }

    actual fun requestBatteryExemption(): Boolean {
        val context = appContext ?: return false
        return AndroidBatteryOptimization.requestIgnoreBatteryOptimizations(context)
    }
}
