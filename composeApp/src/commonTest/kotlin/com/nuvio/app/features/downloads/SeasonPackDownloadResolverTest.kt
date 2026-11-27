package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonPackDownloadResolverTest {
    @Test
    fun supportsSeasonPackLookupFiltersNonEpisodeAndUnidentifiableStreams() {
        val p2pAddonStream = StreamItem(
            name = "Torrentio 1080p",
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            fileIdx = 0,
            addonName = "Torrentio",
            addonId = "addon:torrentio:https://torrentio.example/manifest.json",
            behaviorHints = StreamBehaviorHints(bingeGroup = "torrentio|1080p"),
        )
        assertTrue(
            SeasonPackDownloadResolver.supportsSeasonPackLookup(
                stream = p2pAddonStream,
                isEpisode = true,
                debridEnabled = false,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )
        assertFalse(
            SeasonPackDownloadResolver.supportsSeasonPackLookup(
                stream = p2pAddonStream,
                isEpisode = false,
                debridEnabled = false,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )
        assertFalse(
            SeasonPackDownloadResolver.supportsSeasonPackLookup(
                stream = p2pAddonStream.copy(addonId = "plugin:local"),
                isEpisode = true,
                debridEnabled = false,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )

        val plainHttpAddon = StreamItem(
            name = "Direct",
            url = "https://cdn.example.com/ep1.mp4",
            addonName = "Addon",
            addonId = "addon:direct:https://addon.example/manifest.json",
        )
        assertFalse(
            SeasonPackDownloadResolver.supportsSeasonPackLookup(
                stream = plainHttpAddon,
                isEpisode = true,
                debridEnabled = true,
            ),
        )
    }

    @Test
    fun matchSamePackStreamRequiresSameInfoHashEvenWhenBingeGroupMatches() {
        val packHash = "abcdef0123456789abcdef0123456789abcdef01"
        val otherHash = "9999999999999999999999999999999999999999"
        val anchor = StreamItem(
            name = "Torrentio 1080p",
            infoHash = packHash,
            fileIdx = 0,
            addonName = "Torrentio",
            addonId = "addon:torrentio:https://torrentio.example/manifest.json",
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "torrentio|1080p",
                filename = "Show.S01E01.1080p.mkv",
            ),
        )

        val wrongHashSameBinge = StreamItem(
            name = "Other single episode",
            infoHash = otherHash,
            fileIdx = 0,
            addonName = "Torrentio",
            addonId = anchor.addonId,
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "torrentio|1080p",
                filename = "Show.S01E02.1080p.mkv",
            ),
        )
        assertNull(
            SeasonPackDownloadResolver.matchSamePackStream(
                anchor = anchor,
                candidates = listOf(wrongHashSameBinge),
                season = 1,
                episode = 2,
                debridEnabled = false,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )

        val samePackDifferentOrder = StreamItem(
            name = "Wrong ep label",
            infoHash = packHash,
            fileIdx = 4,
            addonName = "Torrentio",
            addonId = anchor.addonId,
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "torrentio|other",
                filename = "Show.S01E05.1080p.mkv",
            ),
        )
        val exactPackMatch = StreamItem(
            name = "Matching pack file",
            infoHash = packHash.uppercase(),
            fileIdx = 1,
            addonName = "Torrentio",
            addonId = anchor.addonId,
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "torrentio|1080p",
                filename = "Show.S01E02.1080p.mkv",
            ),
        )
        val matched = assertNotNull(
            SeasonPackDownloadResolver.matchSamePackStream(
                anchor = anchor,
                candidates = listOf(wrongHashSameBinge, samePackDifferentOrder, exactPackMatch),
                season = 1,
                episode = 2,
                debridEnabled = false,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )
        assertEquals(1, matched.p2pFileIdx)
    }

    @Test
    fun matchSamePackStreamFallsBackToBingeGroupForHttpStreamsWithoutInfoHash() {
        val anchor = StreamItem(
            name = "Debrid HTTP",
            url = "https://cdn.example.com/resolve/s01e01.mkv",
            addonName = "TorrentioRD",
            addonId = "addon:torrentio:https://torrentio.example/manifest.json",
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "torrentio|rd-pack-42",
                filename = "Show.S01E01.mkv",
            ),
        )
        val unrelated = StreamItem(
            name = "Other HTTP",
            url = "https://cdn.example.com/resolve/other.mkv",
            addonName = "TorrentioRD",
            addonId = anchor.addonId,
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "torrentio|rd-pack-99",
                filename = "Show.S01E02.mkv",
            ),
        )
        val samePack = StreamItem(
            name = "Same pack E02",
            url = "https://cdn.example.com/resolve/s01e02.mkv",
            addonName = "TorrentioRD",
            addonId = anchor.addonId,
            behaviorHints = StreamBehaviorHints(
                bingeGroup = "torrentio|rd-pack-42",
                filename = "Show.S01E02.mkv",
            ),
        )
        val matched = assertNotNull(
            SeasonPackDownloadResolver.matchSamePackStream(
                anchor = anchor,
                candidates = listOf(unrelated, samePack),
                season = 1,
                episode = 2,
                debridEnabled = true,
            ),
        )
        assertEquals(samePack.url, matched.url)
    }

    @Test
    fun isSeasonPackEpisodeWatchedDetectsWatchedKeysAndCompletedProgress() {
        val watchedEpisode = MetaVideo(id = "ttshow:1:1", title = "Ep 1", season = 1, episode = 1)
        val completedByProgress = MetaVideo(id = "ttshow:1:2", title = "Ep 2", season = 1, episode = 2)
        val unwatchedEpisode = MetaVideo(id = "ttshow:1:3", title = "Ep 3", season = 1, episode = 3)

        val watchedKeys = setOf(
            watchedItemKey(type = "series", id = "ttshow", season = 1, episode = 1),
        )
        val progressByVideoId = mapOf(
            "ttshow:1:2" to WatchProgressEntry(
                contentType = "series",
                parentMetaId = "ttshow",
                parentMetaType = "series",
                videoId = "ttshow:1:2",
                title = "Show",
                seasonNumber = 1,
                episodeNumber = 2,
                lastPositionMs = 1_000_000L,
                durationMs = 1_000_000L,
                lastUpdatedEpochMs = 1L,
                isCompleted = true,
            ),
        )

        assertTrue(
            isSeasonPackEpisodeWatched(
                parentMetaId = "ttshow",
                parentMetaType = "series",
                seasonNumber = 1,
                episode = watchedEpisode,
                watchedKeys = watchedKeys,
                progressByVideoId = progressByVideoId,
            ),
        )
        assertTrue(
            isSeasonPackEpisodeWatched(
                parentMetaId = "ttshow",
                parentMetaType = "series",
                seasonNumber = 1,
                episode = completedByProgress,
                watchedKeys = watchedKeys,
                progressByVideoId = progressByVideoId,
            ),
        )
        assertFalse(
            isSeasonPackEpisodeWatched(
                parentMetaId = "ttshow",
                parentMetaType = "series",
                seasonNumber = 1,
                episode = unwatchedEpisode,
                watchedKeys = watchedKeys,
                progressByVideoId = progressByVideoId,
            ),
        )
    }
}
