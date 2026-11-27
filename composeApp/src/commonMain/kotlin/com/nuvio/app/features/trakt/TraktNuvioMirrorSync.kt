package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.servers.ServerItemRef
import com.nuvio.app.features.servers.ServerWatched
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.normalizedMarkedAt
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watching.sync.TraktWatchedSyncAdapter
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceLocal
import com.nuvio.app.features.watchprogress.WatchProgressSourceTraktPlayback
import com.nuvio.app.features.watchprogress.resolvedProgressKey
import com.nuvio.app.features.watchprogress.withResolvedProgressKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToLong

private const val MIN_REAL_DURATION_MS = 121_000L
internal const val TRAKT_MIRROR_SYNTHETIC_DURATION_MS = 3_600_000L
private const val WATCHED_MIRROR_TIMESTAMP_TOLERANCE_MS = 60_000L
private const val PROGRESS_MIRROR_TIMESTAMP_TOLERANCE_MS = 1_000L
private const val PROGRESS_MIRROR_PERCENT_TOLERANCE = 1.5

internal fun isSafeNuvioMirrorId(contentId: String): Boolean {
    val trimmed = contentId.trim()
    return trimmed.isNotBlank() &&
        !trimmed.startsWith("trakt:", ignoreCase = true) &&
        !ServerItemRef.isServerId(trimmed)
}

internal fun computeTraktProgressMirrorUpdates(
    traktEntries: List<WatchProgressEntry>,
    existingNuvioEntries: List<WatchProgressEntry>,
): List<WatchProgressEntry> {
    if (traktEntries.isEmpty()) return emptyList()

    val existingByKey = existingNuvioEntries.associateBy { it.resolvedProgressKey() }
    val updatesByKey = linkedMapOf<String, WatchProgressEntry>()

    for (entry in traktEntries) {
        if (entry.source != WatchProgressSourceTraktPlayback) continue
        if (entry.isEffectivelyCompleted) continue
        if (!isSafeNuvioMirrorId(entry.parentMetaId)) continue

        val percent = entry.normalizedProgressPercent
            ?.toDouble()
            ?.takeIf { it > 0.0 }
            ?.coerceIn(1.0, 89.9)
            ?: continue

        val progressKey = entry.resolvedProgressKey()
        val existing = existingByKey[progressKey] ?: existingNuvioEntries.firstOrNull { candidate ->
            candidate.parentMetaId == entry.parentMetaId &&
                candidate.seasonNumber == entry.seasonNumber &&
                candidate.episodeNumber == entry.episodeNumber
        }

        val durationMs: Long
        val positionMs: Long
        if (existing != null) {
            if (existing.lastUpdatedEpochMs >= entry.lastUpdatedEpochMs - PROGRESS_MIRROR_TIMESTAMP_TOLERANCE_MS) {
                continue
            }
            if (existing.durationMs >= MIN_REAL_DURATION_MS) {
                val existingPercent = (existing.lastPositionMs.toDouble() / existing.durationMs.toDouble()) * 100.0
                if (abs(existingPercent - percent) <= PROGRESS_MIRROR_PERCENT_TOLERANCE) {
                    continue
                }
                durationMs = existing.durationMs
                positionMs = ((durationMs.toDouble() * percent) / 100.0)
                    .roundToLong()
                    .coerceIn(1_000L, durationMs - 1L)
            } else {
                durationMs = TRAKT_MIRROR_SYNTHETIC_DURATION_MS
                positionMs = ((durationMs.toDouble() * percent) / 100.0)
                    .roundToLong()
                    .coerceIn(60_000L, durationMs - 1L)
            }
        } else {
            durationMs = TRAKT_MIRROR_SYNTHETIC_DURATION_MS
            positionMs = ((durationMs.toDouble() * percent) / 100.0)
                .roundToLong()
                .coerceIn(60_000L, durationMs - 1L)
        }

        val mirrored = entry.copy(
            logo = entry.logo ?: existing?.logo,
            poster = entry.poster ?: existing?.poster,
            background = entry.background ?: existing?.background,
            episodeTitle = entry.episodeTitle ?: existing?.episodeTitle,
            episodeThumbnail = entry.episodeThumbnail ?: existing?.episodeThumbnail,
            lastPositionMs = positionMs,
            durationMs = durationMs,
            isCompleted = false,
            progressPercent = null,
            source = WatchProgressSourceLocal,
            trackingProviderId = null,
            trackingProviderItemId = null,
            trackingSourceUrl = null,
        ).withResolvedProgressKey()

        val previousQueued = updatesByKey[mirrored.resolvedProgressKey()]
        if (previousQueued == null || mirrored.lastUpdatedEpochMs > previousQueued.lastUpdatedEpochMs) {
            updatesByKey[mirrored.resolvedProgressKey()] = mirrored
        }
    }

    return updatesByKey.values.toList()
}

