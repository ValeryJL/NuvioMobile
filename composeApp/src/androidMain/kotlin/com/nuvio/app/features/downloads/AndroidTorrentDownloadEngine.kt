package com.nuvio.app.features.downloads

import android.content.Context
import android.util.Log
import com.nuvio.app.features.p2p.DEFAULT_P2P_TRACKERS
import com.nuvio.app.features.p2p.P2pSettingsRepository
import com.nuvio.app.features.p2p.P2pSettingsStorage
import com.nuvio.app.features.p2p.P2pTorrentProfile
import com.nuvio.app.features.p2p.buildNuvioEngineConfig
import com.nuvio.app.features.p2p.buildP2pMagnetUri
import com.nuvio.app.features.p2p.migrateNestedPayloadDirectory
import com.nuvio.app.features.p2p.normalizeP2pInfoHashOrNull
import com.nuvio.app.features.profiles.ProfileStorage
import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.NuvioEventType
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

private const val TAG = "AndroidTorrentDownload"
private const val DOWNLOAD_ENGINE_DIR = "nuvio-downloads-engine"

internal val torrentLoopbackHttpClient: OkHttpClient = downloadHttpClient.newBuilder()
    .readTimeout(90, TimeUnit.SECONDS)
    .build()

internal interface TorrentStreamSession {
    val streamUrl: String
    val fileSize: Long?
    val fileIndex: Int?
    fun consumeError(): String?
    suspend fun close()
}

internal interface TorrentSessionProvider {
    suspend fun openStream(item: DownloadItem): TorrentStreamSession
    suspend fun cleanupTorrentArtifacts(infoHash: String)
}

internal class NuvioEngineTorrentSessionProvider(
    context: Context,
) : TorrentSessionProvider {
    private data class EngineConfigKey(
        val uploadEnabled: Boolean,
        val torrentProfile: P2pTorrentProfile,
    )

    private val appContext = context.applicationContext
    private val stateDirectory = File(appContext.noBackupFilesDir, "$DOWNLOAD_ENGINE_DIR/state")
    private val cacheDirectory = File(appContext.cacheDir, DOWNLOAD_ENGINE_DIR)
    private val payloadDirectory = File(cacheDirectory, "payload")

    private val lifecycleMutex = Mutex()
    private val hashMutexes = ConcurrentHashMap<String, Mutex>()
    private val activeTorrentCounts = ConcurrentHashMap<String, Int>()
    private val torrentErrors = ConcurrentHashMap<String, String>()
    private val streamErrors = ConcurrentHashMap<String, String>()
    private val eventsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var engine: NuvioEngine? = null
    private var engineConfigKey: EngineConfigKey? = null
    private var eventsJob: Job? = null
    private var activeLeases = 0

    override suspend fun openStream(item: DownloadItem): TorrentStreamSession = withContext(Dispatchers.IO) {
        val canonicalHash = normalizeP2pInfoHashOrNull(item.torrentInfoHash)
            ?: throw IllegalStateException("Torrent download is missing a valid info hash")

        ProfileStorage.initialize(appContext)
        P2pSettingsStorage.initialize(appContext)
        P2pSettingsRepository.ensureLoaded()
        val settings = P2pSettingsRepository.uiState.value
        val desiredConfig = EngineConfigKey(
            uploadEnabled = settings.enableUpload,
            torrentProfile = settings.torrentProfile,
        )

        val hashMutex = hashMutexes.getOrPut(canonicalHash) { Mutex() }
        hashMutex.lock()

        var leasedEngine: NuvioEngine? = null
        var leaseRecorded = false
        var addedTorrentId: String? = null
        var preparedStreamId: String? = null

        try {
            val activeEngine = lifecycleMutex.withLock {
                val resolved = ensureEngineLocked(desiredConfig)
                activeLeases += 1
                activeTorrentCounts[canonicalHash] = (activeTorrentCounts[canonicalHash] ?: 0) + 1
                leaseRecorded = true
                leasedEngine = resolved
                resolved
            }

            ensureActive()
            val trackers = (item.torrentTrackers + DEFAULT_P2P_TRACKERS)
                .filter(String::isNotBlank)
                .distinct()
            val magnetUri = buildP2pMagnetUri(canonicalHash, trackers)
            torrentErrors.remove(canonicalHash)

            val torrentId = activeEngine.addMagnet(magnetUri)
            addedTorrentId = torrentId
            ensureActive()

            val stream = activeEngine.prepareStream(
                torrentId = torrentId,
                fileIndex = item.torrentFileIdx,
                filenameHint = item.torrentFilename,
            )
            preparedStreamId = stream.id
            streamErrors.remove(stream.id)
            ensureActive()

            val closed = AtomicBoolean(false)
            object : TorrentStreamSession {
                override val streamUrl: String = stream.url
                override val fileSize: Long? = stream.fileSize.takeIf { it > 0L }
                override val fileIndex: Int = stream.fileIndex

                override fun consumeError(): String? =
                    streamErrors.remove(stream.id) ?: torrentErrors.remove(torrentId)

                override suspend fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            releaseSession(
                                canonicalHash = canonicalHash,
                                activeEngine = activeEngine,
                                torrentId = torrentId,
                                streamId = stream.id,
                            )
                        } finally {
                            hashMutex.unlock()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (leaseRecorded && leasedEngine != null) {
                        releaseSession(
                            canonicalHash = canonicalHash,
                            activeEngine = leasedEngine!!,
                            torrentId = addedTorrentId,
                            streamId = preparedStreamId,
                        )
                    }
                } finally {
                    hashMutex.unlock()
                }
            }
            throw error
        }
    }

    override suspend fun cleanupTorrentArtifacts(infoHash: String): Unit = withContext(Dispatchers.IO) {
        val canonicalHash = normalizeP2pInfoHashOrNull(infoHash) ?: return@withContext
        lifecycleMutex.withLock {
            if (activeTorrentCounts.containsKey(canonicalHash)) return@withLock
            deleteTorrentCacheAndResume(canonicalHash)
            if (activeLeases == 0 && engine == null && activeTorrentCounts.isEmpty()) {
                payloadDirectory.deleteRecursively()
            }
        }
    }

    private suspend fun ensureEngineLocked(desiredConfig: EngineConfigKey): NuvioEngine {
        val current = engine
        if (current != null && (activeLeases > 0 || engineConfigKey == desiredConfig)) {
            return current
        }
        if (current != null) {
            shutdownEngineLocked()
        }

        check(stateDirectory.mkdirs() || stateDirectory.isDirectory) {
            "Could not create Nuvio downloads torrent state directory"
        }
        check(cacheDirectory.mkdirs() || cacheDirectory.isDirectory) {
            "Could not create Nuvio downloads torrent cache directory"
        }
        migrateNestedPayloadDirectory(cacheDirectory)
        if (activeTorrentCounts.isEmpty()) {
            payloadDirectory.deleteRecursively()
        }

        val created = NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = stateDirectory,
                cacheDirectory = cacheDirectory,
                uploadEnabled = desiredConfig.uploadEnabled,
                torrentProfile = desiredConfig.torrentProfile,
                diskCacheCapacityBytes = 0L,
            ),
        )
        engine = created
        engineConfigKey = desiredConfig
        eventsJob = eventsScope.launch {
            created.events.collect { event ->
                when (event.type) {
                    NuvioEventType.TorrentError -> {
                        val message = event.message?.trim()?.takeIf { it.isNotEmpty() } ?: return@collect
                        event.torrentId?.let { torrentErrors[it] = message }
                    }
                    NuvioEventType.StreamStopped -> {
                        if (event.requestId != 0L) return@collect
                        val message = event.message?.trim()?.takeIf { it.isNotEmpty() } ?: return@collect
                        event.streamId?.let { streamErrors[it] = message }
                        event.torrentId?.let { torrentErrors[it] = message }
                    }
                    else -> Unit
                }
            }
        }
        return created
    }

    private suspend fun releaseSession(
        canonicalHash: String,
        activeEngine: NuvioEngine,
        torrentId: String?,
        streamId: String?,
    ) {
        if (streamId != null) {
            runCatching { activeEngine.stopStream(streamId) }
                .onFailure { Log.w(TAG, "Error stopping download torrent stream", it) }
            streamErrors.remove(streamId)
        }
        if (torrentId != null) {
            runCatching { activeEngine.removeTorrent(torrentId) }
                .onFailure { Log.w(TAG, "Error removing download torrent", it) }
            torrentErrors.remove(torrentId)
        }
        runCatching { activeEngine.reclaimDiskCache(0L) }
            .onFailure { Log.w(TAG, "Error reclaiming download torrent cache", it) }
        deleteTorrentCacheAndResume(canonicalHash)

        lifecycleMutex.withLock {
            val remainingForHash = (activeTorrentCounts[canonicalHash] ?: 1) - 1
            if (remainingForHash <= 0) {
                activeTorrentCounts.remove(canonicalHash)
            } else {
                activeTorrentCounts[canonicalHash] = remainingForHash
            }
            activeLeases = (activeLeases - 1).coerceAtLeast(0)
            if (activeLeases == 0) {
                shutdownEngineLocked()
                payloadDirectory.deleteRecursively()
            }
        }
    }

    private suspend fun shutdownEngineLocked() {
        val current = engine ?: return
        eventsJob?.cancel()
        eventsJob = null
        engine = null
        engineConfigKey = null
        torrentErrors.clear()
        streamErrors.clear()
        runCatching { current.shutdown() }
            .onFailure { Log.w(TAG, "Error shutting down Nuvio downloads torrent engine", it) }
    }

    private fun deleteTorrentCacheAndResume(canonicalHash: String) {
        runCatching { File(payloadDirectory, canonicalHash).deleteRecursively() }
        runCatching { File(stateDirectory, "$canonicalHash.resume").delete() }
        runCatching { File(stateDirectory, "resume/$canonicalHash.resume").delete() }
    }
}

internal suspend fun transferTorrentDownload(
    item: DownloadItem,
    directory: File,
    sessionProvider: TorrentSessionProvider,
    client: OkHttpClient = torrentLoopbackHttpClient,
    onHeaders: (totalBytes: Long?, validator: String?) -> Unit,
    onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
): File {
    val partial = File(directory, "${item.fileName}.part")
    val session = sessionProvider.openStream(item)
    return try {
        val knownSize = session.fileSize?.takeIf { it > 0L }
        if (knownSize != null) {
            if (partial.isFile && partial.length() > knownSize) {
                partial.delete()
            } else if (partial.isFile && partial.length() == knownSize) {
                onHeaders(knownSize, null)
                onProgress(knownSize, knownSize)
                return partial
            }
            onHeaders(knownSize, null)
        }
        val loopbackItem = item.copy(
            sourceUrl = session.streamUrl,
            sourceHeaders = emptyMap(),
        )
        transferAndroidDownload(
            item = loopbackItem,
            directory = directory,
            validator = null,
            client = client,
            onHeaders = { totalBytes, _ ->
                onHeaders(totalBytes ?: knownSize, null)
            },
            onProgress = { downloaded, totalBytes ->
                onProgress(downloaded, totalBytes ?: knownSize)
            },
        )
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        val engineError = session.consumeError()?.takeIf { it.isNotBlank() }
        if (engineError != null && error is IOException && error !is DownloadHttpException) {
            throw IOException(engineError, error)
        }
        throw error
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            session.close()
        }
    }
}
