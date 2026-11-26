package com.nuvio.app.features.downloads

import com.nuvio.app.features.p2p.canonicalP2pInfoHash
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSubtitle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadsModelsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val base32Hash = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val hexHash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `download item torrent fields default safely for legacy json payloads`() {
        val legacy = DownloadItem(
            id = "dl_1",
            contentType = "movie",
            parentMetaId = "tt1234",
            parentMetaType = "movie",
            videoId = "tt1234",
            title = "Example",
            streamTitle = "1080p",
            providerName = "Addon",
            sourceUrl = "https://cdn.example.com/movie.mp4",
            fileName = "Example_dl_1.mp4",
            status = DownloadStatus.Completed,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
        assertFalse(legacy.isTorrentDownload)

        val decoded = json.decodeFromString<DownloadItem>(
            """
            {
              "id": "dl_1",
              "contentType": "movie",
              "parentMetaId": "tt1234",
              "parentMetaType": "movie",
              "videoId": "tt1234",
              "title": "Example",
              "streamTitle": "1080p",
              "providerName": "Addon",
              "sourceUrl": "https://cdn.example.com/movie.mp4",
              "fileName": "Example_dl_1.mp4",
              "status": "Completed",
              "createdAtEpochMs": 1,
              "updatedAtEpochMs": 1
            }
            """.trimIndent(),
        )
        assertFalse(decoded.isTorrentDownload)
        assertNull(decoded.torrentInfoHash)
        assertNull(decoded.torrentFileIdx)
        assertTrue(decoded.torrentTrackers.isEmpty())
    }

    @Test
    fun `download item roundtrips torrent metadata cleanly`() {
        val item = DownloadItem(
            id = "dl_2",
            contentType = "series",
            parentMetaId = "tt9999",
            parentMetaType = "series",
            videoId = "tt9999:1:4",
            title = "Show",
            seasonNumber = 1,
            episodeNumber = 4,
            streamTitle = "Season Pack S01",
            providerName = "TorrentAddon",
            sourceUrl = "torrent://$hexHash?index=4",
            torrentInfoHash = hexHash,
            torrentFileIdx = 4,
            torrentFilename = "Show.S01E04.mkv",
            torrentTrackers = listOf("udp://tracker.example:6969/announce"),
            fileName = "Show S01E04_dl_2.mkv",
            status = DownloadStatus.Downloading,
            totalBytes = 2_000_000L,
            createdAtEpochMs = 10L,
            updatedAtEpochMs = 10L,
        )

        assertTrue(item.isTorrentDownload)
        val roundtripped = json.decodeFromString<DownloadItem>(json.encodeToString(item))
        assertEquals(item, roundtripped)
    }

    @Test
    fun `isSupportedDownloadUrl accepts http video urls and rejects streaming manifests or magnets`() {
        assertTrue("https://cdn.example.com/video.mkv?token=1".isSupportedDownloadUrl())
        assertTrue("http://cdn.example.com/stream".isSupportedDownloadUrl())
        assertFalse("magnet:?xt=urn:btih:$hexHash".isSupportedDownloadUrl())
        assertFalse("https://cdn.example.com/master.m3u8?token=1".isSupportedDownloadUrl())
        assertFalse("https://cdn.example.com/manifest.mpd".isSupportedDownloadUrl())
        assertFalse("https://cdn.example.com/file.torrent".isSupportedDownloadUrl())
        assertFalse("torrent://$hexHash".isSupportedDownloadUrl())
    }

    @Test
    fun `buildDownloadItemFromStream maps torrent base32 hash fileIdx filename subtitles and rejects disabled builds`() {
        val stream = StreamItem(
            name = "1080p Pack",
            url = "magnet:?xt=urn:btih:$base32Hash&tr=udp%3A%2F%2Ftracker.example%3A6969%2Fannounce",
            fileIdx = 3,
            sources = listOf("tracker:https://tracker.two/announce"),
            addonName = "P2P",
            addonId = "addon.p2p",
            behaviorHints = StreamBehaviorHints(
                filename = "Show.S01E03.1080p.mkv",
                videoSize = 987_654_321L,
            ),
            externalSubtitles = listOf(
                StreamSubtitle(url = "https://subs.example.com/en.srt", language = "eng", name = "English"),
            ),
        )

        val disabledPolicy = buildDownloadItemFromStream(
            downloadId = "d1",
            nowEpochMs = 100L,
            contentType = "series",
            videoId = "tt1:1:3",
            parentMetaId = "tt1",
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 3,
            episodeTitle = "Episode Three",
            episodeThumbnail = null,
            stream = stream,
            p2pFeatureEnabled = false,
            platformSupportsTorrentDownloads = true,
        )
        assertEquals(DownloadEnqueueResult.UnsupportedFormat, assertIs<StreamDownloadBuildResult.Failure>(disabledPolicy).result)

        val disabledPlatform = buildDownloadItemFromStream(
            downloadId = "d1",
            nowEpochMs = 100L,
            contentType = "series",
            videoId = "tt1:1:3",
            parentMetaId = "tt1",
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 3,
            episodeTitle = "Episode Three",
            episodeThumbnail = null,
            stream = stream,
            p2pFeatureEnabled = true,
            platformSupportsTorrentDownloads = false,
        )
        assertEquals(DownloadEnqueueResult.UnsupportedFormat, assertIs<StreamDownloadBuildResult.Failure>(disabledPlatform).result)

        val built = buildDownloadItemFromStream(
            downloadId = "d1",
            nowEpochMs = 100L,
            contentType = "series",
            videoId = "tt1:1:3",
            parentMetaId = "tt1",
            parentMetaType = "series",
            title = "Show",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 3,
            episodeTitle = "Episode Three",
            episodeThumbnail = null,
            stream = stream,
            p2pFeatureEnabled = true,
            platformSupportsTorrentDownloads = true,
        )
        val item = assertIs<StreamDownloadBuildResult.Success>(built).item
        val canonicalHex = canonicalP2pInfoHash(base32Hash)
        assertTrue(item.isTorrentDownload)
        assertEquals(canonicalHex, item.torrentInfoHash)
        assertEquals(3, item.torrentFileIdx)
        assertEquals("Show.S01E03.1080p.mkv", item.torrentFilename)
        assertEquals("torrent://$canonicalHex?index=3", item.sourceUrl)
        assertEquals(
            listOf("https://tracker.two/announce", "udp://tracker.example:6969/announce"),
            item.torrentTrackers,
        )
        assertTrue(item.fileName.endsWith("_d1.mkv"))
        assertEquals(987_654_321L, item.totalBytes)
        assertEquals(1, item.sourceSubtitles.size)
        assertEquals(DownloadStatus.Downloading, item.status)
    }

    @Test
    fun `buildDownloadItemFromStream falls back to mp4 for year-like torrent filename and rejects missing or invalid sources`() {
        val yearNamedTorrent = StreamItem(
            infoHash = hexHash,
            addonName = "P2P",
            addonId = "addon.p2p",
            behaviorHints = StreamBehaviorHints(filename = "Movie.2024"),
        )
        val built = assertIs<StreamDownloadBuildResult.Success>(
            buildDownloadItemFromStream(
                downloadId = "d2",
                nowEpochMs = 100L,
                contentType = "movie",
                videoId = "tt2",
                parentMetaId = "tt2",
                parentMetaType = "movie",
                title = "Movie",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                stream = yearNamedTorrent,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )
        assertTrue(built.item.fileName.endsWith("_d2.mp4"))

        val badTorrent = StreamItem(url = "torrent://null", addonName = "P2P", addonId = "addon.p2p")
        val badResult = assertIs<StreamDownloadBuildResult.Failure>(
            buildDownloadItemFromStream(
                downloadId = "d3",
                nowEpochMs = 100L,
                contentType = "movie",
                videoId = "tt3",
                parentMetaId = "tt3",
                parentMetaType = "movie",
                title = "Bad",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                stream = badTorrent,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )
        assertEquals(DownloadEnqueueResult.UnsupportedFormat, badResult.result)

        val emptyStream = StreamItem(addonName = "Empty", addonId = "addon.empty")
        val emptyResult = assertIs<StreamDownloadBuildResult.Failure>(
            buildDownloadItemFromStream(
                downloadId = "d4",
                nowEpochMs = 100L,
                contentType = "movie",
                videoId = "tt4",
                parentMetaId = "tt4",
                parentMetaType = "movie",
                title = "Empty",
                logo = null,
                poster = null,
                background = null,
                seasonNumber = null,
                episodeNumber = null,
                episodeTitle = null,
                episodeThumbnail = null,
                stream = emptyStream,
                p2pFeatureEnabled = true,
                platformSupportsTorrentDownloads = true,
            ),
        )
        assertEquals(DownloadEnqueueResult.MissingUrl, emptyResult.result)
    }

    @Test
    fun pauseReasonRoundTripsAndParsesStopDetails() {
        val encoded = DownloadPauseReasons.withDetail(
            DownloadPauseReasons.MISSING_JOB,
            DownloadPauseReasons.encodeStopDetail(
                DownloadPauseReasons.STOP_CATEGORY_TIMEOUT,
                "STOP_REASON_TIMEOUT",
            ),
        )
        assertEquals("missing_job|TIMEOUT:STOP_REASON_TIMEOUT", encoded)

        val parsed = DownloadPauseReasons.parse(encoded)
        assertEquals(DownloadPauseReasons.MISSING_JOB, parsed.code)
        assertEquals(
            DownloadPauseReasons.STOP_CATEGORY_TIMEOUT to "STOP_REASON_TIMEOUT",
            DownloadPauseReasons.parseStopDetail(parsed.detail!!),
        )

        val item = DownloadItem(
            id = "d-paused",
            contentType = "movie",
            videoId = "tt999",
            parentMetaId = "tt999",
            parentMetaType = "movie",
            title = "Paused Movie",
            sourceUrl = "https://example.com/video.mp4",
            fileName = "paused.mp4",
            status = DownloadStatus.Paused,
            pauseReason = encoded,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
        )
        val roundTripped = json.decodeFromString<DownloadItem>(json.encodeToString(item))
        assertEquals(encoded, roundTripped.pauseReason)

        val legacyWithoutPauseReason = json.encodeToString(item.copy(pauseReason = null))
            .replace(",\"pauseReason\":null", "")
            .replace("\"pauseReason\":null,", "")
        val decodedLegacy = json.decodeFromString<DownloadItem>(legacyWithoutPauseReason)
        assertNull(decodedLegacy.pauseReason)
    }
}
