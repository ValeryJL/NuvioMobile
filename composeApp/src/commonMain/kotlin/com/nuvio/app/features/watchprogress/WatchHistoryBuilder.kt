package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.normalizedMarkedAt
import com.nuvio.app.features.watched.watchedItemTypeAliases

enum class WatchHistoryFilter {
    ALL,
    COMPLETED,
    IN_PROGRESS,
}

data class WatchHistoryEntry(
    val key: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String?,
    val title: String,
    val poster: String?,
    val background: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val activityEpochMs: Long,
    val positionMs: Long,
    val durationMs: Long,
    val progressFraction: Float,
    val isCompleted: Boolean,
) {
    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isSeriesLike: Boolean
        get() = isEpisode || isSeriesLikeHistoryType(parentMetaType)

    val artworkUrl: String?
        get() = episodeThumbnail.nonBlankOrNull()
            ?: background.nonBlankOrNull()
            ?: poster.nonBlankOrNull()

    fun enrichWithMetaDetails(meta: MetaDetails): WatchHistoryEntry {
        val matchedEpisode = if (isEpisode || !videoId.isNullOrBlank()) {
            meta.videos.firstOrNull { episode ->
                (!videoId.isNullOrBlank() && episode.id == videoId) ||
                    (seasonNumber != null &&
                        episodeNumber != null &&
                        episode.season == seasonNumber &&
                        episode.episode == episodeNumber)
            }
        } else {
            null
        }
        val seasonPoster = seasonNumber?.let { meta.seasonPosters[it] }.nonBlankOrNull()
        val matchedRuntimeMs = matchedEpisode?.runtime?.toLong()?.takeIf { it > 0L }?.times(60_000L) ?: 0L
        return copy(
            title = title.ifBlank { meta.name },
            poster = poster.nonBlankOrNull() ?: seasonPoster ?: meta.poster.nonBlankOrNull(),
            background = background.nonBlankOrNull() ?: meta.background.nonBlankOrNull(),
            episodeTitle = episodeTitle.nonBlankOrNull() ?: matchedEpisode?.title.nonBlankOrNull(),
            episodeThumbnail = episodeThumbnail.nonBlankOrNull() ?: matchedEpisode?.thumbnail.nonBlankOrNull(),
            durationMs = if (durationMs > 0L) durationMs else matchedRuntimeMs,
        )
    }
}

private data class HistoryKey(
    val dedupKey: String,
    val canonicalMetaId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val isWholeSeriesMarker: Boolean,
)

private data class MergedProgressCandidate(
    val primary: WatchProgressEntry,
    val title: String,
    val poster: String?,
    val background: String?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
)

private data class MergedWatchedCandidate(
    val newest: WatchedItem,
    val name: String,
    val poster: String?,
    val videoId: String?,
)

private data class HistoryDraft(
    val key: String,
    val canonicalMetaId: String,
    val isWholeSeriesMarker: Boolean,
    val entry: WatchHistoryEntry,
)

/**
 * Builds a chronologically sorted (most-recent-first) watch history with at most
 * one record per movie or episode reflecting its latest playback/completion state.
 */
fun buildWatchHistoryEntries(
    progressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
): List<WatchHistoryEntry> {
    val progressByKey = LinkedHashMap<String, MutableList<Pair<HistoryKey, WatchProgressEntry>>>()
    progressEntries.forEach { rawEntry ->
        val normalized = rawEntry.normalizedCompletion()
        val keyInfo = buildHistoryKey(
            metaId = normalized.parentMetaId,
            metaType = normalized.parentMetaType,
            videoId = normalized.videoId,
            seasonNumber = normalized.seasonNumber,
            episodeNumber = normalized.episodeNumber,
        ) ?: return@forEach
        progressByKey.getOrPut(keyInfo.dedupKey) { mutableListOf() }.add(keyInfo to normalized)
    }

    val watchedByKey = LinkedHashMap<String, MutableList<Pair<HistoryKey, WatchedItem>>>()
    watchedItems.forEach { rawItem ->
        val normalized = rawItem.normalizedMarkedAt()
        val keyInfo = buildHistoryKey(
            metaId = normalized.id,
            metaType = normalized.type,
            videoId = normalized.videoId,
            seasonNumber = normalized.season,
            episodeNumber = normalized.episode,
        ) ?: return@forEach
        watchedByKey.getOrPut(keyInfo.dedupKey) { mutableListOf() }.add(keyInfo to normalized)
    }

    val allKeys = LinkedHashSet<String>(progressByKey.size + watchedByKey.size).apply {
        addAll(progressByKey.keys)
        addAll(watchedByKey.keys)
    }

    val drafts = ArrayList<HistoryDraft>(allKeys.size)
    allKeys.forEach { dedupKey ->
        val progressGroup = progressByKey[dedupKey].orEmpty()
        val watchedGroup = watchedByKey[dedupKey].orEmpty()
        val keyInfo = progressGroup.firstOrNull()?.first ?: watchedGroup.first().first

        val progress = mergeProgressGroup(progressGroup.map { it.second })
        val watched = mergeWatchedGroup(watchedGroup.map { it.second })

        val entry = combineToHistoryEntry(
            keyInfo = keyInfo,
            progress = progress,
            watched = watched,
        ) ?: return@forEach

        drafts.add(
            HistoryDraft(
                key = dedupKey,
                canonicalMetaId = keyInfo.canonicalMetaId,
                isWholeSeriesMarker = keyInfo.isWholeSeriesMarker,
                entry = entry,
            ),
        )
    }

    val metaIdsWithSpecificEntries = drafts
        .asSequence()
        .filterNot { it.isWholeSeriesMarker }
        .mapTo(HashSet()) { it.canonicalMetaId }

    return drafts
        .asSequence()
        .filterNot { draft ->
            draft.isWholeSeriesMarker && draft.canonicalMetaId in metaIdsWithSpecificEntries
        }
        .map { it.entry }
        .sortedWith(watchHistoryDisplayComparator)
        .toList()
}

