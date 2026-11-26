package com.nuvio.app.features.downloads

import android.app.job.JobParameters
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi

/**
 * Keeps CPU and Wi-Fi radios awake while an active background download transfer is executing,
 * preventing socket stalls and connection drops shortly after the screen turns off.
 */
internal class AndroidTransferWakeLockGuard(
    private val wakeLock: PowerManager.WakeLock?,
    private val wifiLock: WifiManager.WifiLock?,
) : AutoCloseable {
    override fun close() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock.release()
        }
    }
}

private const val TRANSFER_WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L

internal fun acquireTransferWakeLocks(context: Context, tag: String): AndroidTransferWakeLockGuard {
    val appContext = context.applicationContext
    val wakeLock = runCatching {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag:cpu")?.apply {
            setReferenceCounted(false)
            acquire(TRANSFER_WAKE_LOCK_TIMEOUT_MS)
        }
    }.getOrNull()

    val wifiLock = runCatching {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= 29) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiManager?.createWifiLock(lockMode, "$tag:wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    return AndroidTransferWakeLockGuard(wakeLock = wakeLock, wifiLock = wifiLock)
}

internal object AndroidDownloadStopReasons {
    @RequiresApi(31)
    fun categorizeJobStopReason(stopReason: Int): String? {
        if (stopReason == JobParameters.STOP_REASON_UNDEFINED || stopReason < 0) return null
        return when (stopReason) {
            JobParameters.STOP_REASON_TIMEOUT -> DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_TIMEOUT,
                "STOP_REASON_TIMEOUT",
            )
            JobParameters.STOP_REASON_QUOTA -> DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_QUOTA,
                "STOP_REASON_QUOTA",
            )
            JobParameters.STOP_REASON_BACKGROUND_RESTRICTION -> DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_BACKGROUND_RESTRICTION,
                "STOP_REASON_BACKGROUND_RESTRICTION",
            )
            JobParameters.STOP_REASON_APP_STANDBY -> DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_APP_STANDBY,
                "STOP_REASON_APP_STANDBY",
            )
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_CONNECTIVITY,
                "STOP_REASON_CONSTRAINT_CONNECTIVITY",
            )
            JobParameters.STOP_REASON_DEVICE_STATE -> DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_DEVICE_STATE,
                "STOP_REASON_DEVICE_STATE",
            )
            JobParameters.STOP_REASON_PREEMPT -> DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_PREEMPT,
                "STOP_REASON_PREEMPT",
            )
            JobParameters.STOP_REASON_SYSTEM_PROCESSING -> "STOP_REASON_SYSTEM_PROCESSING"
            JobParameters.STOP_REASON_CANCELLED_BY_APP -> "STOP_REASON_CANCELLED_BY_APP"
            JobParameters.STOP_REASON_USER -> "STOP_REASON_USER"
            else -> "STOP_REASON_$stopReason"
        }
    }
}
