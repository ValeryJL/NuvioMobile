package com.nuvio.app.features.downloads

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.core.build.AppFeaturePolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val DOWNLOADS_FOREGROUND_NOTIFICATION_ID = 0x444E56

class DownloadsForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = ConcurrentHashMap<String, Job>()
    private var wakeLockGuard: AndroidTransferWakeLockGuard? = null
    private var lastNotificationUpdateAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activeInstance = this
        val appCtx = applicationContext
        DownloadsStorage.initialize(appCtx)
        val scheduler = DownloadsPlatformDownloader.scheduler(appCtx)
        DownloadsLiveStatusPlatform.initialize(appCtx)

        val activeTransfers = activeTransfersIn(scheduler)
        if (activeTransfers.isEmpty()) {
            promoteToForeground(DownloadsLiveStatusPlatform.buildIdleForegroundNotification(this))
            stopForegroundAndSelf(startId)
            return START_NOT_STICKY
        }

        ensureWakeLock(appCtx)
        syncHostedIds(activeTransfers)
        promoteToForeground(
            DownloadsLiveStatusPlatform.buildForegroundNotification(this, activeTransfers.map { it.item }),
        )
        syncRunners(scheduler, activeTransfers)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        val scheduler = runCatching { DownloadsPlatformDownloader.scheduler(applicationContext) }.getOrNull()
        val activeTransfers = scheduler?.let(::activeTransfersIn).orEmpty()
        runs.values.forEach { it.cancel() }
        runs.clear()
        if (scheduler != null) {
            val timeoutDetail = DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_TIMEOUT,
                "FGS_DATA_SYNC_TIMEOUT",
            )
            activeTransfers.forEach { transfer ->
                activeTrackedGenerations.remove(transfer.generation)
                pendingStartSinceMs.remove(transfer.generation)
                hostedDownloadIds.remove(transfer.item.id)
                scheduler.recordSystemStop(transfer, timeoutDetail)
                scheduler.scheduleSystemJob(transfer)
            }
        }
        stopForegroundAndSelf(startId)
        if (Build.VERSION.SDK_INT >= 35) {
            super.onTimeout(startId, fgsType)
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        runs.values.forEach { it.cancel() }
        runs.clear()
        activeTrackedGenerations.clear()
        hostedDownloadIds.clear()
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground(notification: android.app.Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, DOWNLOADS_FOREGROUND_NOTIFICATION_ID, notification, serviceType)
        lastNotificationUpdateAt = SystemClock.elapsedRealtime()
    }

    private fun syncRunners(
        scheduler: AndroidDownloadScheduler,
        activeTransfers: List<AndroidDownloadTransfer>,
    ) {
        val activeGenerations = activeTransfers.map { it.generation }.toSet()
        runs.keys.toList().forEach { generation ->
            if (generation !in activeGenerations) {
                runs.remove(generation)?.cancel()
                activeTrackedGenerations.remove(generation)
                pendingStartSinceMs.remove(generation)
            }
        }

        activeTransfers.forEach { transfer ->
            pendingStartSinceMs.remove(transfer.generation)
            activeTrackedGenerations.add(transfer.generation)
            if (runs[transfer.generation]?.isActive == true) return@forEach

            val run = serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (isActive && scheduler.isActive(transfer)) {
                        val retry = scheduler.execute(transfer) {
                            refreshForegroundNotification(scheduler)
                        }
                        if (!retry) break
                        val retryCount = scheduler.store.get(transfer.item.fileName)
                            ?.takeIf { it.generation == transfer.generation }
                            ?.retryCount ?: 1
                        delay(backoffDelayMs(retryCount))
                    }
                } finally {
                    if (runs[transfer.generation] === currentCoroutineContext()[Job]) {
                        runs.remove(transfer.generation)
                    }
                    activeTrackedGenerations.remove(transfer.generation)
                    pendingStartSinceMs.remove(transfer.generation)
                    val latest = scheduler.store.get(transfer.item.fileName)
                    if (latest?.item?.status != DownloadStatus.Downloading) {
                        hostedDownloadIds.remove(transfer.item.id)
                        scheduler.notifyCurrent(transfer.item.fileName)
                    }
                    recheckRemainingTransfers(scheduler)
                }
            }
            runs.put(transfer.generation, jobOrSelf(run))?.cancel()
            run.start()
        }
    }

    private fun jobOrSelf(job: Job): Job = job

    private fun recheckRemainingTransfers(scheduler: AndroidDownloadScheduler) {
        serviceScope.launch {
            var remaining = activeTransfersIn(scheduler)
            if (remaining.isEmpty() && hasQueuedRepositoryItems()) {
                repeat(3) {
                    delay(100L)
                    remaining = activeTransfersIn(scheduler)
                    if (remaining.isNotEmpty()) return@repeat
                }
            }
            if (remaining.isNotEmpty()) {
                syncHostedIds(remaining)
                refreshForegroundNotification(scheduler, force = true)
                syncRunners(scheduler, remaining)
            } else if (runs.isEmpty()) {
                stopForegroundAndSelf()
            }
        }
    }

    private fun cancelRunAndSync(generation: String) {
        runs.remove(generation)?.cancel()
        activeTrackedGenerations.remove(generation)
        pendingStartSinceMs.remove(generation)
        val scheduler = runCatching { DownloadsPlatformDownloader.scheduler(applicationContext) }.getOrNull()
        if (scheduler == null) {
            if (runs.isEmpty()) stopForegroundAndSelf()
            return
        }
        val remaining = activeTransfersIn(scheduler)
        if (remaining.isNotEmpty()) {
            syncHostedIds(remaining)
            refreshForegroundNotification(scheduler, force = true)
            syncRunners(scheduler, remaining)
        } else if (runs.isEmpty()) {
            stopForegroundAndSelf()
        }
    }

    private fun refreshForegroundNotification(
        scheduler: AndroidDownloadScheduler,
        force: Boolean = false,
    ) {
        val active = activeTransfersIn(scheduler)
        if (active.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationUpdateAt < NOTIFICATION_REFRESH_INTERVAL_MS) return
        lastNotificationUpdateAt = now
        syncHostedIds(active)
        val notification = DownloadsLiveStatusPlatform.buildForegroundNotification(
            context = this,
            activeItems = active.map { it.item },
        )
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(DOWNLOADS_FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf(startId: Int? = null) {
        hostedDownloadIds.clear()
        activeTrackedGenerations.clear()
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (startId != null) {
            stopSelf(startId)
        } else {
            stopSelf()
        }
    }

    private fun ensureWakeLock(context: Context) {
        if (wakeLockGuard == null) {
            wakeLockGuard = acquireTransferWakeLocks(context, "nuvio:downloads:service")
        }
    }

    private fun releaseWakeLock() {
        wakeLockGuard?.close()
        wakeLockGuard = null
    }

    private fun syncHostedIds(activeTransfers: List<AndroidDownloadTransfer>) {
        hostedDownloadIds.clear()
        hostedDownloadIds.addAll(activeTransfers.map { it.item.id })
    }

    private fun activeTransfersIn(scheduler: AndroidDownloadScheduler): List<AndroidDownloadTransfer> =
        scheduler.store.transfers.value.values
            .filter { it.item.status == DownloadStatus.Downloading }
            .sortedBy { it.item.createdAtEpochMs }

    private fun hasQueuedRepositoryItems(): Boolean = runCatching {
        DownloadsRepository.uiState.value.items.any { it.status == DownloadStatus.Queued }
    }.getOrDefault(false)

    private fun backoffDelayMs(retryCount: Int): Long {
        val shift = (retryCount - 1).coerceIn(0, 3)
        return (10_000L shl shift).coerceAtMost(60_000L)
    }

    companion object {
        internal const val ACTION_SYNC_DOWNLOADS = "com.nuvio.app.downloads.action.SYNC_FOREGROUND"
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 1_000L
        private const val PENDING_START_GRACE_MS = 10_000L

        @Volatile
        private var activeInstance: DownloadsForegroundService? = null
        private val activeTrackedGenerations = ConcurrentHashMap.newKeySet<String>()
        private val hostedDownloadIds = ConcurrentHashMap.newKeySet<String>()
        private val pendingStartSinceMs = ConcurrentHashMap<String, Long>()

        fun isHosting(downloadId: String): Boolean = hostedDownloadIds.contains(downloadId)

        fun isTrackingOrPending(generation: String, nowMs: Long = System.currentTimeMillis()): Boolean {
            if (activeTrackedGenerations.contains(generation)) return true
            val pendingSince = pendingStartSinceMs[generation] ?: return false
            return if (nowMs - pendingSince < PENDING_START_GRACE_MS) {
                true
            } else {
                pendingStartSinceMs.remove(generation, pendingSince)
                false
            }
        }

        fun markPending(generation: String, nowMs: Long = System.currentTimeMillis()) {
            pendingStartSinceMs[generation] = nowMs
        }

        fun clearPending(generation: String) {
            pendingStartSinceMs.remove(generation)
            activeTrackedGenerations.remove(generation)
        }

        fun cancelGeneration(generation: String) {
            clearPending(generation)
            activeInstance?.cancelRunAndSync(generation)
        }

        fun startOrSync(context: Context): Boolean {
            if (!AppFeaturePolicy.downloadForegroundServiceEnabled) return false
            val appCtx = context.applicationContext
            val intent = Intent(appCtx, DownloadsForegroundService::class.java)
                .setAction(ACTION_SYNC_DOWNLOADS)
            return runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
                true
            }.getOrDefault(false)
        }

        fun syncIfRunning(context: Context) {
            val instance = activeInstance ?: return
            val scheduler = runCatching { DownloadsPlatformDownloader.scheduler(context.applicationContext) }.getOrNull() ?: return
            instance.serviceScope.launch {
                val remaining = instance.activeTransfersIn(scheduler)
                if (remaining.isNotEmpty()) {
                    instance.syncHostedIds(remaining)
                    instance.refreshForegroundNotification(scheduler, force = true)
                    instance.syncRunners(scheduler, remaining)
                } else if (instance.runs.isEmpty()) {
                    instance.stopForegroundAndSelf()
                }
            }
        }

        fun updateNotificationIfRunning(context: Context) {
            val instance = activeInstance ?: return
            val scheduler = runCatching { DownloadsPlatformDownloader.scheduler(context.applicationContext) }.getOrNull() ?: return
            instance.refreshForegroundNotification(scheduler)
        }
    }
}
