package com.nuvio.app.features.trakt

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressSourceLocal
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktHistory
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktShowProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraktNuvioMirrorSyncTest {

    @Test
    fun `computes synthetic duration and proportional position for new Trakt playback entries`() {
        val traktPlayback = WatchProgressEntry(
            contentType = "series",
            parentMetaId = "tt1234567",
            parentMetaType = "series",
            videoId = "tt1234567:2:5",
            title = "Show Title",
            seasonNumber = 2,
            episodeNumber = 5,
            episodeTitle = "Ep 5",
            lastPositionMs = 0L,
            durationMs = 0L,
            lastUpdatedEpochMs = 50_000L,
            progressPercent = 42.5f,
            source = WatchProgressSourceTraktPlayback,
        )

        val updates = computeTraktProgressMirrorUpdates(
            traktEntries = listOf(traktPlayback),
            existingNuvioEntries = emptyList(),
        )

        assertEquals(1, updates.size)
        val mirrored = updates.single()
        assertEquals(WatchProgressSourceLocal, mirrored.source)
        assertEquals(TRAKT_MIRROR_SYNTHETIC_DURATION_MS, mirrored.durationMs)
        assertEquals(1_530_000L, mirrored.lastPositionMs)
        assertEquals("tt1234567_s2e5", mirrored.progressKey)
        assertNull(mirrored.progressPercent)
        assertFalse(mirrored.isCompleted)
    }

    @Test
    fun `ignores completed seeds, trakt-only ids, and server entries when mirroring playback`() {
        val entries = listOf(
            WatchProgressEntry(
                contentType = "series",
                parentMetaId = "tt1",
                parentMetaType = "series",
                videoId = "tt1:1:1",
                title = "Completed History",
                seasonNumber = 1,
                episodeNumber = 1,
                lastPositionMs = 1L,
                durationMs = 1L,
                lastUpdatedEpochMs = 10_000L,
                isCompleted = true,
                progressPercent = 100f,
                source = WatchProgressSourceTraktHistory,
            ),
            WatchProgressEntry(
                contentType = "series",
                parentMetaId = "tt2",
                parentMetaType = "series",
                videoId = "tt2:1:3",
                title = "Show Seed",
                seasonNumber = 1,
                episodeNumber = 3,
                lastPositionMs = 1L,
                durationMs = 1L,
                lastUpdatedEpochMs = 10_000L,
                isCompleted = true,
                progressPercent = 100f,
                source = WatchProgressSourceTraktShowProgress,
            ),
            WatchProgressEntry(
                contentType = "movie",
                parentMetaId = "trakt:999",
                parentMetaType = "movie",
                videoId = "trakt:999",
                title = "Trakt Only Id",
                lastPositionMs = 0L,
                durationMs = 0L,
                lastUpdatedEpochMs = 10_000L,
                progressPercent = 30f,
                source = WatchProgressSourceTraktPlayback,
            ),
        )

        val updates = computeTraktProgressMirrorUpdates(
            traktEntries = entries,
            existingNuvioEntries = emptyList(),
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun `reuses existing real duration when Trakt progress is newer and skips close or older entries`() {
        val realDuration = 2_700_000L
        val existingMovieNewer = WatchProgressEntry(
            contentType = "movie",
            parentMetaId = "tt111",
            parentMetaType = "movie",
            videoId = "tt111",
            title = "Movie 1",
            poster = "https://example.com/p1.jpg",
            lastPositionMs = 900_000L,
            durationMs = realDuration,
            lastUpdatedEpochMs = 90_000L,
        )
        val existingSeriesOlderSamePercent = WatchProgressEntry(
            contentType = "series",
            parentMetaId = "tt222",
            parentMetaType = "series",
            videoId = "tt222:1:2",
            title = "Series 2",
            seasonNumber = 1,
            episodeNumber = 2,
            lastPositionMs = 1_350_000L,
            durationMs = realDuration,
            lastUpdatedEpochMs = 20_000L,
        )
        val existingSeriesOlderChangedPercent = WatchProgressEntry(
            contentType = "series",
            parentMetaId = "tt333",
            parentMetaType = "series",
            videoId = "tt333:1:4",
            title = "Series 3",
            poster = "https://example.com/p3.jpg",
            seasonNumber = 1,
            episodeNumber = 4,
            lastPositionMs = 540_000L,
            durationMs = realDuration,
            lastUpdatedEpochMs = 20_000L,
        )

        val traktUpdates = listOf(
            WatchProgressEntry(
                contentType = "movie",
                parentMetaId = "tt111",
                parentMetaType = "movie",
                videoId = "tt111",
                title = "Movie 1",
                lastPositionMs = 0L,
                durationMs = 0L,
                lastUpdatedEpochMs = 80_000L,
                progressPercent = 70f,
                source = WatchProgressSourceTraktPlayback,
            ),
            WatchProgressEntry(
                contentType = "series",
                parentMetaId = "tt222",
                parentMetaType = "series",
                videoId = "tt222:1:2",
                title = "Series 2",
                seasonNumber = 1,
                episodeNumber = 2,
                lastPositionMs = 0L,
                durationMs = 0L,
                lastUpdatedEpochMs = 60_000L,
                progressPercent = 50.5f,
                source = WatchProgressSourceTraktPlayback,
            ),
            WatchProgressEntry(
                contentType = "series",
                parentMetaId = "tt333",
                parentMetaType = "series",
                videoId = "tt333:1:4",
                title = "Series 3",
                seasonNumber = 1,
                episodeNumber = 4,
                lastPositionMs = 0L,
                durationMs = 0L,
                lastUpdatedEpochMs = 60_000L,
                progressPercent = 60f,
                source = WatchProgressSourceTraktPlayback,
            ),
        )

        val updates = computeTraktProgressMirrorUpdates(
            traktEntries = traktUpdates,
            existingNuvioEntries = listOf(
                existingMovieNewer,
                existingSeriesOlderSamePercent,
                existingSeriesOlderChangedPercent,
            ),
        )

        assertEquals(1, updates.size)
        val mirrored = updates.single()
        assertEquals("tt333", mirrored.parentMetaId)
        assertEquals(realDuration, mirrored.durationMs)
        assertEquals(1_620_000L, mirrored.lastPositionMs)
        assertEquals("https://example.com/p3.jpg", mirrored.poster)
    }

    @Test
    fun `watched mirror only adds missing or meaningfully newer safe items`() {
        val nuvioOnly = WatchedItem(
            id = "tt999",
            type = "movie",
            name = "Nuvio Only",
            markedAtEpochMs = 100_000L,
        )
        val alreadyMirrored = WatchedItem(
            id = "tt100",
            type = "movie",
            name = "Same",
            markedAtEpochMs = 200_000L,
        )
        val traktOlderDuplicate = alreadyMirrored.copy(markedAtEpochMs = 210_000L)
        val traktNewEpisode = WatchedItem(
            id = "tmdb:500",
            type = "series",
            name = "Show",
            season = 1,
            episode = 3,
            markedAtEpochMs = 500_000L,
        )
        val traktOnlyUnsafe = WatchedItem(
            id = "trakt:42",
            type = "movie",
            name = "Unsafe",
            markedAtEpochMs = 600_000L,
        )

        val additions = computeTraktWatchedMirrorAdditions(
            traktItems = listOf(traktOlderDuplicate, traktNewEpisode, traktOnlyUnsafe),
            existingNuvioItems = listOf(nuvioOnly, alreadyMirrored),
        )

        assertEquals(listOf(traktNewEpisode), additions)
    }
}
