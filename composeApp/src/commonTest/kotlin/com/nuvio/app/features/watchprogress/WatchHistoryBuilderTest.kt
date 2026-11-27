package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watched.WatchedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchHistoryBuilderTest {

    private fun episodeProgress(
        parentMetaId: String = "tt0903747",
        season: Int = 1,
        episode: Int = 2,
        lastPositionMs: Long,
        durationMs: Long = 2_700_000L,
        lastUpdatedEpochMs: Long,
        isCompleted: Boolean = false,
        title: String = "Breaking Bad",
        episodeTitle: String? = "Cat's in the Bag...",
        source: String = WatchProgressSourceLocal,
    ): WatchProgressEntry = WatchProgressEntry(
        contentType = "series",
        parentMetaId = parentMetaId,
        parentMetaType = "series",
        videoId = "$parentMetaId:$season:$episode",
        title = title,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = episodeTitle,
        lastPositionMs = lastPositionMs,
        durationMs = durationMs,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        isCompleted = isCompleted,
        source = source,
    )

    @Test
    fun collapsesRepeatedWatchesOfSameEpisodeToLatestState() {
        val entries = listOf(
            episodeProgress(lastPositionMs = 189_000L, lastUpdatedEpochMs = 1_000_000L),
            episodeProgress(lastPositionMs = 304_000L, lastUpdatedEpochMs = 2_000_000L),
            episodeProgress(lastPositionMs = 124_000L, lastUpdatedEpochMs = 3_000_000L),
        )

        val result = buildWatchHistoryEntries(entries, emptyList())

        assertEquals(1, result.size)
        val item = result.single()
        assertEquals(124_000L, item.positionMs)
        assertEquals(3_000_000L, item.activityEpochMs)
        assertFalse(item.isCompleted)
    }

    @Test
    fun mergesCompletedProgressAndWatchedRecordUsingLatestTimestamp() {
        val entries = listOf(
            episodeProgress(
                lastPositionMs = 2_650_000L,
                durationMs = 2_700_000L,
                lastUpdatedEpochMs = 5_000_000L,
                isCompleted = true,
            ),
        )
        val watched = listOf(
            WatchedItem(
                id = "tt0903747",
                type = "show",
                name = "Breaking Bad",
                season = 1,
                episode = 2,
                markedAtEpochMs = 5_500_000L,
            ),
        )

        val result = buildWatchHistoryEntries(entries, watched)

        assertEquals(1, result.size)
        val item = result.single()
        assertTrue(item.isCompleted)
        assertEquals(5_500_000L, item.activityEpochMs)
        assertEquals(2_700_000L, item.durationMs)
    }

    @Test
    fun newerPartialRewatchOverridesOlderWatchedCompletion() {
        val watched = listOf(
            WatchedItem(
                id = "tt0903747",
                type = "series",
                name = "Breaking Bad",
                season = 1,
                episode = 2,
                markedAtEpochMs = 1_000_000L,
            ),
        )
        val entries = listOf(
            episodeProgress(
                lastPositionMs = 124_000L,
                durationMs = 2_700_000L,
                lastUpdatedEpochMs = 9_000_000L,
                isCompleted = false,
            ),
        )

        val result = buildWatchHistoryEntries(entries, watched)

        assertEquals(1, result.size)
        val item = result.single()
        assertFalse(item.isCompleted)
        assertEquals(124_000L, item.positionMs)
        assertEquals(9_000_000L, item.activityEpochMs)
    }

    @Test
    fun preservesManualWatchedItemsWhenProgressWasCleared() {
        val watched = listOf(
            WatchedItem(
                id = "tt0903747",
                type = "series",
                name = "Breaking Bad",
                season = 2,
                episode = 1,
                markedAtEpochMs = 20261127153000L,
            ),
        )

        val result = buildWatchHistoryEntries(emptyList(), watched)

        assertEquals(1, result.size)
        val item = result.single()
        assertTrue(item.isCompleted)
        assertEquals(2, item.seasonNumber)
        assertEquals(1, item.episodeNumber)
        assertTrue(item.activityEpochMs > 1_700_000_000_000L)
    }

    @Test
    fun suppressesWholeSeriesMarkerWhenEpisodesExistAndKeepsStandaloneFallback() {
        val watched = listOf(
            WatchedItem(
                id = "tt0903747",
                type = "series",
                name = "Breaking Bad",
                season = 1,
                episode = 1,
                markedAtEpochMs = 4_000_000L,
            ),
            WatchedItem(
                id = "tt0903747",
                type = "series",
                name = "Breaking Bad",
                markedAtEpochMs = 5_000_000L,
            ),
            WatchedItem(
                id = "tt1234567",
                type = "series",
                name = "Chernobyl",
                markedAtEpochMs = 3_000_000L,
            ),
        )

        val result = buildWatchHistoryEntries(emptyList(), watched)

        assertEquals(2, result.size)
        assertEquals("tt0903747", result[0].parentMetaId)
        assertEquals(1, result[0].episodeNumber)
        assertEquals("tt1234567", result[1].parentMetaId)
        assertFalse(result[1].isEpisode)
    }

    @Test
    fun sortsMostRecentFirstAndFiltersZeroProgressAbandonedEntries() {
        val entries = listOf(
            WatchProgressEntry(
                contentType = "movie",
                parentMetaId = "tt0133093",
                parentMetaType = "movie",
                videoId = "tt0133093",
                title = "The Matrix",
                lastPositionMs = 0L,
                durationMs = 8_160_000L,
                lastUpdatedEpochMs = 9_999_999L,
                isCompleted = false,
            ),
            WatchProgressEntry(
                contentType = "movie",
                parentMetaId = "tt0468569",
                parentMetaType = "movie",
                videoId = "tt0468569",
                title = "The Dark Knight",
                lastPositionMs = 1_500_000L,
                durationMs = 9_120_000L,
                lastUpdatedEpochMs = 2_000_000L,
                isCompleted = false,
            ),
            episodeProgress(
                lastPositionMs = 2_500_000L,
                durationMs = 2_700_000L,
                lastUpdatedEpochMs = 5_000_000L,
                isCompleted = true,
            ),
        )

        val result = buildWatchHistoryEntries(entries, emptyList())

        assertEquals(2, result.size)
        assertEquals("tt0903747", result[0].parentMetaId)
        assertEquals("tt0468569", result[1].parentMetaId)

        assertEquals(1, filterWatchHistoryEntries(result, WatchHistoryFilter.COMPLETED, "").size)
        assertEquals(1, filterWatchHistoryEntries(result, WatchHistoryFilter.IN_PROGRESS, "").size)
        assertEquals(1, filterWatchHistoryEntries(result, WatchHistoryFilter.ALL, "knight").size)
        assertEquals(1, filterWatchHistoryEntries(result, WatchHistoryFilter.ALL, "S01E02").size)
    }
}
