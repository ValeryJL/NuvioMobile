package com.nuvio.app.features.downloads

import android.net.ConnectivityManager
import com.nuvio.app.features.streams.StreamSubtitle
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidTorrentDownloadTest {
    @get:Rule val temporary = TemporaryFolder()

    @After
    fun tearDown() {
        DownloadsRepository.clearLocalState()
    }

    @Test
    fun completesMultiFileTorrentDownloadWithSubtitlesAndClosesSession(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MockWebServer().use { server ->
            val episodePayload = "episode-02-binary-payload-content".encodeToByteArray()
            server.dispatcher = torrentRangeDispatcher(
                server = server,
                filesByIndex = mapOf(2 to episodePayload),
                subtitlePath = "/subs/en.srt",
                subtitleBody = validSrt,
            )
            val provider = FakeTorrentSessionProvider(server, filesByIndex = mapOf(2 to episodePayload))
            val scheduler = AndroidDownloadScheduler(context, provider).apply {
                unmeteredNetworkAvailable = { true }
            }
            val item = torrentDownloadItem(
                fileName = "show-ep2-${UUID.randomUUID()}.mkv",
                fileIdx = 2,
                subtitles = listOf(StreamSubtitle(url = server.url("/subs/en.srt").toString(), language = "eng", name = "English")),
            )
            val transfer = scheduler.store.begin(item)

            try {
                assertFalse(scheduler.execute(transfer) { })

                val completed = assertNotNull(scheduler.store.get(item.fileName)).item
                assertEquals(DownloadStatus.Completed, completed.status)
                assertEquals(episodePayload.size.toLong(), completed.downloadedBytes)
                assertEquals(episodePayload.size.toLong(), completed.totalBytes)
                assertNotNull(completed.localFileUri)
                assertEquals("episode-02-binary-payload-content", File(scheduler.directory, item.fileName).readText())
                assertFalse(File(scheduler.directory, "${item.fileName}.part").exists())
                assertEquals(1, provider.openCount.get())
                assertEquals(1, provider.closeCount.get())
                assertEquals(listOf<Int?>(2), provider.openedFileIndices)

                val savedSubtitles = DownloadSubtitles.localSubtitles(completed.localFileUri!!)
                assertEquals(1, savedSubtitles.size)
                assertEquals("eng", savedSubtitles.single().language)
            } finally {
                scheduler.remove(item.fileName)
                File(scheduler.directory, item.fileName).delete()
            }
        }
    }

    @Test
    fun supportsPauseCancellationAndByteRangeResumeWithoutIfRange(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MockWebServer().use { server ->
            val fullPayload = "0123456789abcdefghijklmnopqrstuvwxyz".encodeToByteArray()
            val recordedRequests = mutableListOf<RecordedRequest>()
            server.dispatcher = torrentRangeDispatcher(
                server = server,
                filesByIndex = mapOf(0 to fullPayload),
                throttleFirstResponseBytesPerPeriod = 1L,
                throttlePeriodMs = 5_000L,
                onRequest = { synchronized(recordedRequests) { recordedRequests += it } },
            )
            val provider = FakeTorrentSessionProvider(server, filesByIndex = mapOf(0 to fullPayload))
            val scheduler = AndroidDownloadScheduler(context, provider).apply {
                unmeteredNetworkAvailable = { true }
            }
            val item = torrentDownloadItem(
                fileName = "pause-resume-${UUID.randomUUID()}.mp4",
                fileIdx = 0,
            )
            val firstTransfer = scheduler.store.begin(item)
            val partFile = File(scheduler.directory, "${item.fileName}.part")

            try {
                val running = async(Dispatchers.Default) { scheduler.execute(firstTransfer) { } }
                withTimeout(5_000) {
                    while (!partFile.isFile || partFile.length() == 0L) delay(20)
                }

                scheduler.pause(item.fileName)
                running.cancel()
                runCatching { running.await() }

                val partialSizeAfterPause = partFile.length()
                assertTrue(partialSizeAfterPause in 1L until fullPayload.size.toLong())
                assertEquals(DownloadStatus.Paused, scheduler.restore(item).status)
                assertEquals(1, provider.closeCount.get())

                // Resume with normal speed on a new session (new ETag); must send Range and no If-Range.
                server.dispatcher = torrentRangeDispatcher(
                    server = server,
                    filesByIndex = mapOf(0 to fullPayload),
                    etag = "\"session-resumed-2\"",
                    onRequest = { synchronized(recordedRequests) { recordedRequests += it } },
                )
                val resumedTransfer = scheduler.store.begin(assertNotNull(scheduler.store.get(item.fileName)).item)
                assertFalse(scheduler.execute(resumedTransfer) { })

                val completed = assertNotNull(scheduler.store.get(item.fileName)).item
                assertEquals(DownloadStatus.Completed, completed.status)
                assertEquals("0123456789abcdefghijklmnopqrstuvwxyz", File(scheduler.directory, item.fileName).readText())
                assertFalse(partFile.exists())

                val resumedRequest = synchronized(recordedRequests) { recordedRequests.last() }
                assertEquals("bytes=$partialSizeAfterPause-", resumedRequest.getHeader("Range"))
                assertNull(resumedRequest.getHeader("If-Range"))
                assertEquals(2, provider.openCount.get())
                assertEquals(2, provider.closeCount.get())
            } finally {
                scheduler.remove(item.fileName)
                File(scheduler.directory, item.fileName).delete()
            }
        }
    }

    @Test
    fun removeCleansPartialSubtitlesAndDefersTorrentArtifactsUntilLastSiblingRemoved(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val sharedHash = "fedcba9876543210fedcba9876543210fedcba98"
        val provider = FakeTorrentSessionProvider(MockWebServer(), filesByIndex = emptyMap())
        val scheduler = AndroidDownloadScheduler(context, provider)
        scheduler.directory.mkdirs()

        val ep1 = torrentDownloadItem(
            id = "ep1-${UUID.randomUUID()}",
            infoHash = sharedHash,
            fileIdx = 1,
            fileName = "ep1-${UUID.randomUUID()}.mkv",
        )
        val ep2 = torrentDownloadItem(
            id = "ep2-${UUID.randomUUID()}",
            infoHash = sharedHash,
            fileIdx = 2,
            fileName = "ep2-${UUID.randomUUID()}.mkv",
        )
        scheduler.store.begin(ep1)
        scheduler.store.begin(ep2)
        val part1 = File(scheduler.directory, "${ep1.fileName}.part").apply { writeText("p1") }
        val part2 = File(scheduler.directory, "${ep2.fileName}.part").apply { writeText("p2") }

        scheduler.remove(ep1.fileName)
        withTimeout(5_000) {
            while (part1.exists()) delay(20)
        }
        assertTrue(provider.cleanedUpHashes.isEmpty())

        scheduler.remove(ep2.fileName)
        withTimeout(5_000) {
            while (part2.exists() || provider.cleanedUpHashes.isEmpty()) delay(20)
        }
        assertEquals(listOf(sharedHash), provider.cleanedUpHashes)
    }

    @Test
    fun bypassesBoundNetworkSocketFactoryForLoopbackTorrentTransfer(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity?.activeNetwork
        MockWebServer().use { server ->
            val payload = "loopback-network-bypass-ok".encodeToByteArray()
            server.dispatcher = torrentRangeDispatcher(server, mapOf(0 to payload))
            val provider = FakeTorrentSessionProvider(server, mapOf(0 to payload))
            val scheduler = AndroidDownloadScheduler(context, provider).apply {
                unmeteredNetworkAvailable = { true }
            }
            val item = torrentDownloadItem(fileName = "loopback-${UUID.randomUUID()}.mp4")
            val transfer = scheduler.store.begin(item)

            try {
                assertFalse(scheduler.execute(transfer, network = activeNetwork) { })
                assertEquals(DownloadStatus.Completed, scheduler.store.get(item.fileName)?.item?.status)
                assertEquals("loopback-network-bypass-ok", File(scheduler.directory, item.fileName).readText())
            } finally {
                scheduler.remove(item.fileName)
                File(scheduler.directory, item.fileName).delete()
            }
        }
    }

    @Test
    fun serializesConcurrentMultiFileEpisodesAndHandlesPartBoundaries(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MockWebServer().use { server ->
            val payload1 = "episode-one-payload".encodeToByteArray()
            val payload2 = "episode-two-payload".encodeToByteArray()
            server.dispatcher = torrentRangeDispatcher(server, mapOf(1 to payload1, 2 to payload2))
            val sharedHash = "0123456789abcdef0123456789abcdef01234567"
            val provider = FakeTorrentSessionProvider(
                server = server,
                filesByIndex = mapOf(1 to payload1, 2 to payload2),
                serializePerHash = true,
            )
            val scheduler = AndroidDownloadScheduler(context, provider).apply {
                unmeteredNetworkAvailable = { true }
            }
            val item1 = torrentDownloadItem(id = "s1", infoHash = sharedHash, fileIdx = 1, fileName = "s1-${UUID.randomUUID()}.mkv")
            val item2 = torrentDownloadItem(id = "s2", infoHash = sharedHash, fileIdx = 2, fileName = "s2-${UUID.randomUUID()}.mkv")
            val t1 = scheduler.store.begin(item1)
            val t2 = scheduler.store.begin(item2)

            try {
                val results = awaitAll(
                    async(Dispatchers.Default) { scheduler.execute(t1) { } },
                    async(Dispatchers.Default) { scheduler.execute(t2) { } },
                )
                assertEquals(listOf(false, false), results)
                assertEquals(1, provider.maxConcurrentPerHash[sharedHash])
                assertEquals("episode-one-payload", File(scheduler.directory, item1.fileName).readText())
                assertEquals("episode-two-payload", File(scheduler.directory, item2.fileName).readText())
            } finally {
                scheduler.remove(item1.fileName)
                scheduler.remove(item2.fileName)
                File(scheduler.directory, item1.fileName).delete()
                File(scheduler.directory, item2.fileName).delete()
            }

            // Verify pre-existing complete .part finalizes cleanly without extra HTTP transfer, and oversized .part resets.
            val dir = temporary.newFolder()
            val exactBytes = "already-here".encodeToByteArray()
            val exactItem = torrentDownloadItem(fileName = "exact.mkv")
            File(dir, "exact.mkv.part").writeBytes(exactBytes)
            val exactProvider = FakeTorrentSessionProvider(MockWebServer(), mapOf(0 to exactBytes))
            val exactOutput = transferTorrentDownload(
                item = exactItem,
                directory = dir,
                sessionProvider = exactProvider,
                onHeaders = { _, _ -> },
                onProgress = { _, _ -> },
            )
            assertEquals("already-here", exactOutput.readText())
            assertEquals(1, exactProvider.closeCount.get())

            // Oversized .part gets deleted and redownloaded cleanly.
            MockWebServer().use { oversizedServer ->
                oversizedServer.dispatcher = torrentRangeDispatcher(oversizedServer, mapOf(0 to exactBytes))
                val oversizedProvider = FakeTorrentSessionProvider(oversizedServer, mapOf(0 to exactBytes))
                val oversizedItem = torrentDownloadItem(fileName = "oversized.mkv")
                File(dir, "oversized.mkv.part").writeText("much-longer-corrupted-partial-file")
                val oversizedOutput = transferTorrentDownload(
                    item = oversizedItem,
                    directory = dir,
                    sessionProvider = oversizedProvider,
                    onHeaders = { _, _ -> },
                    onProgress = { _, _ -> },
                )
                assertEquals("already-here", oversizedOutput.readText())
            }
        }
    }

    @Test
    fun resetsRetryCountWhenTransferMakesProgressBeforeTransientError(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MockWebServer().use { server ->
            val fullPayload = "0123456789".repeat(200).encodeToByteArray()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setResponseCode(200)
                    .setBody(Buffer().write(fullPayload))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
            val provider = FakeTorrentSessionProvider(server, mapOf(0 to fullPayload))
            val scheduler = AndroidDownloadScheduler(context, provider).apply {
                unmeteredNetworkAvailable = { true }
            }
            val item = torrentDownloadItem(fileName = "retry-reset-${UUID.randomUUID()}.mkv")
            val started = scheduler.store.begin(item)
            val atLimitTransfer = scheduler.store.update(item.fileName, started.generation) {
                it.copy(retryCount = 3)
            }!!

            try {
                // Made partial progress before disconnect -> retryCount resets from 3 to 0 before +1 -> returns true!
                assertTrue(scheduler.execute(atLimitTransfer) { })
                val updated = assertNotNull(scheduler.store.get(item.fileName))
                assertEquals(1, updated.retryCount)
                assertTrue(updated.item.downloadedBytes in 1L until fullPayload.size.toLong())
                assertEquals(DownloadStatus.Downloading, updated.item.status)
            } finally {
                scheduler.remove(item.fileName)
            }
        }
    }

    @Test
    fun cleanupTorrentArtifactsTriggersOnCompletionOnlyAfterPausedSiblingsFinish(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        MockWebServer().use { server ->
            val payload1 = "episode-one-shared-torrent".encodeToByteArray()
            val payload2 = "episode-two-shared-torrent".encodeToByteArray()
            val sharedHash = "0123456789abcdef0123456789abcdef01234567"
            server.dispatcher = torrentRangeDispatcher(server, mapOf(1 to payload1, 2 to payload2))
            val provider = FakeTorrentSessionProvider(
                server,
                filesByIndex = mapOf(1 to payload1, 2 to payload2),
            )
            val scheduler = AndroidDownloadScheduler(context, provider).apply {
                unmeteredNetworkAvailable = { true }
            }
            val ep1 = torrentDownloadItem(
                id = "cleanup-ep1-${UUID.randomUUID()}",
                infoHash = sharedHash,
                fileIdx = 1,
                fileName = "cleanup-ep1-${UUID.randomUUID()}.mkv",
            )
            val ep2 = torrentDownloadItem(
                id = "cleanup-ep2-${UUID.randomUUID()}",
                infoHash = sharedHash,
                fileIdx = 2,
                fileName = "cleanup-ep2-${UUID.randomUUID()}.mkv",
            )
            val transfer1 = scheduler.store.begin(ep1)
            scheduler.store.begin(ep2)
            scheduler.pause(ep2.fileName, DownloadPauseReasons.USER_APP)

            try {
                assertFalse(scheduler.execute(transfer1) { })
                assertEquals(DownloadStatus.Completed, scheduler.store.get(ep1.fileName)?.item?.status)
                assertTrue(provider.cleanedUpHashes.isEmpty())

                val resumedEp2 = scheduler.store.begin(ep2.copy(status = DownloadStatus.Downloading))
                assertFalse(scheduler.execute(resumedEp2) { })
                assertEquals(DownloadStatus.Completed, scheduler.store.get(ep2.fileName)?.item?.status)
                assertEquals(listOf(sharedHash), provider.cleanedUpHashes)
            } finally {
                scheduler.remove(ep1.fileName)
                scheduler.remove(ep2.fileName)
                File(scheduler.directory, ep1.fileName).delete()
                File(scheduler.directory, ep2.fileName).delete()
            }
        }
    }

    private class FakeTorrentSessionProvider(
        private val server: MockWebServer,
        private val filesByIndex: Map<Int, ByteArray>,
        private val serializePerHash: Boolean = false,
    ) : TorrentSessionProvider {
        val openCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val openedFileIndices = CopyOnWriteArrayList<Int?>()
        val cleanedUpHashes = CopyOnWriteArrayList<String>()
        val maxConcurrentPerHash = ConcurrentHashMap<String, Int>()
        private val activePerHash = ConcurrentHashMap<String, AtomicInteger>()
        private val perHashMutex = ConcurrentHashMap<String, Mutex>()

        override suspend fun openStream(item: DownloadItem): TorrentStreamSession {
            val hash = requireNotNull(item.torrentInfoHash)
            val mutex = if (serializePerHash) perHashMutex.getOrPut(hash) { Mutex() } else null
            mutex?.lock()
            openCount.incrementAndGet()
            openedFileIndices += item.torrentFileIdx
            val active = activePerHash.getOrPut(hash) { AtomicInteger(0) }.incrementAndGet()
            maxConcurrentPerHash.merge(hash, active, ::maxOf)
            val idx = item.torrentFileIdx ?: 0
            val size = filesByIndex[idx]?.size?.toLong()
            return object : TorrentStreamSession {
                override val streamUrl: String = server.url("/stream/$idx").toString()
                override val fileSize: Long? = size
                override val fileIndex: Int = idx
                override fun consumeError(): String? = null
                override suspend fun close() {
                    closeCount.incrementAndGet()
                    activePerHash[hash]?.decrementAndGet()
                    mutex?.unlock()
                }
            }
        }

        override suspend fun cleanupTorrentArtifacts(infoHash: String) {
            cleanedUpHashes += infoHash
        }
    }

    private fun torrentRangeDispatcher(
        server: MockWebServer,
        filesByIndex: Map<Int, ByteArray>,
        subtitlePath: String? = null,
        subtitleBody: String? = null,
        etag: String = "\"session-1\"",
        throttleFirstResponseBytesPerPeriod: Long? = null,
        throttlePeriodMs: Long = 0L,
        onRequest: (RecordedRequest) -> Unit = {},
    ): Dispatcher {
        var throttleApplied = false
        return object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                onRequest(request)
                val path = request.path.orEmpty()
                if (subtitlePath != null && subtitleBody != null && path == subtitlePath) {
                    return MockResponse().setResponseCode(200).setBody(subtitleBody)
                }
                val fileIdx = path.removePrefix("/stream/").toIntOrNull()
                    ?: return MockResponse().setResponseCode(404)
                val data = filesByIndex[fileIdx] ?: return MockResponse().setResponseCode(404)
                val rangeHeader = request.getHeader("Range")
                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val start = rangeHeader.removePrefix("bytes=").substringBefore('-').toIntOrNull()
                        ?: return MockResponse().setResponseCode(416)
                    if (start !in data.indices) return MockResponse().setResponseCode(416)
                    val slice = data.copyOfRange(start, data.size)
                    return MockResponse()
                        .setResponseCode(206)
                        .setHeader("ETag", etag)
                        .setHeader("Content-Range", "bytes $start-${data.lastIndex}/${data.size}")
                        .setBody(Buffer().write(slice))
                }
                val response = MockResponse()
                    .setResponseCode(200)
                    .setHeader("ETag", etag)
                    .setBody(Buffer().write(data))
                if (throttleFirstResponseBytesPerPeriod != null && !throttleApplied) {
                    throttleApplied = true
                    response.throttleBody(throttleFirstResponseBytesPerPeriod, throttlePeriodMs, TimeUnit.MILLISECONDS)
                }
                return response
            }
        }
    }

    private fun torrentDownloadItem(
        id: String = "torrent-${UUID.randomUUID()}",
        infoHash: String = "0123456789abcdef0123456789abcdef01234567",
        fileIdx: Int? = 0,
        fileName: String = "torrent-video.mkv",
        subtitles: List<StreamSubtitle> = emptyList(),
    ): DownloadItem = DownloadItem(
        id = id,
        contentType = if (fileIdx != null && fileIdx > 0) "series" else "movie",
        parentMetaId = "tt-torrent",
        parentMetaType = if (fileIdx != null && fileIdx > 0) "series" else "movie",
        videoId = "tt-torrent:${fileIdx ?: 0}",
        title = "Torrent Test",
        seasonNumber = if (fileIdx != null && fileIdx > 0) 1 else null,
        episodeNumber = if (fileIdx != null && fileIdx > 0) fileIdx else null,
        streamTitle = "1080p",
        providerName = "TorrentAddon",
        sourceUrl = "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}",
        sourceSubtitles = subtitles,
        torrentInfoHash = infoHash,
        torrentFileIdx = fileIdx,
        torrentFilename = fileName,
        torrentTrackers = listOf("udp://tracker.example:6969/announce"),
        fileName = fileName,
        status = DownloadStatus.Downloading,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private val validSrt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
}