internal fun computeTraktWatchedMirrorAdditions(
    traktItems: Collection<WatchedItem>,
    existingNuvioItems: Collection<WatchedItem>,
): List<WatchedItem> {
    if (traktItems.isEmpty()) return emptyList()

    val existingByKey = existingNuvioItems
        .asSequence()
        .map(WatchedItem::normalizedMarkedAt)
        .associateBy { watchedItemKey(it.type, it.id, it.season, it.episode) }

    val additionsByKey = linkedMapOf<String, WatchedItem>()
    for (rawItem in traktItems) {
        if (!isSafeNuvioMirrorId(rawItem.id)) continue
        val item = rawItem.normalizedMarkedAt()
        if (ServerWatched.isServerItem(item)) continue
        val key = watchedItemKey(item.type, item.id, item.season, item.episode)
        val existing = existingByKey[key]
        if (existing != null && existing.markedAtEpochMs >= item.markedAtEpochMs - WATCHED_MIRROR_TIMESTAMP_TOLERANCE_MS) {
            continue
        }
        val queued = additionsByKey[key]
        if (queued == null || item.markedAtEpochMs > queued.markedAtEpochMs) {
            additionsByKey[key] = item
        }
    }

    return additionsByKey.values.toList()
}

internal object TraktNuvioMirrorSync {
    private val log = Logger.withTag("TraktNuvioMirror")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mirrorMutex = Mutex()
    private var lastMirroredWatchedProfileId: Int? = null

    fun isMirrorActive(): Boolean {
        TraktSettingsRepository.ensureLoaded()
        if (!TraktSettingsRepository.uiState.value.mirrorTraktToNuvioSync) return false
        TraktAuthRepository.ensureLoaded()
        if (!TraktAuthRepository.isAuthenticated.value) return false
        val authState = AuthRepository.state.value
        return authState is AuthState.Authenticated && !authState.isAnonymous
    }

    fun mirrorProgressSnapshotAsync(entries: List<WatchProgressEntry>) {
        if (!isMirrorActive()) return
        val snapshot = entries.toList()
        scope.launch {
            mirrorMutex.withLock {
                mirrorProgressSnapshotLocked(snapshot)
            }
        }
    }

    fun mirrorWatchedItemsAsync(
        items: List<WatchedItem>,
        profileId: Int = ProfileRepository.activeProfileId,
    ) {
        if (!isMirrorActive()) return
        val snapshot = items.toList()
        scope.launch {
            mirrorMutex.withLock {
                mirrorWatchedItemsLocked(
                    items = snapshot,
                    profileId = profileId,
                )
            }
        }
    }

    fun onMirrorEnabledAsync() {
        if (!isMirrorActive()) return
        scope.launch {
            syncAllLocked()
        }
    }

    fun onNuvioAuthenticatedAsync() {
        if (!isMirrorActive()) return
        scope.launch {
            mirrorMutex.withLock {
                if (!isMirrorActive()) return@withLock
                val progressState = TraktProgressRepository.uiState.value
                if (progressState.hasLoadedRemoteProgress) {
                    mirrorProgressSnapshotLocked(progressState.entries)
                }
                val activeProfileId = ProfileRepository.activeProfileId
                if (lastMirroredWatchedProfileId != activeProfileId) {
                    runCatching {
                        val watched = TraktWatchedSyncAdapter.pull(
                            profileId = activeProfileId,
                            pageSize = 900,
                        )
                        mirrorWatchedItemsLocked(
                            items = watched,
                            profileId = activeProfileId,
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        log.w(error) { "Failed to mirror Trakt watched history on Nuvio auth" }
                    }
                }
            }
        }
    }

    private suspend fun syncAllLocked() = mirrorMutex.withLock {
        if (!isMirrorActive()) return@withLock
        val activeProfileId = ProfileRepository.activeProfileId
        runCatching {
            TraktProgressRepository.refreshNow()
            val progressState = TraktProgressRepository.uiState.value
            if (progressState.errorMessage == null && progressState.hasLoadedRemoteProgress) {
                mirrorProgressSnapshotLocked(progressState.entries)
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Failed to refresh Trakt progress for Nuvio Sync mirror" }
        }
        runCatching {
            val watched = TraktWatchedSyncAdapter.pull(
                profileId = activeProfileId,
                pageSize = 900,
            )
            mirrorWatchedItemsLocked(
                items = watched,
                profileId = activeProfileId,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Failed to pull Trakt watched history for Nuvio Sync mirror" }
        }
    }

    private suspend fun mirrorProgressSnapshotLocked(entries: List<WatchProgressEntry>) {
        if (!isMirrorActive()) return
        val profileId = ProfileRepository.activeProfileId
        runCatching {
            WatchProgressRepository.mirrorEntriesToNuvioSync(
                profileId = profileId,
                computeUpdates = { existing ->
                    computeTraktProgressMirrorUpdates(
                        traktEntries = entries,
                        existingNuvioEntries = existing,
                    )
                },
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Failed to mirror Trakt progress to Nuvio Sync for profile $profileId" }
        }
    }

    private suspend fun mirrorWatchedItemsLocked(
        items: List<WatchedItem>,
        profileId: Int,
    ) {
        if (!isMirrorActive()) return
        if (ProfileRepository.activeProfileId != profileId) return
        runCatching {
            WatchedRepository.mirrorItemsToNuvioSync(
                profileId = profileId,
                computeAdditions = { existing ->
                    computeTraktWatchedMirrorAdditions(
                        traktItems = items,
                        existingNuvioItems = existing,
                    )
                },
            )
            lastMirroredWatchedProfileId = profileId
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Failed to mirror Trakt watched items to Nuvio Sync for profile $profileId" }
        }
    }
}
