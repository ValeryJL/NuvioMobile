package com.nuvio.app.features.downloads

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.p2p.normalizeP2pInfoHashOrNull
import com.nuvio.app.features.streams.FilenameEpisodeMatcher
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.isSupportedDownload
import com.nuvio.app.features.streams.streamAddonInstanceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class SeasonPackEpisodeCandidate(
    val video: MetaVideo,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val matchedStream: StreamItem?,
    val existingDownload: DownloadItem?,
    val checked: Boolean,
) {
    val isAlreadyInDownloads: Boolean
        get() = existingDownload != null && existingDownload.status != DownloadStatus.Failed

    val isSelectable: Boolean
        get() = matchedStream != null && !isAlreadyInDownloads
}

internal data class SeasonPackLookupProgress(
    val seasonNumber: Int,
    val sourceTitle: String,
    val isLoading: Boolean,
    val checkedEpisodes: Int,
    val totalEpisodes: Int,
    val episodes: List<SeasonPackEpisodeCandidate>,
    val errorMessage: String? = null,
)

internal object SeasonPackDownloadResolver {
    private const val ADDON_FETCH_CONCURRENCY = 3
    private const val DEBRID_RESOLVE_CONCURRENCY = 2
    private const val ADDON_READY_TIMEOUT_MS = 10_000L

    fun supportsSeasonPackLookup(
        stream: StreamItem?,
        isEpisode: Boolean,
        debridEnabled: Boolean,
        p2pFeatureEnabled: Boolean = AppFeaturePolicy.p2pEnabled,
        platformSupportsTorrentDownloads: Boolean = DownloadsPlatformDownloader.supportsTorrentDownloads,
    ): Boolean {
        if (stream == null || !isEpisode) return false
        if (
            !stream.isSupportedDownload(
                debridEnabled = debridEnabled,
                p2pFeatureEnabled = p2pFeatureEnabled,
                platformSupportsTorrentDownloads = platformSupportsTorrentDownloads,
            )
        ) return false
        if (!stream.isInstalledAddonStream) return false
        return stream.p2pInfoHash != null || !stream.behaviorHints.bingeGroup.isNullOrBlank()
    }

    /**
     * Pure matcher that identifies which stream inside [candidates] belongs to the same torrent or
     * pack source as [anchor].
     *
     * When [anchor] has a `p2pInfoHash`, candidates MUST match that exact normalized infoHash so two
     * unrelated single-episode torrents with the same generic `bingeGroup` are never conflated.
     */
    fun matchSamePackStream(
        anchor: StreamItem,
        candidates: List<StreamItem>,
        season: Int?,
        episode: Int,
        debridEnabled: Boolean,
        p2pFeatureEnabled: Boolean = AppFeaturePolicy.p2pEnabled,
        platformSupportsTorrentDownloads: Boolean = DownloadsPlatformDownloader.supportsTorrentDownloads,
    ): StreamItem? {
        val anchorHash = normalizeP2pInfoHashOrNull(anchor.p2pInfoHash)
        val anchorBingeGroup = anchor.behaviorHints.bingeGroup?.trim()?.takeIf { it.isNotEmpty() }
        val pool = candidates.filter {
            it.isSupportedDownload(
                debridEnabled = debridEnabled,
                p2pFeatureEnabled = p2pFeatureEnabled,
                platformSupportsTorrentDownloads = platformSupportsTorrentDownloads,
            )
        }
        if (pool.isEmpty()) return null

        val matchingPool = when {
            anchorHash != null -> pool.filter { normalizeP2pInfoHashOrNull(it.p2pInfoHash) == anchorHash }
            anchorBingeGroup != null -> pool.filter {
                it.behaviorHints.bingeGroup?.trim()?.equals(anchorBingeGroup, ignoreCase = false) == true
            }
            else -> emptyList()
        }
        if (matchingPool.isEmpty()) return null
        if (matchingPool.size == 1) return matchingPool.first()

        val anchorFilename = anchor.p2pPreferredFilename
        val anchorExtracted = anchorFilename?.let(FilenameEpisodeMatcher::extractSeasonEpisode)
        val expectedFilename = if (anchorFilename != null && anchorExtracted != null) {
            FilenameEpisodeMatcher.buildNextFilename(
                filename = anchorFilename,
                currentSeason = anchorExtracted.first,
                currentEpisode = anchorExtracted.second,
                nextSeason = season,
                nextEpisode = episode,
            )
        } else {
            null
        }

        return matchingPool.maxByOrNull { candidate ->
            var score = 0
            val candidateFilename = candidate.p2pPreferredFilename
            if (anchorBingeGroup != null && candidate.behaviorHints.bingeGroup == anchorBingeGroup) {
                score += 8
            }
            if (expectedFilename != null && FilenameEpisodeMatcher.matches(candidateFilename, expectedFilename)) {
                score += 16
            } else if (candidateFilename != null) {
                val parsed = FilenameEpisodeMatcher.extractSeasonEpisode(candidateFilename)
                if (parsed?.second == episode && (season == null || parsed.first == null || parsed.first == season)) {
                    score += 10
                }
            }
            if (anchor.isCachedDebridTorrentStream && candidate.isCachedDebridTorrentStream) {
                score += 4
            }
            if (candidate.playableDirectUrl != null) {
                score += 2
            }
            score
        }
    }

