package com.nuvio.app.features.downloads

import android.app.job.JobInfo
import android.net.NetworkCapabilities
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class AndroidDownloadLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun scheduledDownloadsArePersistedUserInitiatedTransfers() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context).apply { preferForegroundService = false }
        val transfer = AndroidDownloadStore(temporary.newFolder()).begin(downloadItem())

        val job = scheduler.buildJob(transfer)

        assertTrue(job.isUserInitiated)
        assertTrue(job.isPersisted)
        assertNotNull(job.requiredNetwork)
        assertEquals(JobInfo.NETWORK_BYTES_UNKNOWN.toLong(), job.estimatedNetworkDownloadBytes)
        assertEquals(transfer.generation, job.extras.getString(AndroidDownloadScheduler.GENERATION))
        assertEquals(transfer.item.fileName, job.extras.getString(AndroidDownloadScheduler.FILE_NAME))
        assertEquals(DownloadsTransferJobService::class.java.name, job.service.className)
        assertEquals(2, job.extras.size())
    }

    @Test
    fun transferStateAndCredentialsSurviveProcessRecreation() {
        val directory = temporary.newFolder()
        val store = AndroidDownloadStore(directory)
        val transfer = store.begin(downloadItem())
        store.update(transfer.item.fileName, transfer.generation) {
            it.copy(validator = "\"v1\"", item = it.item.copy(downloadedBytes = 12L, totalBytes = 100L))
        }

        val restored = assertNotNull(AndroidDownloadStore(directory).get(transfer.item.fileName))

        assertEquals(DownloadStatus.Downloading, restored.item.status)
        assertEquals(12L, restored.item.downloadedBytes)
        assertEquals(transfer.item.sourceHeaders, restored.item.sourceHeaders)
        assertEquals("\"v1\"", restored.validator)
        assertEquals(transfer.generation, restored.generation)
        assertEquals(transfer.jobId, restored.jobId)
    }

    @Test
    fun missingSystemJobIsPausedInsteadOfSilentlyRescheduling() {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication())
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "stopped.mkv"))

        assertEquals(DownloadStatus.Paused, scheduler.restore(transfer.item).status)
    }

    @Test
    fun staleWorkerCannotOverwriteAResumedTransfer() {
        val store = AndroidDownloadStore(temporary.newFolder())
        val first = store.begin(downloadItem())
        store.update(first.item.fileName, first.generation) { it.copy(item = it.item.copy(status = DownloadStatus.Paused)) }
        val resumed = store.begin(first.item)

        store.update(first.item.fileName, first.generation) { it.copy(item = it.item.copy(status = DownloadStatus.Failed)) }

        assertNotEquals(first.generation, resumed.generation)
        assertEquals(resumed, store.get(first.item.fileName))
    }

    @Test
    fun backgroundExecutionDoesNotNeedRepositoryOrActivityCallbacks(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("complete video"))
            val scheduler = AndroidDownloadScheduler(context).apply {
                preferForegroundService = false
                unmeteredNetworkAvailable = { true }
            }
            val item = downloadItem(server.url("/video").toString(), "background").copy(fileName = "background.mkv")
            val transfer = scheduler.store.begin(item)

            assertFalse(scheduler.execute(transfer) { })

            val recreated = AndroidDownloadScheduler(context)
            val restored = recreated.restore(item)
            assertEquals(DownloadStatus.Completed, restored.status)
            assertEquals("complete video", File(recreated.directory, item.fileName).readText())
            assertEquals(14L, restored.downloadedBytes)
            assertNotNull(restored.localFileUri)
        }
    }

    @Test
    fun pausedTransferIsNotRestartedBySystemRedelivery(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context).apply { preferForegroundService = false }
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "paused.mkv"))
        scheduler.store.update(transfer.item.fileName, transfer.generation) {
            it.copy(item = it.item.copy(status = DownloadStatus.Paused))
        }

        assertFalse(scheduler.execute(transfer) { error("Paused download must not transfer data") })
        assertEquals(DownloadStatus.Paused, scheduler.restore(transfer.item).status)
    }

    @Test
    fun completedRenameIsRecoveredAfterProcessDeathBeforeStateCommit(): Unit = runBlocking {
        val scheduler = AndroidDownloadScheduler(RuntimeEnvironment.getApplication()).apply {
            unmeteredNetworkAvailable = { true }
        }
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "finalized.mkv"))
        scheduler.directory.mkdirs()
        File(scheduler.directory, transfer.item.fileName).writeText("final data")

        assertFalse(scheduler.execute(transfer) { })

        assertEquals(DownloadStatus.Completed, scheduler.store.get(transfer.item.fileName)?.item?.status)
        assertEquals(10L, scheduler.store.get(transfer.item.fileName)?.item?.downloadedBytes)
    }

    @Test
    fun enqueueReschedulesMissingSystemJobOnApi34() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context).apply { preferForegroundService = false }
        val existing = scheduler.store.begin(downloadItem().copy(fileName = "requeue.mkv"))
        val jobScheduler = scheduler.jobScheduler()
        assertEquals(null, jobScheduler.getPendingJob(existing.jobId))

        val transfer = scheduler.enqueue(existing.item)

        assertEquals(existing.generation, transfer.generation)
        assertEquals(DownloadStatus.Downloading, transfer.item.status)
        assertNotNull(jobScheduler.getPendingJob(transfer.jobId))
    }

    @Test
    fun missingSystemJobRecordsDiagnosticStopReasonOnRestore() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context).apply { preferForegroundService = false }
        val transfer = scheduler.store.begin(downloadItem().copy(fileName = "timed-out.mkv"))
        val timeoutDetail = DownloadPauseReasons.encodeStopDetail(
            DownloadPauseReasons.STOP_CATEGORY_TIMEOUT,
            "STOP_REASON_TIMEOUT",
        )
        scheduler.recordSystemStop(transfer, timeoutDetail)

        val restored = scheduler.restore(transfer.item)
        assertEquals(DownloadStatus.Paused, restored.status)
        assertEquals("missing_job|TIMEOUT:STOP_REASON_TIMEOUT", restored.pauseReason)
    }

    @Test
    fun restoreDoesNotPauseActivelyExecutingTransfer(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val started = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    started.countDown()
                    allowFinish.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody("active payload")
                }
            }
            val scheduler = AndroidDownloadScheduler(context).apply {
                preferForegroundService = false
                unmeteredNetworkAvailable = { true }
            }
            val item = downloadItem(server.url("/active").toString(), "active-exec").copy(fileName = "active.mkv")
            val transfer = scheduler.store.begin(item)
            val job = launch(Dispatchers.IO) { scheduler.execute(transfer) { } }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            val restoredDuringExecution = scheduler.restore(item)
            assertEquals(DownloadStatus.Downloading, restoredDuringExecution.status)

            allowFinish.countDown()
            job.join()
            assertEquals(DownloadStatus.Completed, scheduler.store.get(item.fileName)?.item?.status)
        }
    }

    @Test
    fun shouldRetryRespectsExplicitHigherMaxRetries() {
        assertFalse(shouldRetryAndroidDownload(IOException("reset"), 4))
        assertTrue(shouldRetryAndroidDownload(IOException("reset"), 4, maxRetries = 8))
        assertFalse(shouldRetryAndroidDownload(IOException("reset"), 8, maxRetries = 8))
    }

    @Test
    fun reconcileActiveDetectsMissingJobAndForceRecreateAllocatesNewBackgroundJob() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context).apply { preferForegroundService = false }
        val initial = scheduler.enqueue(downloadItem().copy(fileName = "live-missing.mkv"))
        val jobScheduler = scheduler.jobScheduler()
        assertNotNull(jobScheduler.getPendingJob(initial.jobId))

        jobScheduler.cancel(initial.jobId)
        val reconciled = scheduler.reconcileActive(initial.item)
        assertEquals(DownloadStatus.Paused, reconciled.status)
        assertEquals(DownloadPauseReasons.MISSING_JOB, reconciled.pauseReason)

        val recreated = scheduler.enqueue(reconciled, forceRecreate = true)
        assertEquals(DownloadStatus.Downloading, recreated.item.status)
        assertNotEquals(initial.jobId, recreated.jobId)
        assertNotEquals(initial.generation, recreated.generation)
        assertNotNull(jobScheduler.getPendingJob(recreated.jobId))
    }

    @Test
    fun reconcileActivePausesStalledSystemBackoffAfterGraceWindow() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = AndroidDownloadScheduler(context).apply { preferForegroundService = false }
        val transfer = scheduler.enqueue(downloadItem().copy(fileName = "backoff-stall.mkv"))
        val quotaDetail = DownloadPauseReasons.encodeStopDetail(
            DownloadPauseReasons.STOP_CATEGORY_QUOTA,
            "STOP_REASON_QUOTA",
        )
        scheduler.recordSystemStop(transfer, quotaDetail, stoppedAtEpochMs = 1_000L)

        val withinGrace = scheduler.reconcileActive(
            transfer.item,
            nowMs = 1_000L + AndroidDownloadScheduler.SYSTEM_BACKOFF_STALL_GRACE_MS - 1_000L,
        )
        assertEquals(DownloadStatus.Downloading, withinGrace.status)
        assertNotNull(scheduler.jobScheduler().getPendingJob(transfer.jobId))

        val pastGrace = scheduler.reconcileActive(
            transfer.item,
            nowMs = 1_000L + AndroidDownloadScheduler.SYSTEM_BACKOFF_STALL_GRACE_MS + 1_000L,
        )
        assertEquals(DownloadStatus.Paused, pastGrace.status)
        assertEquals("worker_stopped|QUOTA:STOP_REASON_QUOTA", pastGrace.pauseReason)
        assertEquals(null, scheduler.jobScheduler().getPendingJob(transfer.jobId))
    }

    @Test
    fun wifiOnlyBlocksMeteredTransferUntilWifiOrCellularEnabled(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("cellular ok"))
            var wifiOnly = true
            var onWifi = false
            val scheduler = AndroidDownloadScheduler(context).apply {
                preferForegroundService = false
                wifiOnlyEnabled = { wifiOnly }
                unmeteredNetworkAvailable = { onWifi }
            }
            val item = downloadItem(server.url("/video").toString(), "wifi-policy").copy(fileName = "wifi-policy.mkv")
            val transfer = scheduler.enqueue(item)

            val meteredJob = assertNotNull(scheduler.jobScheduler().getPendingJob(transfer.jobId))
            assertTrue(meteredJob.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)

            // Attempting to execute on metered network aborts transfer early without retrying or pausing user state.
            assertFalse(scheduler.execute(transfer) { })
            assertEquals(0, server.requestCount)
            val afterBlocked = assertNotNull(scheduler.store.get(item.fileName))
            assertEquals(DownloadStatus.Downloading, afterBlocked.item.status)
            assertEquals(
                DownloadPauseReasons.encodeStopDetail(DownloadPauseReasons.STOP_CATEGORY_CONNECTIVITY, "WIFI_ONLY"),
                afterBlocked.lastStopReason,
            )
            // Connectivity stop under wifiOnly is intentionally kept waiting, not paused as stalled.
            val reconciled = scheduler.reconcileActive(
                afterBlocked.item,
                nowMs = System.currentTimeMillis() + AndroidDownloadScheduler.SYSTEM_BACKOFF_STALL_GRACE_MS + 60_000L,
            )
            assertEquals(DownloadStatus.Downloading, reconciled.status)

            // Disabling Wi-Fi-only re-schedules any-network job and allows immediate execution over cellular.
            wifiOnly = false
            scheduler.applyNetworkPolicy()
            val cellularJob = assertNotNull(scheduler.jobScheduler().getPendingJob(transfer.jobId))
            assertFalse(cellularJob.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true)

            assertFalse(scheduler.execute(afterBlocked) { })
            assertEquals(DownloadStatus.Completed, scheduler.store.get(item.fileName)?.item?.status)
            assertEquals("cellular ok", File(scheduler.directory, item.fileName).readText())
        }
    }
}
