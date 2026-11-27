package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.streams.StreamItem
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadQueueTest {
    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DownloadsStorage.initialize(context)
        DownloadsStorage.saveWifiOnly(true)
        DownloadsSettingsRepository.resetForTesting()
        DownloadsRepository.availableStorageBytesProvider = DownloadsPlatformDownloader::availableStorageBytes
        DownloadsPlatformDownloader.initialize(context)
        DownloadsPlatformDownloader.scheduler(context).apply {
            preferForegroundService = false
            unmeteredNetworkAvailable = { true }
        }
        DownloadsLiveStatusPlatform.initialize(context)
        DownloadsRepository.clearLocalState()
    }

    @After
    fun tearDown() {
        val context = RuntimeEnvironment.getApplication()
        DownloadsRepository.uiState.value.items
            .map { it.id }
            .forEach(DownloadsRepository::cancelDownload)
        DownloadsRepository.clearLocalState()
        DownloadsStorage.saveWifiOnly(true)
        DownloadsSettingsRepository.resetForTesting()
        DownloadsRepository.availableStorageBytesProvider = DownloadsPlatformDownloader::availableStorageBytes
        DownloadsPlatformDownloader.scheduler(context).apply {
            preferForegroundService = AppFeaturePolicy.downloadForegroundServiceEnabled
            unmeteredNetworkAvailable = { isAndroidUnmeteredNetworkAvailable(context) }
        }
    }

    @Test
    fun batchEnqueueCapsConcurrencyAndPromotesQueuedEpisodesInOrder() {
        val requests = (1..4).map { ep ->
            httpBatchRequest(ep)
        }

        val summary = DownloadsRepository.enqueueBatchFromStreams(requests)
        assertEquals(BatchDownloadEnqueueSummary(startedCount = 2, queuedCount = 2, failedCount = 0), summary)

        val byEpisode = DownloadsRepository.uiState.value.items.associateBy { it.episodeNumber }
        assertEquals(DownloadStatus.Downloading, byEpisode[1]?.status)
        assertEquals(DownloadStatus.Downloading, byEpisode[2]?.status)
        assertEquals(DownloadStatus.Queued, byEpisode[3]?.status)
        assertEquals(DownloadStatus.Queued, byEpisode[4]?.status)

        DownloadsRepository.pauseDownload(byEpisode.getValue(1).id)
        val afterPause = DownloadsRepository.uiState.value.items.associateBy { it.episodeNumber }
        assertEquals(DownloadStatus.Paused, afterPause[1]?.status)
        assertEquals(DownloadStatus.Downloading, afterPause[2]?.status)
        assertEquals(DownloadStatus.Downloading, afterPause[3]?.status)
        assertEquals(DownloadStatus.Queued, afterPause[4]?.status)

        DownloadsRepository.cancelDownload(afterPause.getValue(2).id)
        val afterCancel = DownloadsRepository.uiState.value.items.associateBy { it.episodeNumber }
        assertEquals(DownloadStatus.Downloading, afterCancel[3]?.status)
        assertEquals(DownloadStatus.Downloading, afterCancel[4]?.status)
    }

    @Test
    fun pauseActiveDownloadsStopsQueueWithoutPromotingAndResumeRespectsLimit() {
        val summary = DownloadsRepository.enqueueBatchFromStreams((1..4).map(::httpBatchRequest))
        assertEquals(2, summary.startedCount)
        assertEquals(2, summary.queuedCount)

        DownloadsRepository.pauseActiveDownloads()
        val pausedItems = DownloadsRepository.uiState.value.items
        assertTrue(pausedItems.all { it.status == DownloadStatus.Paused })
        assertTrue(pausedItems.all { it.pauseReason == DownloadPauseReasons.USER_APP })

        DownloadsRepository.resumePausedDownloads()
        val resumedItems = DownloadsRepository.uiState.value.items
        assertEquals(2, resumedItems.count { it.status == DownloadStatus.Downloading })
        assertEquals(2, resumedItems.count { it.status == DownloadStatus.Queued })
        assertTrue(resumedItems.all { it.pauseReason == null })
    }

    @Test
    fun sharedHashTorrentBatchRunsOnlyOneActiveTransferAtATimeOnP2pBuilds() {
        if (!AppFeaturePolicy.p2pEnabled) return
        val sharedHash = "0123456789abcdef0123456789abcdef01234567"
        val requests = (1..3).map { ep ->
            StreamBatchDownloadRequest(
                contentType = "series",
                videoId = "tt-pack:1:$ep",
                parentMetaId = "tt-pack",
                parentMetaType = "series",
                title = "Pack Show",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = 1,
                episodeNumber = ep,
                episodeTitle = "Episode $ep",
                episodeThumbnail = null,
                stream = StreamItem(
                    name = "Torrentio 1080p",
                    infoHash = sharedHash,
                    fileIdx = ep - 1,
                    addonName = "Torrentio",
                    addonId = "addon:torrentio:https://torrentio.example/manifest.json",
                ),
            )
        }

        val summary = DownloadsRepository.enqueueBatchFromStreams(requests)
        assertEquals(BatchDownloadEnqueueSummary(startedCount = 1, queuedCount = 2, failedCount = 0), summary)

        val byEpisode = DownloadsRepository.uiState.value.items.associateBy { it.episodeNumber }
        assertEquals(DownloadStatus.Downloading, byEpisode[1]?.status)
        assertEquals(DownloadStatus.Queued, byEpisode[2]?.status)
        assertEquals(DownloadStatus.Queued, byEpisode[3]?.status)

        // Prioritizing episode 3 moves it ahead of episode 2 without exceeding one active transfer per hash.
        DownloadsRepository.startQueuedDownloadNow(byEpisode.getValue(3).id)
        DownloadsRepository.pauseDownload(byEpisode.getValue(1).id)
        val afterPromotion = DownloadsRepository.uiState.value.items.associateBy { it.episodeNumber }
        assertEquals(DownloadStatus.Downloading, afterPromotion[3]?.status)
        assertEquals(DownloadStatus.Queued, afterPromotion[2]?.status)
    }

    @Test
    fun reconcileActiveDownloadsDetectsDroppedJobPromotesQueueAndPlayCreatesNewBackgroundJob() {
        val context = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(context)
        val summary = DownloadsRepository.enqueueBatchFromStreams((1..3).map(::httpBatchRequest))
        assertEquals(2, summary.startedCount)
        assertEquals(1, summary.queuedCount)

        val byEpisode = DownloadsRepository.uiState.value.items.associateBy { it.episodeNumber }
        val ep1 = byEpisode.getValue(1)
        val ep2 = byEpisode.getValue(2)
        val initialEp1Transfer = assertNotNull(scheduler.store.get(ep1.fileName))
        val initialEp2Transfer = assertNotNull(scheduler.store.get(ep2.fileName))

        // Simulate Android killing ep1's JobScheduler task while the app stays open.
        scheduler.jobScheduler().cancel(initialEp1Transfer.jobId)
        DownloadsRepository.reconcileActiveDownloads()

        val afterReconcile = DownloadsRepository.uiState.value.items.associateBy { it.episodeNumber }
        assertEquals(DownloadStatus.Paused, afterReconcile[1]?.status)
        assertEquals(DownloadPauseReasons.MISSING_JOB, afterReconcile[1]?.pauseReason)
        assertEquals(DownloadStatus.Downloading, afterReconcile[3]?.status)

        // Restarting an active download creates a fresh JobScheduler job & generation cleanly.
        DownloadsRepository.restartDownload(ep2.id)
        val restartedEp2Transfer = assertNotNull(scheduler.store.get(ep2.fileName))
        assertNotEquals(initialEp2Transfer.jobId, restartedEp2Transfer.jobId)
        assertNotEquals(initialEp2Transfer.generation, restartedEp2Transfer.generation)
        assertNull(scheduler.jobScheduler().getPendingJob(initialEp2Transfer.jobId))
        assertNotNull(scheduler.jobScheduler().getPendingJob(restartedEp2Transfer.jobId))

        // Pressing Play on paused ep1 creates a brand-new background job (or queues cleanly if at cap).
        DownloadsRepository.pauseDownload(afterReconcile.getValue(3).id)
        DownloadsRepository.resumeDownload(ep1.id)
        val resumedEp1Transfer = assertNotNull(scheduler.store.get(ep1.fileName))
        assertEquals(DownloadStatus.Downloading, DownloadsRepository.uiState.value.items.first { it.id == ep1.id }.status)
        assertNotEquals(initialEp1Transfer.jobId, resumedEp1Transfer.jobId)
        assertNotEquals(initialEp1Transfer.generation, resumedEp1Transfer.generation)
        assertNotNull(scheduler.jobScheduler().getPendingJob(resumedEp1Transfer.jobId))
    }

    @Test
    fun enqueueRejectsDownloadsWhenStorageInsufficientWithoutDeletingExisting() {
        val gb = 1024L * 1024L * 1024L
        DownloadsRepository.availableStorageBytesProvider = { 10L * gb }
        val firstResult = DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-movie",
            parentMetaId = "tt-movie",
            parentMetaType = "movie",
            title = "Large Movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "HTTP 1080p",
                url = "https://example.com/movie-small.mp4",
                addonName = "TestAddon",
                addonId = "addon:test:https://addon.example/manifest.json",
                behaviorHints = com.nuvio.app.features.streams.StreamBehaviorHints(videoSize = 1L * gb),
            ),
        )
        assertEquals(DownloadEnqueueResult.Started, firstResult)

        // Attempting to replace with a 20 GB file when only 10 GB is free must fail and keep the existing download.
        val replaceResult = DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-movie",
            parentMetaId = "tt-movie",
            parentMetaType = "movie",
            title = "Large Movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "HTTP 4K",
                url = "https://example.com/movie-4k.mp4",
                addonName = "TestAddon",
                addonId = "addon:test:https://addon.example/manifest.json",
                behaviorHints = com.nuvio.app.features.streams.StreamBehaviorHints(videoSize = 20L * gb),
            ),
        )
        assertEquals(DownloadEnqueueResult.InsufficientStorage, replaceResult)
        assertEquals(
            "https://example.com/movie-small.mp4",
            DownloadsRepository.uiState.value.items.single().sourceUrl,
        )

        // Even when file size is unknown, less than 256 MB headroom is rejected.
        DownloadsRepository.availableStorageBytesProvider = { 100L * 1024L * 1024L }
        val lowHeadroomResult = DownloadsRepository.enqueueFromStream(
            contentType = "movie",
            videoId = "tt-other",
            parentMetaId = "tt-other",
            parentMetaType = "movie",
            title = "Other Movie",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = null,
            episodeNumber = null,
            episodeTitle = null,
            episodeThumbnail = null,
            stream = StreamItem(
                name = "HTTP",
                url = "https://example.com/other.mp4",
                addonName = "TestAddon",
                addonId = "addon:test:https://addon.example/manifest.json",
            ),
        )
        assertEquals(DownloadEnqueueResult.InsufficientStorage, lowHeadroomResult)
    }

    @Test
    fun batchEnqueueStopsOnceCumulativeRemainingStorageExceedsFreeSpace() {
        val gb = 1024L * 1024L * 1024L
        // 4.5 GB free allows two 2 GB episodes (4 GB + 256 MB headroom <= 4.5 GB), rejecting ep 3 and ep 4.
        DownloadsRepository.availableStorageBytesProvider = { (4L * gb) + (512L * 1024L * 1024L) }

        val requests = (1..4).map { ep ->
            httpBatchRequest(ep, videoSizeBytes = 2L * gb)
        }
        val summary = DownloadsRepository.enqueueBatchFromStreams(requests)

        assertEquals(
            BatchDownloadEnqueueSummary(
                startedCount = 2,
                queuedCount = 0,
                failedCount = 2,
                insufficientStorageCount = 2,
            ),
            summary,
        )
        assertEquals(listOf(1, 2), DownloadsRepository.uiState.value.items.mapNotNull { it.episodeNumber })
    }

    private fun httpBatchRequest(
        episodeNumber: Int,
        videoSizeBytes: Long? = null,
    ): StreamBatchDownloadRequest =
        StreamBatchDownloadRequest(
            contentType = "series",
            videoId = "tt-series:1:$episodeNumber",
            parentMetaId = "tt-series",
            parentMetaType = "series",
            title = "Test Series",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            episodeTitle = "Episode $episodeNumber",
            episodeThumbnail = null,
            stream = StreamItem(
                name = "HTTP 1080p",
                url = "https://example.com/series/s01e0$episodeNumber.mp4",
                addonName = "TestAddon",
                addonId = "addon:test:https://addon.example/manifest.json",
                behaviorHints = com.nuvio.app.features.streams.StreamBehaviorHints(videoSize = videoSizeBytes),
            ),
        )
}
