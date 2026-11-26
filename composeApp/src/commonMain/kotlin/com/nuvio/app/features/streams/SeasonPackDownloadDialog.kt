package com.nuvio.app.features.streams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioActionLabel
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.SeasonPackDownloadResolver
import com.nuvio.app.features.downloads.SeasonPackEpisodeCandidate
import com.nuvio.app.features.downloads.SeasonPackLookupProgress
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.downloads_season_pack_deselect_all
import nuvio.composeapp.generated.resources.downloads_season_pack_download_button
import nuvio.composeapp.generated.resources.downloads_season_pack_found_summary
import nuvio.composeapp.generated.resources.downloads_season_pack_load_failed
import nuvio.composeapp.generated.resources.downloads_season_pack_scanning
import nuvio.composeapp.generated.resources.downloads_season_pack_select_all
import nuvio.composeapp.generated.resources.downloads_season_pack_single_only
import nuvio.composeapp.generated.resources.downloads_season_pack_status_available
import nuvio.composeapp.generated.resources.downloads_season_pack_status_checking
import nuvio.composeapp.generated.resources.downloads_season_pack_status_completed
import nuvio.composeapp.generated.resources.downloads_season_pack_status_in_progress
import nuvio.composeapp.generated.resources.downloads_season_pack_status_missing
import nuvio.composeapp.generated.resources.downloads_season_pack_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SeasonPackDownloadDialog(
    anchorStream: StreamItem?,
    type: String,
    parentMetaId: String,
    parentMetaType: String,
    currentVideoId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    episodeThumbnail: String?,
    isSubmitting: Boolean,
    onConfirm: (List<SeasonPackEpisodeCandidate>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (anchorStream == null || seasonNumber == null || episodeNumber == null) return

    val tokens = MaterialTheme.nuvio
    var progress by remember(anchorStream, seasonNumber, episodeNumber) {
        mutableStateOf<SeasonPackLookupProgress?>(null)
    }
    var selectedEpisodeNumbers by remember(anchorStream, seasonNumber, episodeNumber) {
        mutableStateOf(setOf(episodeNumber))
    }

    LaunchedEffect(anchorStream, type, parentMetaId, parentMetaType, currentVideoId, seasonNumber, episodeNumber) {
        SeasonPackDownloadResolver.observeSeasonPackLookup(
            type = type,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            currentVideoId = currentVideoId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            anchorStream = anchorStream,
        ).collect { update ->
            progress = update
        }
    }

    val currentProgress = progress
    val selectableEpisodes = currentProgress?.episodes?.filter { it.isSelectable }.orEmpty()
    val selectableNumbers = selectableEpisodes.map { it.episodeNumber }.toSet()
    val effectiveSelected = selectedEpisodeNumbers.intersect(selectableNumbers)
    val allSelected = selectableEpisodes.isNotEmpty() && effectiveSelected.size == selectableEpisodes.size

    DialogSurface(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = stringResource(Res.string.downloads_season_pack_title, seasonNumber, anchorStream.addonName),
    ) {
        Text(
            text = anchorStream.streamLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (currentProgress == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s80),
                contentAlignment = Alignment.Center,
            ) {
                NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.lg))
            }
        } else if (currentProgress.errorMessage != null && currentProgress.episodes.isEmpty()) {
            Text(
                text = stringResource(Res.string.downloads_season_pack_load_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.danger,
            )
        } else {
            val matchedCount = currentProgress.episodes.count { it.matchedStream != null }
            if (currentProgress.isLoading) {
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6)) {
                    Text(
                        text = stringResource(
                            Res.string.downloads_season_pack_scanning,
                            currentProgress.checkedEpisodes,
                            currentProgress.totalEpisodes,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (currentProgress.totalEpisodes > 0) {
                                currentProgress.checkedEpisodes.toFloat() / currentProgress.totalEpisodes.toFloat()
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            Res.string.downloads_season_pack_found_summary,
                            matchedCount,
                            currentProgress.totalEpisodes,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                    if (selectableEpisodes.size > 1) {
                        NuvioActionLabel(
                            text = if (allSelected) {
                                stringResource(Res.string.downloads_season_pack_deselect_all)
                            } else {
                                stringResource(Res.string.downloads_season_pack_select_all, selectableEpisodes.size)
                            },
                            onClick = {
                                if (!isSubmitting) {
                                    selectedEpisodeNumbers = if (allSelected) {
                                        emptySet()
                                    } else {
                                        selectableNumbers
                                    }
                                }
                            },
                        )
                    }
                }
                if (matchedCount <= 1 && currentProgress.totalEpisodes > 1) {
                    Text(
                        text = stringResource(Res.string.downloads_season_pack_single_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.textMuted,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s40),
                verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                items(
                    items = currentProgress.episodes,
                    key = { "${it.seasonNumber}:${it.episodeNumber}:${it.video.id}" },
                ) { candidate ->
                    val statusDescription = when {
                        candidate.existingDownload?.status == DownloadStatus.Completed ->
                            stringResource(Res.string.downloads_season_pack_status_completed)
                        candidate.isAlreadyInDownloads ->
                            stringResource(Res.string.downloads_season_pack_status_in_progress)
                        !candidate.checked ->
                            stringResource(Res.string.downloads_season_pack_status_checking)
                        candidate.matchedStream == null ->
                            stringResource(Res.string.downloads_season_pack_status_missing)
                        else ->
                            stringResource(Res.string.downloads_season_pack_status_available)
                    }
                    val titleText = buildString {
                        append('S')
                        append(candidate.seasonNumber.toString().padStart(2, '0'))
                        append('E')
                        append(candidate.episodeNumber.toString().padStart(2, '0'))
                        val epTitle = candidate.video.title.trim()
                        if (epTitle.isNotEmpty()) {
                            append(" • ")
                            append(epTitle)
                        }
                    }
                    val isChecked = candidate.isSelectable && candidate.episodeNumber in effectiveSelected
                    DialogOption(
                        text = titleText,
                        description = statusDescription,
                        selected = isChecked,
                        enabled = !isSubmitting && candidate.isSelectable,
                        role = Role.Checkbox,
                        onClick = {
                            selectedEpisodeNumbers = if (candidate.episodeNumber in selectedEpisodeNumbers) {
                                selectedEpisodeNumbers - candidate.episodeNumber
                            } else {
                                selectedEpisodeNumbers + candidate.episodeNumber
                            }
                        },
                    )
                }
            }
        }

        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
                enabled = !isSubmitting,
            )
            DialogButton(
                text = stringResource(Res.string.downloads_season_pack_download_button, effectiveSelected.size),
                onClick = {
                    val chosen = selectableEpisodes.filter { it.episodeNumber in effectiveSelected }
                    onConfirm(chosen)
                },
                style = DialogButtonStyle.Primary,
                enabled = effectiveSelected.isNotEmpty(),
                loading = isSubmitting,
            )
        }
    }
}