    fun observeSeasonPackLookup(
        type: String,
        parentMetaId: String,
        parentMetaType: String,
        currentVideoId: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String?,
        episodeThumbnail: String?,
        anchorStream: StreamItem,
    ): Flow<SeasonPackLookupProgress> = channelFlow {
        val debridEnabled = DebridSettingsRepository.snapshot().canResolvePlayableLinks
        val sourceTitle = anchorStream.addonName

        val addons = findReadyEnabledAddons(targetAddonId = anchorStream.addonId)
        val targetAddon = addons.firstOrNull { addon ->
            val manifest = addon.manifest ?: return@firstOrNull false
            addon.streamAddonInstanceId(manifest.id) == anchorStream.addonId
        }
        val targetManifest = targetAddon?.manifest
        if (targetAddon == null || targetManifest == null) {
            send(
                SeasonPackLookupProgress(
                    seasonNumber = seasonNumber,
                    sourceTitle = sourceTitle,
                    isLoading = false,
                    checkedEpisodes = 0,
                    totalEpisodes = 0,
                    episodes = emptyList(),
                    errorMessage = "addon_unavailable",
                ),
            )
            return@channelFlow
        }

        val meta = withContext(Dispatchers.Main.immediate) {
            MetaDetailsRepository.peek(type = parentMetaType, id = parentMetaId)
                ?: MetaDetailsRepository.fetch(type = parentMetaType, id = parentMetaId, cacheResult = true)
        }
        val existingByLogicalKey = DownloadsRepository.uiState.value.items
            .associateBy { it.logicalContentKey }

        val rawSeasonVideos = meta?.videos
            .orEmpty()
            .filter { it.season == seasonNumber && it.episode != null && it.available }
            .distinctBy { it.episode }
            .sortedBy { it.episode }

        val seasonVideos = if (rawSeasonVideos.any { it.episode == episodeNumber }) {
            rawSeasonVideos
        } else {
            val syntheticCurrent = MetaVideo(
                id = currentVideoId,
                title = episodeTitle.orEmpty(),
                thumbnail = episodeThumbnail,
                season = seasonNumber,
                episode = episodeNumber,
            )
            (rawSeasonVideos + syntheticCurrent).sortedBy { it.episode }
        }

        if (seasonVideos.isEmpty()) {
            send(
                SeasonPackLookupProgress(
                    seasonNumber = seasonNumber,
                    sourceTitle = sourceTitle,
                    isLoading = false,
                    checkedEpisodes = 0,
                    totalEpisodes = 0,
                    episodes = emptyList(),
                    errorMessage = "no_episodes",
                ),
            )
            return@channelFlow
        }

        val stateMutex = Mutex()
        val workingList = seasonVideos.map { video ->
            val ep = video.episode ?: episodeNumber
            val logicalKey = "${parentMetaId.trim()}|$seasonNumber|$ep"
            val isCurrent = ep == episodeNumber
            SeasonPackEpisodeCandidate(
                video = video,
                seasonNumber = seasonNumber,
                episodeNumber = ep,
                matchedStream = if (isCurrent) anchorStream else null,
                existingDownload = existingByLogicalKey[logicalKey],
                checked = isCurrent,
            )
        }.toMutableList()
        var checkedCount = workingList.count { it.checked }

        send(
            SeasonPackLookupProgress(
                seasonNumber = seasonNumber,
                sourceTitle = sourceTitle,
                isLoading = checkedCount < workingList.size,
                checkedEpisodes = checkedCount,
                totalEpisodes = workingList.size,
                episodes = workingList.toList(),
            ),
        )

        val indicesToCheck = workingList.indices.filter { !workingList[it].checked }
        if (indicesToCheck.isNotEmpty()) {
            val semaphore = Semaphore(ADDON_FETCH_CONCURRENCY)
            coroutineScope {
                indicesToCheck.forEach { index ->
                    launch(Dispatchers.Default) {
                        val candidate = workingList[index]
                        val matched = semaphore.withPermit {
                            fetchMatchingEpisodeStream(
                                addonUrl = targetManifest.transportUrl,
                                addonName = targetAddon.displayTitle.ifBlank { targetManifest.name },
                                addonLogo = targetManifest.logoUrl,
                                type = type,
                                episodeVideo = candidate.video,
                                seasonNumber = seasonNumber,
                                episodeNumber = candidate.episodeNumber,
                                anchor = anchorStream,
                                debridEnabled = debridEnabled,
                            )
                        }
                        val snapshot = stateMutex.withLock {
                            workingList[index] = candidate.copy(
                                matchedStream = matched,
                                checked = true,
                            )
                            checkedCount += 1
                            SeasonPackLookupProgress(
                                seasonNumber = seasonNumber,
                                sourceTitle = sourceTitle,
                                isLoading = checkedCount < workingList.size,
                                checkedEpisodes = checkedCount,
                                totalEpisodes = workingList.size,
                                episodes = workingList.toList(),
                            )
                        }
                        send(snapshot)
                    }
                }
            }
        }
    }