fun filterWatchHistoryEntries(
    entries: List<WatchHistoryEntry>,
    filter: WatchHistoryFilter,
    query: String,
): List<WatchHistoryEntry> {
    val trimmedQuery = query.trim()
    return entries.filter { entry ->
        val matchesFilter = when (filter) {
            WatchHistoryFilter.ALL -> true
            WatchHistoryFilter.COMPLETED -> entry.isCompleted
            WatchHistoryFilter.IN_PROGRESS -> !entry.isCompleted
        }
        if (!matchesFilter) return@filter false
        if (trimmedQuery.isEmpty()) return@filter true

        entry.title.contains(trimmedQuery, ignoreCase = true) ||
            (entry.episodeTitle?.contains(trimmedQuery, ignoreCase = true) == true) ||
            formatCompactEpisodeToken(entry.seasonNumber, entry.episodeNumber)
                ?.contains(trimmedQuery, ignoreCase = true) == true
    }
}

private val watchHistoryDisplayComparator =
    compareByDescending<WatchHistoryEntry> { it.activityEpochMs }
        .thenBy { it.title.lowercase() }
        .thenBy { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }

private fun combineToHistoryEntry(
    keyInfo: HistoryKey,
    progress: MergedProgressCandidate?,
    watched: MergedWatchedCandidate?,
): WatchHistoryEntry? {
    val entry = progress?.primary
    val watchedItem = watched?.newest

    if (entry == null && watchedItem == null) return null

    if (entry == null && watchedItem != null) {
        return WatchHistoryEntry(
            key = keyInfo.dedupKey,
            parentMetaId = watchedItem.id.trim(),
            parentMetaType = watchedItem.type,
            videoId = watched.videoId,
            title = watched.name,
            poster = watched.poster,
            background = null,
            seasonNumber = keyInfo.seasonNumber,
            episodeNumber = keyInfo.episodeNumber,
            episodeTitle = null,
            episodeThumbnail = null,
            activityEpochMs = watchedItem.markedAtEpochMs.coerceAtLeast(0L),
            positionMs = 0L,
            durationMs = 0L,
            progressFraction = 1f,
            isCompleted = true,
        )
    }

    val p = entry!!
    val progressTs = p.lastUpdatedEpochMs.coerceAtLeast(0L)
    val watchedTs = watchedItem?.markedAtEpochMs?.coerceAtLeast(0L) ?: -1L
    val hasMeasurableProgress = p.lastPositionMs > 0L || p.progressFraction > 0f

    val isCompleted: Boolean
    val activityEpochMs: Long
    when {
        p.isEffectivelyCompleted -> {
            isCompleted = true
            activityEpochMs = maxOf(progressTs, watchedTs.coerceAtLeast(0L))
        }
        watchedItem != null && watchedTs > progressTs -> {
            isCompleted = true
            activityEpochMs = watchedTs
        }
        hasMeasurableProgress -> {
            isCompleted = false
            activityEpochMs = progressTs
        }
        watchedItem != null -> {
            isCompleted = true
            activityEpochMs = maxOf(progressTs, watchedTs)
        }
        else -> return null
    }

    val durationMs = p.durationMs.coerceAtLeast(0L)
    val positionMs = when {
        p.lastPositionMs > 0L -> p.lastPositionMs
        !isCompleted && durationMs > 0L && p.progressFraction > 0f -> p.resolveResumePosition(durationMs)
        else -> 0L
    }
    val progressFraction = if (isCompleted) {
        1f
    } else {
        p.progressFraction.coerceIn(0f, 1f)
    }

    return WatchHistoryEntry(
        key = keyInfo.dedupKey,
        parentMetaId = p.parentMetaId.trim().ifBlank { watchedItem?.id?.trim().orEmpty() },
        parentMetaType = p.parentMetaType.ifBlank { watchedItem?.type.orEmpty() },
        videoId = p.videoId.nonBlankOrNull() ?: watched?.videoId,
        title = progress.title.ifBlank { watched?.name.orEmpty() },
        poster = progress.poster ?: watched?.poster,
        background = progress.background,
        seasonNumber = keyInfo.seasonNumber ?: p.seasonNumber ?: watchedItem?.season,
        episodeNumber = keyInfo.episodeNumber ?: p.episodeNumber ?: watchedItem?.episode,
        episodeTitle = progress.episodeTitle,
        episodeThumbnail = progress.episodeThumbnail,
        activityEpochMs = activityEpochMs,
        positionMs = positionMs,
        durationMs = durationMs,
        progressFraction = progressFraction,
        isCompleted = isCompleted,
    )
}

