package com.nuvio.app.features.downloads

internal expect object DownloadPowerPlatform {
    fun needsBatteryOptimizationExemption(): Boolean
    fun requestBatteryExemption(): Boolean
    fun isUnmeteredNetworkAvailable(): Boolean
}
