package com.nuvio.app.features.downloads

import android.app.job.JobParameters
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.nuvio.app.core.build.AppFeaturePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class DownloadsTransferWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val scheduler = DownloadsPlatformDownloader.scheduler(applicationContext)
        val fileName = inputData.getString(AndroidDownloadScheduler.FILE_NAME) ?: return Result.failure()
        val generation = inputData.getString(AndroidDownloadScheduler.GENERATION) ?: return Result.failure()
        val transfer = scheduler.store.get(fileName)?.takeIf { it.generation == generation } ?: return Result.success()
        if (!scheduler.isActive(transfer)) return Result.success()
        DownloadsLiveStatusPlatform.initialize(applicationContext)
        try {
            if (AppFeaturePolicy.downloadForegroundServiceEnabled) {
                try {
                    setForeground(ForegroundInfo(
                        DownloadsLiveStatusPlatform.notificationId(transfer.item.id),
                        DownloadsLiveStatusPlatform.buildNotification(applicationContext, transfer.item),
                        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
                    ))
                } catch (error: IllegalStateException) {
                    scheduler.pause(
                        fileName = fileName,
                        reason = DownloadPauseReasons.withDetail(
                            DownloadPauseReasons.FG_SERVICE_BLOCKED,
                            error.message ?: error::class.simpleName,
                        ),
                    )
                    return Result.success()
                }
            }
            val retry = scheduler.execute(transfer) { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
            scheduler.notifyCurrent(fileName)
            return if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val latest = scheduler.store.get(fileName)?.takeIf { it.generation == generation }
                if (latest != null && latest.item.status == DownloadStatus.Downloading && Build.VERSION.SDK_INT >= 31) {
                    val code = stopReason
                    if (code == JobParameters.STOP_REASON_USER) {
                        scheduler.pause(fileName, DownloadPauseReasons.SYSTEM_USER_STOP)
                    } else {
                        AndroidDownloadStopReasons.categorizeJobStopReason(code)?.let { detail ->
                            scheduler.recordSystemStop(latest, detail)
                        }
                    }
                }
            }
            throw cancelled
        } catch (error: Exception) {
            scheduler.fail(transfer, error)
            scheduler.notifyCurrent(fileName)
            return Result.failure()
        }
    }
}
