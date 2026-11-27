package com.nuvio.app.features.downloads

internal actual object DownloadPowerPlatform {
    actual fun needsBatteryOptimizationExemption(): Boolean = false

    actual fun requestBatteryExemption(): Boolean = false
}
