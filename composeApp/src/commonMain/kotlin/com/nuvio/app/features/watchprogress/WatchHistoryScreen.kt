package com.nuvio.app.features.watchprogress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioTypeScale
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.formatPlaybackTime
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.media_movie
import nuvio.composeapp.generated.resources.media_series
import nuvio.composeapp.generated.resources.watch_history_count
import nuvio.composeapp.generated.resources.watch_history_empty_message
import nuvio.composeapp.generated.resources.watch_history_empty_title
import nuvio.composeapp.generated.resources.watch_history_filter_all
import nuvio.composeapp.generated.resources.watch_history_filter_completed
import nuvio.composeapp.generated.resources.watch_history_filter_in_progress
import nuvio.composeapp.generated.resources.watch_history_filtered_empty_message
import nuvio.composeapp.generated.resources.watch_history_filtered_empty_title
import nuvio.composeapp.generated.resources.watch_history_search_placeholder
import nuvio.composeapp.generated.resources.watch_history_status_completed
import nuvio.composeapp.generated.resources.watch_history_status_watched_until
import nuvio.composeapp.generated.resources.watch_history_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val MAX_METADATA_ENRICH_TARGETS = 40
private const val METADATA_ENRICH_CONCURRENCY = 4

@Composable
fun WatchHistoryScreen(
    onBack: () -> Unit,
    onOpenDetails: (WatchHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val watchProgressState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()

    val baseEntries = remember(watchProgressState.entries, watchedState.items) {
        buildWatchHistoryEntries(
            progressEntries = watchProgressState.entries,
            watchedItems = watchedState.items,
        )
    }

    var fetchedMetaByKey by remember { mutableStateOf<Map<String, MetaDetails>>(emptyMap()) }
    LaunchedEffect(baseEntries) {
        val candidates = baseEntries
            .filter { entry ->
                entry.parentMetaId.isNotBlank() &&
                    entry.parentMetaType.isNotBlank() &&
                    (entry.title.isBlank() ||
                        entry.artworkUrl == null ||
                        (entry.isEpisode &&
                            (entry.episodeTitle.isNullOrBlank() || entry.episodeThumbnail.isNullOrBlank())))
            }
            .distinctBy { "${it.parentMetaType}:${it.parentMetaId}" }
            .take(MAX_METADATA_ENRICH_TARGETS)
            .filter { entry ->
                val lookupKey = "${entry.parentMetaType}:${entry.parentMetaId}"
                lookupKey !in fetchedMetaByKey &&
                    MetaDetailsRepository.peek(entry.parentMetaType, entry.parentMetaId) == null
            }

        if (candidates.isEmpty()) return@LaunchedEffect

        val loaded = withContext(Dispatchers.Default) {
            val semaphore = Semaphore(METADATA_ENRICH_CONCURRENCY)
            coroutineScope {
                candidates.map { candidate ->
                    async {
                        semaphore.withPermit {
                            val meta = runCatching {
                                MetaDetailsRepository.fetch(
                                    type = candidate.parentMetaType,
                                    id = candidate.parentMetaId,
                                )
                            }.getOrNull() ?: return@withPermit null
                            "${candidate.parentMetaType}:${candidate.parentMetaId}" to meta
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        }
        if (loaded.isNotEmpty()) {
            fetchedMetaByKey = fetchedMetaByKey + loaded
        }
    }

    val enrichedEntries = remember(baseEntries, fetchedMetaByKey) {
        baseEntries.map { entry ->
            val lookupKey = "${entry.parentMetaType}:${entry.parentMetaId}"
            val meta = fetchedMetaByKey[lookupKey]
                ?: MetaDetailsRepository.peek(entry.parentMetaType, entry.parentMetaId)
            if (meta != null) entry.enrichWithMetaDetails(meta) else entry
        }
    }

    var selectedFilter by rememberSaveable { mutableStateOf(WatchHistoryFilter.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val completedCount = remember(enrichedEntries) { enrichedEntries.count { it.isCompleted } }
    val inProgressCount = remember(enrichedEntries) { enrichedEntries.size - completedCount }
    val visibleEntries = remember(enrichedEntries, selectedFilter, searchQuery) {
        filterWatchHistoryEntries(
            entries = enrichedEntries,
            filter = selectedFilter,
            query = searchQuery,
        )
    }

    val listState = rememberLazyListState()
    val tokens = MaterialTheme.nuvio

    NuvioScreen(
        modifier = modifier,
        listState = listState,
    ) {
        stickyHeader(key = "watch_history_header") {
            NuvioScreenHeader(
                title = stringResource(Res.string.watch_history_title),
                onBack = onBack,
            )
        }

        if (enrichedEntries.isEmpty()) {
            item(key = "watch_history_empty") {
                WatchHistoryEmptyCard(
                    title = stringResource(Res.string.watch_history_empty_title),
                    message = stringResource(Res.string.watch_history_empty_message),
                )
            }
            return@NuvioScreen
        }

        item(key = "watch_history_controls") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
            ) {
                NuvioInputField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = stringResource(Res.string.watch_history_search_placeholder),
                    trailingContent = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    tint = tokens.colors.textMuted,
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.padding(end = NuvioTokens.Space.s12),
                            )
                        }
                    },
                )

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                ) {
                    item(key = "filter_all") {
                        Chip(
                            label = "${stringResource(Res.string.watch_history_filter_all)} (${enrichedEntries.size})",
                            selected = selectedFilter == WatchHistoryFilter.ALL,
                            onClick = { selectedFilter = WatchHistoryFilter.ALL },
                        )
                    }
                    item(key = "filter_completed") {
                        Chip(
                            label = "${stringResource(Res.string.watch_history_filter_completed)} ($completedCount)",
                            selected = selectedFilter == WatchHistoryFilter.COMPLETED,
                            onClick = { selectedFilter = WatchHistoryFilter.COMPLETED },
                        )
                    }
                    item(key = "filter_in_progress") {
                        Chip(
                            label = "${stringResource(Res.string.watch_history_filter_in_progress)} ($inProgressCount)",
                            selected = selectedFilter == WatchHistoryFilter.IN_PROGRESS,
                            onClick = { selectedFilter = WatchHistoryFilter.IN_PROGRESS },
                        )
                    }
                }

                Text(
                    text = pluralStringResource(
                        Res.plurals.watch_history_count,
                        visibleEntries.size,
                        visibleEntries.size,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.colors.textMuted,
                )
            }
        }

        if (visibleEntries.isEmpty()) {
            item(key = "watch_history_filtered_empty") {
                WatchHistoryEmptyCard(
                    title = stringResource(Res.string.watch_history_filtered_empty_title),
                    message = stringResource(Res.string.watch_history_filtered_empty_message),
                )
            }
            return@NuvioScreen
        }

        items(
            items = visibleEntries,
            key = { it.key },
            contentType = { "watch_history_item" },
        ) { entry ->
            WatchHistoryRowCard(
                entry = entry,
                onClick = { onOpenDetails(entry) },
            )
        }
    }
}

@Composable
private fun WatchHistoryRowCard(
    entry: WatchHistoryEntry,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.card)
            .clickable(onClick = onClick),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        tonalElevation = 0.dp,
        shadowElevation = tokens.elevation.flat,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTokens.Space.s12),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WatchHistoryThumbnail(entry = entry)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
            ) {
                Text(
                    text = entry.title.ifBlank { entry.parentMetaId },
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                    maxLines = if (entry.isEpisode) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )

                val subtitle = watchHistorySubtitle(entry)
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.nuvioTypeScale.bodySm,
                        color = tokens.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                WatchHistoryStatusRow(entry = entry)

                entry.activityEpochMs
                    .takeIf { it > 0L }
                    ?.let { epochMs ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = tokens.colors.textMuted,
                                modifier = Modifier.size(13.dp),
                            )
                            Text(
                                text = formatLocalDateTime(epochMs),
                                style = MaterialTheme.nuvioTypeScale.bodySm,
                                color = tokens.colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun WatchHistoryThumbnail(entry: WatchHistoryEntry) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.sm)
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(64.dp)
            .clip(shape)
            .background(tokens.colors.surfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        val artworkUrl = entry.artworkUrl
        if (artworkUrl != null) {
            AsyncImage(
                model = artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = if (entry.isSeriesLike) Icons.Rounded.Tv else Icons.Rounded.Movie,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(NuvioTokens.Icon.md),
            )
        }

        if (entry.isCompleted) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(NuvioTokens.Space.s4)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.68f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(12.dp),
                )
            }
        } else if (entry.progressFraction > 0f) {
            NuvioProgressBar(
                progress = entry.progressFraction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = NuvioTokens.Space.s6, vertical = NuvioTokens.Space.s4),
                height = 3.dp,
                trackColor = Color.Black.copy(alpha = 0.48f),
            )
        }
    }
}