private fun mergeProgressGroup(entries: List<WatchProgressEntry>): MergedProgressCandidate? {
    if (entries.isEmpty()) return null
    val sorted = entries.sortedWith(progressFreshnessComparator)
    val primary = sorted.first()
    return MergedProgressCandidate(
        primary = primary,
        title = sorted.firstNotNullOfOrNull { it.title.nonBlankOrNull() }.orEmpty(),
        poster = sorted.firstNotNullOfOrNull { it.poster.nonBlankOrNull() },
        background = sorted.firstNotNullOfOrNull { it.background.nonBlankOrNull() },
        episodeTitle = sorted.firstNotNullOfOrNull { it.episodeTitle.nonBlankOrNull() },
        episodeThumbnail = sorted.firstNotNullOfOrNull { it.episodeThumbnail.nonBlankOrNull() },
    )
}

private val progressFreshnessComparator =
    compareByDescending<WatchProgressEntry> { it.lastUpdatedEpochMs }
        .thenByDescending { it.isEffectivelyCompleted }
        .thenByDescending { it.durationMs > 0L }
        .thenByDescending { it.lastPositionMs }
        .thenByDescending { it.progressFraction }

private fun mergeWatchedGroup(items: List<WatchedItem>): MergedWatchedCandidate? {
    if (items.isEmpty()) return null
    val sorted = items.sortedByDescending { it.markedAtEpochMs }
    val newest = sorted.first()
    return MergedWatchedCandidate(
        newest = newest,
        name = sorted.firstNotNullOfOrNull { it.name.nonBlankOrNull() }.orEmpty(),
        poster = sorted.firstNotNullOfOrNull { it.poster.nonBlankOrNull() },
        videoId = sorted.firstNotNullOfOrNull { it.videoId.nonBlankOrNull() },
    )
}

private fun buildHistoryKey(
    metaId: String,
    metaType: String,
    videoId: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
): HistoryKey? {
    val cleanId = metaId.trim()
    val loweredId = cleanId.lowercase()
    if (loweredId.isBlank()) return null

    val parsedPair = if (seasonNumber == null || episodeNumber == null) {
        parseEpisodeFromVideoId(cleanId, videoId)
    } else {
        null
    }
    val resolvedSeason = seasonNumber ?: parsedPair?.first
    val resolvedEpisode = episodeNumber ?: parsedPair?.second

    if (resolvedSeason != null && resolvedEpisode != null) {
        return HistoryKey(
            dedupKey = "$loweredId:e:$resolvedSeason:$resolvedEpisode",
            canonicalMetaId = loweredId,
            seasonNumber = resolvedSeason,
            episodeNumber = resolvedEpisode,
            isWholeSeriesMarker = false,
        )
    }

    if (!isSeriesLikeHistoryType(metaType)) {
        return HistoryKey(
            dedupKey = "$loweredId:m",
            canonicalMetaId = loweredId,
            seasonNumber = null,
            episodeNumber = null,
            isWholeSeriesMarker = false,
        )
    }

    val cleanVideoId = videoId
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals(cleanId, ignoreCase = true) }
    if (cleanVideoId != null) {
        return HistoryKey(
            dedupKey = "$loweredId:v:${cleanVideoId.lowercase()}",
            canonicalMetaId = loweredId,
            seasonNumber = null,
            episodeNumber = null,
            isWholeSeriesMarker = false,
        )
    }

    return HistoryKey(
        dedupKey = "$loweredId:s",
        canonicalMetaId = loweredId,
        seasonNumber = null,
        episodeNumber = null,
        isWholeSeriesMarker = true,
    )
}

private fun parseEpisodeFromVideoId(metaId: String, videoId: String?): Pair<Int, Int>? {
    val raw = videoId?.trim() ?: return null
    val prefix = "$metaId:"
    if (!raw.startsWith(prefix, ignoreCase = true)) return null
    val parts = raw.substring(prefix.length).split(':')
    if (parts.size != 2) return null
    val season = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val episode = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
    return season to episode
}

private fun isSeriesLikeHistoryType(type: String): Boolean =
    watchedItemTypeAliases(type).contains("series")

private fun formatCompactEpisodeToken(season: Int?, episode: Int?): String? {
    if (season == null || episode == null) return null
    val s = season.toString().padStart(2, '0')
    val e = episode.toString().padStart(2, '0')
    return "S${s}E${e}"
}

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }
