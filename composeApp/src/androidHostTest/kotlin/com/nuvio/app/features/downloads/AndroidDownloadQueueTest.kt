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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDownloadQueueTest {
    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        DownloadsStorage.initialize(context)
        DownloadsPlatformDownloader.initialize(context)
        DownloadsLiveStatusPlatform.initialize(context)
        DownloadsRepository.clearLocalState()
    }

    @After
    fun tearDown() {
        DownloadsRepository.uiState.value.items
            .map { it.id }
            .forEach(DownloadsRepository::cancelDownload)
        DownloadsRepository.clearLocalState()
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

    private fun httpBatchRequest(episodeNumber: Int): StreamBatchDownloadRequest =
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
            ),
        )
}
