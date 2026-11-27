package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nuvio.app.core.build.AppFeaturePolicy
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_stalled
import okhttp3.ConnectionPool
import org.jetbrains.compose.resources.getString

internal class AndroidDownloadScheduler(
    val context: Context,
    private val torrentProvider: TorrentSessionProvider = NuvioEngineTorrentSessionProvider(context.applicationContext),
    internal var preferForegroundService: Boolean = AppFeaturePolicy.downloadForegroundServiceEnabled,
) {
    val store = AndroidDownloadStore(File(context.filesDir, "download-transfers"))
    val directory = File(context.filesDir, "downloads")
    internal var wifiOnlyEnabled: () -> Boolean = {
        DownloadsSettingsRepository.ensureLoaded()
        DownloadsSettingsRepository.wifiOnly.value
    }
    internal var unmeteredNetworkAvailable: () -> Boolean = {
        isAndroidUnmeteredNetworkAvailable(context)
    }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val activeExecutions = ConcurrentHashMap.newKeySet<String>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal fun isNetworkAllowedNow(): Boolean =
        !wifiOnlyEnabled() || unmeteredNetworkAvailable()

    fun enqueue(item: DownloadItem, forceRecreate: Boolean = false): AndroidDownloadTransfer {
        val previous = store.get(item.fileName)
        if (forceRecreate && previous != null && previous.item.id == item.id) {
            activeJobs.remove(previous.generation)?.cancel()
            DownloadsForegroundService.cancelGeneration(previous.generation)
            cancelScheduled(previous)
        }
        val transfer = store.begin(item, forceNewJob = forceRecreate)
        val networkAllowed = isNetworkAllowedNow()
        val existing = !forceRecreate && previous?.generation == transfer.generation
        val alreadyRunning = existing && (
            activeExecutions.contains(transfer.generation) ||
                DownloadsForegroundService.isTrackingOrPending(transfer.generation) ||
                (Build.VERSION.SDK_INT >= 34 && jobScheduler().getPendingJob(transfer.jobId) != null)
            )
        if (alreadyRunning) return transfer

        if (preferForegroundService && networkAllowed) {
            DownloadsForegroundService.markPending(transfer.generation)
            if (DownloadsForegroundService.startOrSync(context)) {
                cancelScheduled(transfer)
                return store.get(item.fileName) ?: transfer
            }
            DownloadsForegroundService.clearPending(transfer.generation)
        }

        scheduleSystemJob(transfer)
        return store.get(item.fileName) ?: transfer
    }

    internal fun scheduleSystemJob(transfer: AndroidDownloadTransfer) {
        val wifiOnly = wifiOnlyEnabled()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                check(jobScheduler().schedule(buildJob(transfer, wifiOnly)) == JobScheduler.RESULT_SUCCESS) {
                    "Android could not start the download. Open the app and try again."
                }
            } else {
                val work = OneTimeWorkRequestBuilder<DownloadsTransferWorker>()
                    .setInputData(workDataOf(FILE_NAME to transfer.item.fileName, GENERATION to transfer.generation))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                    .build()
                val operation = WorkManager.getInstance(context).enqueueUniqueWork(
                    workName(transfer),
                    if (transfer.retryCount > 0) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                    work,
                )
                operation.result.addListener({
                    try {
                        operation.result.get()
                    } catch (error: Exception) {
                        fail(transfer, error)
                        notifyCurrent(transfer.item.fileName)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        } catch (error: Exception) {
            fail(transfer, error)
        }
    }

    @RequiresApi(34)
    internal fun buildJob(
        transfer: AndroidDownloadTransfer,
        wifiOnly: Boolean = wifiOnlyEnabled(),
    ): JobInfo =
        JobInfo.Builder(transfer.jobId, ComponentName(context, DownloadsTransferJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setEstimatedNetworkBytes(transfer.item.totalBytes ?: JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), 0L)
            .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply {
                putString(FILE_NAME, transfer.item.fileName)
                putString(GENERATION, transfer.generation)
            })
            .build()

    private val namespacedJobScheduler: JobScheduler by lazy(::createNamespacedJobScheduler)

    @RequiresApi(34)
    private fun createNamespacedJobScheduler(): JobScheduler =
        context.getSystemService(JobScheduler::class.java).forNamespace(JOB_NAMESPACE)

    @RequiresApi(34)
    internal fun jobScheduler(): JobScheduler = namespacedJobScheduler

    fun restore(item: DownloadItem): DownloadItem {
        var transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return if (item.status == DownloadStatus.Downloading) {
                item.copy(
                    status = DownloadStatus.Paused,
                    errorMessage = null,
                    pauseReason = item.pauseReason ?: DownloadPauseReasons.MISSING_TRANSFER_STATE,
                )
            } else item
        if (transfer.item.status == DownloadStatus.Downloading &&
            Build.VERSION.SDK_INT >= 34 &&
            !activeExecutions.contains(transfer.generation) &&
            !DownloadsForegroundService.isTrackingOrPending(transfer.generation) &&
            jobScheduler().getPendingJob(transfer.jobId) == null
        ) {
            pause(
                fileName = item.fileName,
                reason = DownloadPauseReasons.withDetail(
                    DownloadPauseReasons.MISSING_JOB,
                    transfer.lastStopReason,
                ),
            )
            transfer = store.get(item.fileName) ?: transfer
        }
        return transfer.item
    }

    fun reconcileActive(item: DownloadItem, nowMs: Long = System.currentTimeMillis()): DownloadItem {
        if (item.status != DownloadStatus.Downloading) return item
        val transfer = store.get(item.fileName)?.takeIf { it.item.id == item.id }
            ?: return item.copy(
                status = DownloadStatus.Paused,
                errorMessage = null,
                pauseReason = item.pauseReason ?: DownloadPauseReasons.MISSING_TRANSFER_STATE,
            )
        if (transfer.item.status != DownloadStatus.Downloading) return transfer.item
        if (activeExecutions.contains(transfer.generation) ||
            DownloadsForegroundService.isTrackingOrPending(transfer.generation, nowMs)
        ) {
            return transfer.item
        }

        if (!hasBackgroundJob(transfer)) {
            pause(
                fileName = item.fileName,
                reason = DownloadPauseReasons.withDetail(
                    DownloadPauseReasons.MISSING_JOB,
                    transfer.lastStopReason,
                ),
            )
            return store.get(item.fileName)?.item ?: transfer.item
        }

        val stopDetail = transfer.lastStopReason
        if (stopDetail != null) {
            val (category, _) = DownloadPauseReasons.parseStopDetail(stopDetail)
            if (category == DownloadPauseReasons.STOP_CATEGORY_CONNECTIVITY && wifiOnlyEnabled()) {
                return transfer.item
            }
            val stoppedAt = transfer.lastStoppedAtEpochMs ?: transfer.item.updatedAtEpochMs
            val isSystemBlocked = category == DownloadPauseReasons.STOP_CATEGORY_BACKGROUND_RESTRICTION ||
                category == DownloadPauseReasons.STOP_CATEGORY_APP_STANDBY
            val stalledInBackoff = stoppedAt > 0L && nowMs - stoppedAt >= SYSTEM_BACKOFF_STALL_GRACE_MS
            if (isSystemBlocked || stalledInBackoff) {
                pause(
                    fileName = item.fileName,
                    reason = DownloadPauseReasons.withDetail(
                        DownloadPauseReasons.WORKER_STOPPED,
                        stopDetail,
                    ),
                )
                return store.get(item.fileName)?.item ?: transfer.item
            }
        }
        return transfer.item
    }

    private fun hasBackgroundJob(transfer: AndroidDownloadTransfer): Boolean =
        if (Build.VERSION.SDK_INT >= 34) {
            jobScheduler().getPendingJob(transfer.jobId) != null
        } else {
            runCatching {
                WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWork(workName(transfer))
                    .get()
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(true)
        }

    fun pause(fileName: String, reason: String? = null) {
        val transfer = store.get(fileName) ?: return
        if (transfer.item.status != DownloadStatus.Downloading) return
        val resolvedReason = reason ?: transfer.item.pauseReason ?: DownloadPauseReasons.TRANSFER_CANCELLED
        val paused = store.update(fileName, transfer.generation) {
            it.copy(
                item = it.item.copy(
                    status = DownloadStatus.Paused,
                    errorMessage = null,
                    pauseReason = resolvedReason,
                ),
            )
        }
        activeJobs.remove(transfer.generation)?.cancel()
        DownloadsForegroundService.cancelGeneration(transfer.generation)
        cancelScheduled(transfer)
        DownloadsForegroundService.syncIfRunning(context)
        paused?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    internal fun recordSystemStop(
        transfer: AndroidDownloadTransfer,
        stopDetail: String,
        stoppedAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        updateActive(transfer) {
            it.copy(lastStopReason = stopDetail, lastStoppedAtEpochMs = stoppedAtEpochMs)
        }
    }

    fun remove(fileName: String) {
        val transfer = store.get(fileName)
        val torrentInfoHash = transfer?.item?.torrentInfoHash
        store.remove(fileName)
        transfer?.let {
            activeJobs.remove(it.generation)?.cancel()
            DownloadsForegroundService.cancelGeneration(it.generation)
            cancelScheduled(it)
            DownloadsForegroundService.syncIfRunning(context)
            DownloadsLiveStatusPlatform.removeNotification(it.item.id)
        }
        cleanupScope.launch {
            lock(fileName).withLock {
                if (store.get(fileName) == null) {
                    File(directory, "$fileName.part").delete()
                    DownloadSubtitleStorage(File(directory, fileName).toURI().toString()).remove()
                    if (!torrentInfoHash.isNullOrBlank() &&
                        store.transfers.value.values.none { it.item.torrentInfoHash == torrentInfoHash }) {
                        torrentProvider.cleanupTorrentArtifacts(torrentInfoHash)
                    }
                }
            }
        }
    }

    private fun cancelScheduled(transfer: AndroidDownloadTransfer) {
        if (Build.VERSION.SDK_INT >= 34) jobScheduler().cancel(transfer.jobId)
        else WorkManager.getInstance(context).cancelUniqueWork(workName(transfer))
    }

    internal fun applyNetworkPolicy() {
        val networkAllowed = isNetworkAllowedNow()
        val activeTransfers = store.transfers.value.values.filter { it.item.status == DownloadStatus.Downloading }
        activeTransfers.forEach { transfer ->
            cancelScheduled(transfer)
            if (!networkAllowed) {
                activeJobs.remove(transfer.generation)?.cancel()
                DownloadsForegroundService.cancelGeneration(transfer.generation)
                scheduleSystemJob(transfer)
            } else if (preferForegroundService) {
                DownloadsForegroundService.markPending(transfer.generation)
                if (!DownloadsForegroundService.startOrSync(context)) {
                    DownloadsForegroundService.clearPending(transfer.generation)
                    scheduleSystemJob(transfer)
                }
            } else {
                scheduleSystemJob(transfer)
            }
        }
        DownloadsForegroundService.syncIfRunning(context)
    }

    suspend fun execute(
        transfer: AndroidDownloadTransfer,
        network: Network? = null,
        onProgress: (AndroidDownloadTransfer) -> Unit,
    ): Boolean = lock(transfer.item.fileName).withLock {
        val fileName = transfer.item.fileName
        if (!isActive(transfer)) return@withLock false
        val destination = File(directory, fileName)
        val initialPartialBytes = File(directory, "$fileName.part").takeIf(File::isFile)?.length() ?: 0L
        val isTorrent = transfer.item.isTorrentDownload
        val client = if (network != null && !isTorrent) {
            downloadHttpClient.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { network.getAllByName(it).toList() }
                .connectionPool(ConnectionPool())
                .build()
        } else if (isTorrent) {
            torrentLoopbackHttpClient
        } else {
            downloadHttpClient
        }
        currentCoroutineContext()[Job]?.let { job -> activeJobs[transfer.generation] = job }
        activeExecutions.add(transfer.generation)
        updateActive(transfer) {
            if (it.lastStopReason != null || it.lastStoppedAtEpochMs != null) {
                it.copy(lastStopReason = null, lastStoppedAtEpochMs = null)
            } else it
        }
        val lockGuard = acquireTransferWakeLocks(context, "nuvio:download:${transfer.jobId}")
        try {
            if (!isNetworkAllowedNow()) {
                throw WifiOnlyRequiredException()
            }
            if (isTorrent && (!AppFeaturePolicy.p2pEnabled || !DownloadsPlatformDownloader.supportsTorrentDownloads)) {
                throw IllegalStateException("Torrent downloads are not supported on this build")
            }
            DownloadSubtitles.prepare(transfer.item, destination.toURI().toString())
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            if (!isNetworkAllowedNow()) {
                throw WifiOnlyRequiredException()
            }
            var lastProgressAt = 0L
            val onHeadersCallback: (Long?, String?) -> Unit = { total, validator ->
                updateActive(transfer) { it.copy(validator = validator, item = it.item.copy(totalBytes = total)) }
            }
            val onProgressCallback: (Long, Long?) -> Unit = { bytes, total ->
                if (!isNetworkAllowedNow()) {
                    throw WifiOnlyRequiredException()
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastProgressAt >= 1_000L || bytes == total) {
                    lastProgressAt = now
                    updateActive(transfer) {
                        val updatedRetryCount = if (bytes > initialPartialBytes) 0 else it.retryCount
                        it.copy(
                            retryCount = updatedRetryCount,
                            lastStopReason = null,
                            lastStoppedAtEpochMs = null,
                            item = it.item.copy(downloadedBytes = bytes, totalBytes = total),
                        )
                    }?.let(onProgress)
                }
            }
            val partial = when {
                destination.isFile -> destination
                isTorrent -> transferTorrentDownload(
                    item = transfer.item,
                    directory = directory,
                    sessionProvider = torrentProvider,
                    client = client,
                    onHeaders = onHeadersCallback,
                    onProgress = onProgressCallback,
                )
                else -> transferAndroidDownload(
                    item = transfer.item,
                    directory = directory,
                    validator = transfer.validator,
                    client = client,
                    onHeaders = onHeadersCallback,
                    onProgress = onProgressCallback,
                )
            }
            currentCoroutineContext().ensureActive()
            updateActive(transfer) { current ->
                if (partial != destination && !partial.renameTo(destination)) {
                    throw IOException("Could not finalize the downloaded file")
                }
                val bytes = destination.length()
                current.copy(item = current.item.copy(
                    status = DownloadStatus.Completed,
                    localFileUri = destination.toURI().toString(),
                    downloadedBytes = bytes,
                    totalBytes = bytes,
                    errorMessage = null,
                    pauseReason = null,
                ))
            }
            cleanupTorrentArtifactsIfUnused(transfer.item.torrentInfoHash)
            false
        } catch (wifiRequired: WifiOnlyRequiredException) {
            if (isActive(transfer)) {
                updateActive(transfer) {
                    it.copy(
                        lastStopReason = DownloadPauseReasons.encodeStopDetail(
                            DownloadPauseReasons.STOP_CATEGORY_CONNECTIVITY,
                            "WIFI_ONLY",
                        ),
                        lastStoppedAtEpochMs = System.currentTimeMillis(),
                    )
                }
                scheduleSystemJob(transfer)
            }
            false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (!isActive(transfer)) return@withLock false
            val currentPartialBytes = File(directory, "$fileName.part").takeIf(File::isFile)?.length() ?: 0L
            val currentTransfer = store.get(fileName)?.takeIf { it.generation == transfer.generation } ?: transfer
            val effectiveRetryCount = if (currentPartialBytes > initialPartialBytes) 0 else currentTransfer.retryCount
            val maxRetries = if (isTorrent) 8 else 6
            val retry = shouldRetryAndroidDownload(error, effectiveRetryCount, maxRetries)
            if (retry) updateActive(transfer) {
                it.copy(
                    retryCount = effectiveRetryCount + 1,
                    item = it.item.copy(
                        downloadedBytes = currentPartialBytes.coerceAtLeast(it.item.downloadedBytes),
                        pauseReason = null,
                    ),
                )
            } else fail(transfer, error)
            retry
        } finally {
            activeJobs.remove(transfer.generation)
            activeExecutions.remove(transfer.generation)
            lockGuard.close()
            if (network != null && !isTorrent) withContext(NonCancellable + Dispatchers.IO) { client.connectionPool.evictAll() }
        }
    }

    fun isActive(transfer: AndroidDownloadTransfer): Boolean = store.get(transfer.item.fileName)?.let {
        it.generation == transfer.generation && it.item.status == DownloadStatus.Downloading
    } == true

    fun fail(transfer: AndroidDownloadTransfer, error: Exception) {
        val message = when (error) {
            is DownloadStallException -> runCatching {
                runBlocking { getString(Res.string.downloads_error_stalled) }
            }.getOrElse { error.message ?: "Download failed" }
            else -> error.message ?: "Download failed"
        }
        updateActive(transfer) {
            it.copy(item = it.item.copy(status = DownloadStatus.Failed, errorMessage = message))
        }
    }

    fun notifyCurrent(fileName: String) {
        store.get(fileName)?.let { DownloadsLiveStatusPlatform.notifyTransfer(it.item) }
    }

    private fun updateActive(
        transfer: AndroidDownloadTransfer,
        update: (AndroidDownloadTransfer) -> AndroidDownloadTransfer,
    ): AndroidDownloadTransfer? = store.update(transfer.item.fileName, transfer.generation) {
        if (it.item.status == DownloadStatus.Downloading) {
            update(it).let { changed -> changed.copy(item = changed.item.copy(updatedAtEpochMs = System.currentTimeMillis())) }
        } else it
    }

    private suspend fun cleanupTorrentArtifactsIfUnused(infoHash: String?) {
        if (infoHash.isNullOrBlank()) return
        val stillNeeded = store.transfers.value.values.any {
            it.item.torrentInfoHash == infoHash && it.item.status != DownloadStatus.Completed
        }
        if (!stillNeeded) {
            withContext(NonCancellable) {
                torrentProvider.cleanupTorrentArtifacts(infoHash)
            }
        }
    }

    private fun lock(fileName: String): Mutex = locks.getOrPut(fileName) { Mutex() }
    private fun workName(transfer: AndroidDownloadTransfer) = "nuvio-download-${transfer.jobId}"

    companion object {
        const val FILE_NAME = "download_file_name"
        const val GENERATION = "download_generation"
        const val JOB_NAMESPACE = "nuvio-downloads"
        const val SYSTEM_BACKOFF_STALL_GRACE_MS = 25_000L
    }
}

internal fun shouldRetryAndroidDownload(error: Exception, retries: Int, maxRetries: Int = 4): Boolean =
    retries < maxRetries && when (error) {
        is DownloadHttpException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode in 500..599
        is IOException -> true
        else -> false
    }