@Composable
private fun WatchHistoryStatusRow(entry: WatchHistoryEntry) {
    val tokens = MaterialTheme.nuvio
    if (entry.isCompleted) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(13.dp),
            )
            val statusText = buildString {
                append(stringResource(Res.string.watch_history_status_completed))
                if (entry.durationMs > 0L) {
                    append(" · ")
                    append(formatPlaybackTime(entry.durationMs))
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.nuvioTypeScale.bodySm,
                color = tokens.colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        val percent = (entry.progressFraction * 100f).toInt().coerceIn(0, 99)
        val progressLabel = when {
            entry.durationMs > 0L -> {
                val base = "${formatPlaybackTime(entry.positionMs)} / ${formatPlaybackTime(entry.durationMs)}"
                if (percent > 0) "$base · $percent%" else base
            }
            entry.positionMs > 0L ->
                stringResource(Res.string.watch_history_status_watched_until, formatPlaybackTime(entry.positionMs))
            else -> "$percent%"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = tokens.colors.textSecondary,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = progressLabel,
                style = MaterialTheme.nuvioTypeScale.bodySm,
                color = tokens.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun watchHistorySubtitle(entry: WatchHistoryEntry): String =
    when {
        entry.isEpisode -> buildContinueWatchingEpisodeSubtitle(
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
        )
        entry.isSeriesLike -> stringResource(Res.string.media_series)
        else -> stringResource(Res.string.media_movie)
    }

@Composable
private fun WatchHistoryEmptyCard(
    title: String,
    message: String,
) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = NuvioTokens.Space.s16),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.surfaceCard),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                    modifier = Modifier.size(NuvioTokens.Icon.md),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
