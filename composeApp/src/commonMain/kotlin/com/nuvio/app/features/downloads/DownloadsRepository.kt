package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.p2p.normalizeP2pInfoHashOrNull
import com.nuvio.app.features.player.SubtitleRequestExtras
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.streams.StreamItem
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val ACTIVE_HEALTH_CHECK_INTERVAL_MS = 15_000L

object DownloadsRepository {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val _hasUnseenCompleted = MutableStateFlow(false)
    val hasUnseenCompleted: StateFlow<Boolean> = _hasUnseenCompleted.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()
    private val watchdogLock = SynchronizedObject()
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null
    private var reconcileJob: Job? = null
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun markCompletedSeen() {
        _hasUnseenCompleted.value = false
    }

    fun clearLocalState() {
        cancelWatchdog()
        activeHandles.values.forEach { it.cancel(DownloadPauseReasons.ACCOUNT_CLEARED) }
        activeHandles.clear()
        hasLoaded = false
        _hasUnseenCompleted.value = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val now = DownloadsClock.nowEpochMs()
        val request = StreamBatchDownloadRequest(
            contentType = contentType,
            videoId = videoId,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            stream = stream,
        )
        val currentItems = _uiState.value.items.toMutableList()
        val prepared = when (val result = prepareSingleUpsert(request, now, currentItems)) {
            is PreparedUpsert.Failure -> return result.failure
            is PreparedUpsert.Ready -> result
        }

        currentItems.add(0, prepared.item)
        publish(currentItems)
        persist()
        if (prepared.item.status == DownloadStatus.Downloading) {
            startDownload(prepared.item)
        }

        return when {
            prepared.replacedExisting -> DownloadEnqueueResult.Replaced
            prepared.item.status == DownloadStatus.Queued -> DownloadEnqueueResult.Queued
            else -> DownloadEnqueueResult.Started
        }
    }

    internal fun enqueueBatchFromStreams(
        requests: List<StreamBatchDownloadRequest>,
    ): BatchDownloadEnqueueSummary {
        ensureLoaded()
        if (requests.isEmpty()) return BatchDownloadEnqueueSummary(0, 0, 0)

        val baseNow = DownloadsClock.nowEpochMs()
        val currentItems = _uiState.value.items.toMutableList()
        val batchItems = mutableListOf<DownloadItem>()
        val toStart = mutableListOf<DownloadItem>()
        var failedCount = 0

        requests.forEachIndexed { index, request ->
            val createdAt = baseNow + index
            when (val result = prepareSingleUpsert(request, createdAt, currentItems, pendingBatch = batchItems)) {
                is PreparedUpsert.Failure -> failedCount += 1
                is PreparedUpsert.Ready -> {
                    batchItems += result.item
                    if (result.item.status == DownloadStatus.Downloading) {
                        toStart += result.item
                    }
                }
            }
        }

        if (batchItems.isNotEmpty()) {
            currentItems.addAll(0, batchItems)
            publish(currentItems)
            persist()
            toStart.forEach(::startDownload)
        }

        return BatchDownloadEnqueueSummary(
            startedCount = toStart.size,
            queuedCount = (batchItems.size - toStart.size).coerceAtLeast(0),
            failedCount = failedCount,
        )
    }

    fun pauseDownload(downloadId: String, reason: String = DownloadPauseReasons.USER_APP) {
        ensureLoaded()
        if (pauseSingleItem(downloadId, reason)) {
            promoteQueuedDownloads()
        }
    }

    fun pauseActiveDownloads(reason: String = DownloadPauseReasons.USER_APP) {
        ensureLoaded()
        // Pause queued items first so canceling active handles never triggers promotion mid-loop.
        _uiState.value.items
            .filter { it.status == DownloadStatus.Queued }
            .map { it.id }
            .forEach { pauseSingleItem(it, reason) }
        _uiState.value.items
            .filter { it.status == DownloadStatus.Downloading }
            .map { it.id }
            .forEach { pauseSingleItem(it, reason) }
    }

    fun resumePausedDownloads() {
        ensureLoaded()
        val paused = _uiState.value.items
            .filter { it.status == DownloadStatus.Paused }
            .sortedWith(queueOrderComparator)
        if (paused.isEmpty()) return

        val now = DownloadsClock.nowEpochMs()
        val working = _uiState.value.items.toMutableList()
        val toStart = mutableListOf<DownloadItem>()

        paused.forEach { item ->
            val index = working.indexOfFirst { it.id == item.id }
            if (index < 0) return@forEach
            val canStart = canStartImmediately(item, working)
            val updated = item.copy(
                status = if (canStart) DownloadStatus.Downloading else DownloadStatus.Queued,
                errorMessage = null,
                pauseReason = null,
                localFileUri = null,
                updatedAtEpochMs = now,
            )
            working[index] = updated
            if (canStart) toStart += updated
        }

        publish(working)
        persist()
        toStart.forEach { startDownload(it, forceRecreate = true) }
    }

    fun resumeDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Paused &&
            item.status != DownloadStatus.Failed &&
            item.status != DownloadStatus.Queued
        ) return

        val canStart = canStartImmediately(item, _uiState.value.items)
        val nextStatus = if (canStart) DownloadStatus.Downloading else DownloadStatus.Queued
        val reset = item.copy(
            status = nextStatus,
            errorMessage = null,
            pauseReason = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )

        replaceItem(reset)
        persist()
        if (nextStatus == DownloadStatus.Downloading) {
            startDownload(reset, forceRecreate = true)
        }
    }

    fun restartDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status == DownloadStatus.Completed) return

        if (item.status == DownloadStatus.Downloading) {
            activeHandles.remove(item.id)?.cancel(DownloadPauseReasons.TRANSFER_CANCELLED)
            val restarted = item.copy(
                status = DownloadStatus.Downloading,
                errorMessage = null,
                pauseReason = null,
                localFileUri = null,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
            replaceItem(restarted)
            persist()
            startDownload(restarted, forceRecreate = true)
            return
        }

        resumeDownload(downloadId)
    }

    fun startQueuedDownloadNow(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        if (item.status != DownloadStatus.Queued) return

        val sameHashActive = !item.torrentInfoHash.isNullOrBlank() &&
            _uiState.value.items.any {
                it.status == DownloadStatus.Downloading &&
                    it.id != item.id &&
                    it.torrentInfoHash == item.torrentInfoHash
            }
        if (sameHashActive) {
            // Shared-hash torrent transfers are serialized; move this episode to the head of the queue.
            val earliestTimestamp = _uiState.value.items
                .filter { it.status == DownloadStatus.Queued }
                .minOfOrNull { it.createdAtEpochMs } ?: DownloadsClock.nowEpochMs()
            mutateItem(item.id) { current ->
                current.copy(
                    createdAtEpochMs = (earliestTimestamp - 1L).coerceAtLeast(0L),
                    updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                )
            }
            return
        }

        val started = item.copy(
            status = DownloadStatus.Downloading,
            errorMessage = null,
            pauseReason = null,
            localFileUri = null,
            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
        )
        replaceItem(started)
        persist()
        startDownload(started, forceRecreate = true)
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun requestActiveReconciliation() {
        if (!hasLoaded) return
        if (_uiState.value.items.none { it.status == DownloadStatus.Downloading }) return
        synchronized(watchdogLock) {
            if (reconcileJob?.isActive == true) return
            reconcileJob = watchdogScope.launch {
                reconcileActiveDownloads()
            }
        }
    }

    internal fun reconcileActiveDownloads() {
        if (!hasLoaded) return
        val currentItems = _uiState.value.items
        if (currentItems.none { it.status == DownloadStatus.Downloading }) return

        val reconciledUpdates = linkedMapOf<String, DownloadItem>()
        currentItems.forEach { item ->
            if (item.status == DownloadStatus.Downloading) {
                val reconciled = DownloadsPlatformDownloader.reconcileActiveItem(item)
                if (reconciled != item) {
                    reconciledUpdates[item.id] = reconciled
                    if (reconciled.status != DownloadStatus.Downloading) {
                        activeHandles.remove(item.id)?.detachObserver()
                    }
                }
            }
        }
        if (reconciledUpdates.isEmpty()) return

        var anyApplied = false
        val mergedItems = _uiState.value.items.map { latest ->
            val reconciled = reconciledUpdates[latest.id]
            if (reconciled != null && latest.status == DownloadStatus.Downloading) {
                anyApplied = true
                reconciled
            } else {
                latest
            }
        }
        if (anyApplied) {
            publish(mergedItems)
            persist()
            promoteQueuedDownloads()
        }
    }

    internal fun reattachBackgroundDownload(downloadId: String) {
        if (!hasLoaded) return
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return
        activeHandles.remove(downloadId)?.detachObserver()
        val restored = DownloadsPlatformDownloader.restoreItem(item)
        replaceItem(restored)
        persist()
        if (restored.status == DownloadStatus.Downloading) startDownload(restored)
    }

    fun cancelDownload(downloadId: String) {
        ensureLoaded()
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return

        activeHandles.remove(downloadId)?.cancel()
        DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
        DownloadsPlatformDownloader.removePartialFile(item.fileName)

        publish(_uiState.value.items.filterNot { it.id == downloadId })
        persist()
        if (item.status == DownloadStatus.Downloading) {
            promoteQueuedDownloads()
        }
    }

    private fun pauseSingleItem(downloadId: String, reason: String): Boolean {
        val item = _uiState.value.items.firstOrNull { it.id == downloadId } ?: return false
        if (item.status != DownloadStatus.Downloading && item.status != DownloadStatus.Queued) return false

        val wasDownloading = item.status == DownloadStatus.Downloading
        activeHandles.remove(downloadId)?.cancel(reason)
        mutateItem(downloadId) { current ->
            current.copy(
                status = DownloadStatus.Paused,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                errorMessage = null,
                pauseReason = reason,
            )
        }
        return wasDownloading
    }

    private fun prepareSingleUpsert(
        request: StreamBatchDownloadRequest,
        now: Long,
        currentItems: MutableList<DownloadItem>,
        pendingBatch: MutableList<DownloadItem> = mutableListOf(),
    ): PreparedUpsert {
        val downloadId = nextDownloadId(now)
        val built = buildDownloadItemFromStream(
            downloadId = downloadId,
            nowEpochMs = now,
            contentType = request.contentType,
            videoId = request.videoId,
            parentMetaId = request.parentMetaId,
            parentMetaType = request.parentMetaType,
            title = request.title,
            logo = request.logo,
            poster = request.poster,
            background = request.background,
            seasonNumber = request.seasonNumber,
            episodeNumber = request.episodeNumber,
            episodeTitle = request.episodeTitle,
            episodeThumbnail = request.episodeThumbnail,
            stream = request.stream,
        )
        val rawItem = when (built) {
            is StreamDownloadBuildResult.Failure -> return PreparedUpsert.Failure(built.result)
            is StreamDownloadBuildResult.Success -> built.item
        }

        val logicalKey = buildLogicalKey(
            parentMetaId = request.parentMetaId,
            seasonNumber = request.seasonNumber,
            episodeNumber = request.episodeNumber,
        )

        var replacedExisting = false
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            activeHandles.remove(existing.id)?.cancel()
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }
        pendingBatch.removeAll { it.logicalContentKey == logicalKey }

        val canStart = canStartImmediately(rawItem, currentItems + pendingBatch)
        val item = rawItem.copy(
            status = if (canStart) DownloadStatus.Downloading else DownloadStatus.Queued,
        )
        return PreparedUpsert.Ready(item = item, replacedExisting = replacedExisting)
    }

    private fun canStartImmediately(candidate: DownloadItem, items: List<DownloadItem>): Boolean {
        val active = items.filter { it.status == DownloadStatus.Downloading && it.id != candidate.id }
        if (active.size >= MAX_CONCURRENT_DOWNLOADS) return false
        val hash = candidate.torrentInfoHash
        if (!hash.isNullOrBlank() && active.any { it.torrentInfoHash == hash }) return false
        return true
    }

    private fun promoteQueuedDownloads() {
        while (true) {
            val currentItems = _uiState.value.items
            val next = currentItems
                .filter { it.status == DownloadStatus.Queued }
                .sortedWith(queueOrderComparator)
                .firstOrNull { canStartImmediately(it, currentItems) }
                ?: break

            val promoted = next.copy(
                status = DownloadStatus.Downloading,
                errorMessage = null,
                pauseReason = null,
                localFileUri = null,
                updatedAtEpochMs = DownloadsClock.nowEpochMs(),
            )
            replaceItem(promoted)
            persist()
            startDownload(promoted)
        }
    }

    private fun loadFromDisk() {
        _hasUnseenCompleted.value = false
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                val statusNormalized = DownloadsPlatformDownloader.restoreItem(item)

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
        normalized.filter { it.status == DownloadStatus.Downloading && it.id !in activeHandles }
            .forEach { startDownload(it) }
        promoteQueuedDownloads()
        syncWatchdogState(_uiState.value.items)
    }

    private fun startDownload(item: DownloadItem, forceRecreate: Boolean = false) {
        val request = DownloadPlatformRequest(item)

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            forceRecreate = forceRecreate,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                            pauseReason = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        pauseReason = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                promoteQueuedDownloads()
            },
            onFailure = { message ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            pauseReason = null,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
                promoteQueuedDownloads()
            },
            onPaused = { reason ->
                activeHandles.remove(item.id)
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) return@mutateItem current
                    current.copy(
                        status = DownloadStatus.Paused,
                        errorMessage = null,
                        pauseReason = reason ?: current.pauseReason,
                    )
                }
                promoteQueuedDownloads()
            },
        )

        activeHandles[item.id] = handle
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        val previousStatuses = _uiState.value.items.associate { it.id to it.status }
        if (items.any { it.status == DownloadStatus.Completed && previousStatuses[it.id].let { status -> status != null && status != DownloadStatus.Completed } }) {
            _hasUnseenCompleted.value = true
        }
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
        syncWatchdogState(items)
    }

    private fun syncWatchdogState(items: List<DownloadItem>) {
        if (items.any { it.status == DownloadStatus.Downloading }) {
            ensureWatchdogRunning()
        } else {
            cancelWatchdog()
        }
    }

    private fun ensureWatchdogRunning() {
        synchronized(watchdogLock) {
            if (watchdogJob?.isActive == true) return
            watchdogJob = watchdogScope.launch {
                while (isActive) {
                    delay(ACTIVE_HEALTH_CHECK_INTERVAL_MS)
                    if (!hasLoaded || _uiState.value.items.none { it.status == DownloadStatus.Downloading }) {
                        break
                    }
                    reconcileActiveDownloads()
                }
            }
        }
    }

    private fun cancelWatchdog() {
        synchronized(watchdogLock) {
            watchdogJob?.cancel()
            watchdogJob = null
            reconcileJob?.cancel()
            reconcileJob = null
        }
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

internal data class StreamBatchDownloadRequest(
    val contentType: String,
    val videoId: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val logo: String?,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val stream: StreamItem,
)

internal data class BatchDownloadEnqueueSummary(
    val startedCount: Int,
    val queuedCount: Int,
    val failedCount: Int,
)

private sealed interface PreparedUpsert {
    data class Ready(val item: DownloadItem, val replacedExisting: Boolean) : PreparedUpsert
    data class Failure(val failure: DownloadEnqueueResult) : PreparedUpsert
}

private const val MAX_CONCURRENT_DOWNLOADS = 2

private val queueOrderComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem>(
        { it.createdAtEpochMs },
        { it.seasonNumber ?: Int.MAX_VALUE },
        { it.episodeNumber ?: Int.MAX_VALUE },
        { it.id },
    )

internal sealed interface StreamDownloadBuildResult {
    data class Success(val item: DownloadItem) : StreamDownloadBuildResult
    data class Failure(val result: DownloadEnqueueResult) : StreamDownloadBuildResult
}

internal fun buildDownloadItemFromStream(
    downloadId: String,
    nowEpochMs: Long,
    contentType: String,
    videoId: String,
    parentMetaId: String,
    parentMetaType: String,
    title: String,
    logo: String?,
    poster: String?,
    background: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    episodeThumbnail: String?,
    stream: StreamItem,
    p2pFeatureEnabled: Boolean = AppFeaturePolicy.p2pEnabled,
    platformSupportsTorrentDownloads: Boolean = DownloadsPlatformDownloader.supportsTorrentDownloads,
): StreamDownloadBuildResult {
    val directUrl = stream.playableDirectUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val torrentInfoHash: String?
    val torrentFileIdx: Int?
    val torrentFilename: String?
    val torrentTrackers: List<String>
    val sourceUrl: String
    val initialTotalBytes: Long?

    if (directUrl != null) {
        if (!directUrl.isSupportedDownloadUrl()) {
            return StreamDownloadBuildResult.Failure(DownloadEnqueueResult.UnsupportedFormat)
        }
        torrentInfoHash = null
        torrentFileIdx = null
        torrentFilename = null
        torrentTrackers = emptyList()
        sourceUrl = directUrl
        initialTotalBytes = null
    } else if (stream.needsLocalDebridResolve && stream.p2pInfoHash != null) {
        if (!p2pFeatureEnabled || !platformSupportsTorrentDownloads) {
            return StreamDownloadBuildResult.Failure(DownloadEnqueueResult.UnsupportedFormat)
        }
        val canonicalHash = normalizeP2pInfoHashOrNull(stream.p2pInfoHash)
            ?: return StreamDownloadBuildResult.Failure(DownloadEnqueueResult.UnsupportedFormat)
        torrentInfoHash = canonicalHash
        torrentFileIdx = stream.p2pFileIdx
        torrentFilename = stream.p2pPreferredFilename
        torrentTrackers = stream.p2pTrackers
        sourceUrl = buildTorrentSentinelUrl(canonicalHash, torrentFileIdx)
        initialTotalBytes = stream.behaviorHints.videoSize?.takeIf { it > 0L }
    } else {
        return StreamDownloadBuildResult.Failure(
            if (stream.directPlaybackUrl != null || stream.externalUrl != null || stream.isTorrentStream) {
                DownloadEnqueueResult.UnsupportedFormat
            } else {
                DownloadEnqueueResult.MissingUrl
            },
        )
    }

    val fileName = buildFileName(
        title = title,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        fallbackTitle = stream.streamLabel,
        preferredFilename = torrentFilename,
        sourceUrl = directUrl ?: sourceUrl,
        downloadId = downloadId,
    )
    val subtitleHints = if (torrentFilename != null && stream.behaviorHints.filename.isNullOrBlank()) {
        stream.behaviorHints.copy(filename = torrentFilename)
    } else {
        stream.behaviorHints
    }

    return StreamDownloadBuildResult.Success(
        DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            subtitleRequests = addonSubtitleRequests(
                type = contentType,
                videoId = videoId,
                extras = SubtitleRequestExtras.from(subtitleHints, directUrl),
            ),
            sourceSubtitles = stream.externalSubtitles,
            torrentInfoHash = torrentInfoHash,
            torrentFileIdx = torrentFileIdx,
            torrentFilename = torrentFilename,
            torrentTrackers = torrentTrackers,
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Downloading,
            downloadedBytes = 0L,
            totalBytes = initialTotalBytes,
            errorMessage = null,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        ),
    )
}

private fun buildTorrentSentinelUrl(infoHash: String, fileIdx: Int?): String =
    "torrent://$infoHash${fileIdx?.let { "?index=$it" }.orEmpty()}"

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    preferredFilename: String?,
    sourceUrl: String,
    downloadId: String,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = preferredFilename?.knownVideoExtensionOrNull()
        ?: sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(downloadId)
        append('.')
        append(extension)
    }
}

private val knownVideoExtensions = setOf(
    "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "flv", "wmv", "mpg", "mpeg",
)

private fun String.knownVideoExtensionOrNull(): String? {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()
    return suffix.takeIf { it in knownVideoExtensions }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

internal fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