    private suspend fun findReadyEnabledAddons(targetAddonId: String): List<ManagedAddon> =
        withContext(Dispatchers.Main.immediate) {
            AddonRepository.initialize()
            val current = AddonRepository.uiState.value.addons.enabledAddons()
            val hasTarget = current.any { addon ->
                val manifest = addon.manifest ?: return@any false
                addon.streamAddonInstanceId(manifest.id) == targetAddonId
            }
            if (hasTarget || current.none { it.manifest == null && it.isRefreshing }) {
                return@withContext current
            }
            withTimeoutOrNull(ADDON_READY_TIMEOUT_MS) {
                AddonRepository.uiState.first { state ->
                    val enabled = state.addons.enabledAddons()
                    enabled.any { addon ->
                        val manifest = addon.manifest ?: return@any false
                        addon.streamAddonInstanceId(manifest.id) == targetAddonId
                    } || enabled.none { it.manifest == null && it.isRefreshing }
                }
            }?.addons?.enabledAddons() ?: AddonRepository.uiState.value.addons.enabledAddons()
        }

    private suspend fun fetchMatchingEpisodeStream(
        addonUrl: String,
        addonName: String,
        addonLogo: String?,
        type: String,
        episodeVideo: MetaVideo,
        seasonNumber: Int,
        episodeNumber: Int,
        anchor: StreamItem,
        debridEnabled: Boolean,
    ): StreamItem? {
        return try {
            val url = buildAddonResourceUrl(
                manifestUrl = addonUrl,
                resource = "stream",
                type = type,
                id = episodeVideo.id,
            )
            val payload = fetchAddonResponseText(url = url, forceRefresh = false)
            val streams = StreamParser.parse(
                payload = payload,
                addonName = addonName,
                addonId = anchor.addonId,
                addonLogo = addonLogo,
            )
            matchSamePackStream(
                anchor = anchor,
                candidates = streams,
                season = seasonNumber,
                episode = episodeNumber,
                debridEnabled = debridEnabled,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun resolveAndEnqueueSelections(
        contentType: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        selections: List<SeasonPackEpisodeCandidate>,
    ): BatchDownloadEnqueueSummary {
        val ordered = selections
            .filter { it.matchedStream != null }
            .sortedBy { it.episodeNumber }
        if (ordered.isEmpty()) return BatchDownloadEnqueueSummary(0, 0, 0)

        val semaphore = Semaphore(DEBRID_RESOLVE_CONCURRENCY)
        val mutex = Mutex()
        val resolvedPairs = arrayOfNulls<StreamBatchDownloadRequest>(ordered.size)
        var resolveFailures = 0

        coroutineScope {
            ordered.forEachIndexed { index, candidate ->
                launch(Dispatchers.Default) {
                    val rawStream = candidate.matchedStream ?: return@launch
                    val finalStream = if (DirectDebridPlaybackResolver.shouldResolveToPlayableStream(rawStream)) {
                        semaphore.withPermit {
                            when (
                                val result = DirectDebridPlaybackResolver.resolveToPlayableStream(
                                    stream = rawStream,
                                    season = candidate.seasonNumber,
                                    episode = candidate.episodeNumber,
                                )
                            ) {
                                is DirectDebridPlayableResult.Success -> result.stream
                                else -> null
                            }
                        }
                    } else {
                        rawStream
                    }
                    mutex.withLock {
                        if (finalStream == null) {
                            resolveFailures += 1
                        } else {
                            resolvedPairs[index] = StreamBatchDownloadRequest(
                                contentType = contentType,
                                videoId = candidate.video.id,
                                parentMetaId = parentMetaId,
                                parentMetaType = parentMetaType,
                                title = title,
                                logo = logo,
                                poster = poster,
                                background = background,
                                seasonNumber = candidate.seasonNumber,
                                episodeNumber = candidate.episodeNumber,
                                episodeTitle = candidate.video.title.takeIf { it.isNotBlank() },
                                episodeThumbnail = candidate.video.thumbnail,
                                stream = finalStream,
                            )
                        }
                    }
                }
            }
        }

        val readyRequests = resolvedPairs.filterNotNull()
        val summary = withContext(Dispatchers.Main.immediate) {
            DownloadsRepository.enqueueBatchFromStreams(readyRequests)
        }
        return summary.copy(failedCount = summary.failedCount + resolveFailures)
    }
}
