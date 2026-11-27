package com.nuvio.app.features.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.downloads.DownloadStatus
import com.nuvio.app.features.downloads.SeasonPackDownloadResolver
import com.nuvio.app.features.downloads.SeasonPackEpisodeCandidate
import com.nuvio.app.features.downloads.SeasonPackLookupProgress
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.downloads_season_pack_all_watched
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
import nuvio.composeapp.generated.resources.downloads_season_pack_status_watched
import nuvio.composeapp.generated.resources.downloads_season_pack_title
import nuvio.composeapp.generated.resources.downloads_season_pack_unwatched_only
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
    var userToggledUnwatchedOnly by remember(anchorStream, seasonNumber, episodeNumber) {
        mutableStateOf<Boolean?>(null)
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
    val allEpisodes = currentProgress?.episodes.orEmpty()
    val watchedEpisodesCount = allEpisodes.count { it.isWatched }
    val unwatchedEpisodesCount = allEpisodes.size - watchedEpisodesCount
    val currentEpisodeWatched = allEpisodes.firstOrNull { it.episodeNumber == episodeNumber }?.isWatched == true
    val isUnwatchedOnly = userToggledUnwatchedOnly ?: (watchedEpisodesCount > 0 && !currentEpisodeWatched)
    val displayedEpisodes = if (isUnwatchedOnly) {
        allEpisodes.filter { !it.isWatched }
    } else {
        allEpisodes
    }
    val selectableEpisodes = displayedEpisodes.filter { it.isSelectable }
    val selectableNumbers = selectableEpisodes.map { it.episodeNumber }.toSet()
    val effectiveSelected = selectedEpisodeNumbers.intersect(selectableNumbers)
    val toggleState = when {
        effectiveSelected.isEmpty() -> ToggleableState.Off
        effectiveSelected.size == selectableEpisodes.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

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
                Text(
                    text = stringResource(
                        Res.string.downloads_season_pack_found_summary,
                        matchedCount,
                        currentProgress.totalEpisodes,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textMuted,
                )
                if (watchedEpisodesCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tokens.shapes.compactCard)
                            .background(
                                if (isUnwatchedOnly) {
                                    tokens.colors.accent.copy(alpha = tokens.opacity.subtle)
                                } else {
                                    tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle)
                                },
                            )
                            .toggleable(
                                value = isUnwatchedOnly,
                                enabled = !isSubmitting,
                                role = Role.Checkbox,
                                onValueChange = { userToggledUnwatchedOnly = it },
                            )
                            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = isUnwatchedOnly,
                            onCheckedChange = null,
                            enabled = !isSubmitting,
                            colors = CheckboxDefaults.colors(
                                checkedColor = tokens.colors.accent,
                                uncheckedColor = tokens.colors.textSecondary,
                                checkmarkColor = tokens.colors.onAccent,
                            ),
                        )
                        Text(
                            text = stringResource(
                                Res.string.downloads_season_pack_unwatched_only,
                                unwatchedEpisodesCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (selectableEpisodes.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tokens.shapes.compactCard)
                            .background(
                                if (toggleState != ToggleableState.Off) {
                                    tokens.colors.accent.copy(alpha = tokens.opacity.subtle)
                                } else {
                                    tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle)
                                },
                            )
                            .triStateToggleable(
                                state = toggleState,
                                enabled = !isSubmitting,
                                role = Role.Checkbox,
                                onClick = {
                                    selectedEpisodeNumbers = if (toggleState == ToggleableState.On) {
                                        emptySet()
                                    } else {
                                        selectableNumbers
                                    }
                                },
                            )
                            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TriStateCheckbox(
                            state = toggleState,
                            onClick = null,
                            enabled = !isSubmitting,
                            colors = CheckboxDefaults.colors(
                                checkedColor = tokens.colors.accent,
                                uncheckedColor = tokens.colors.textSecondary,
                                checkmarkColor = tokens.colors.onAccent,
                            ),
                        )
                        Text(
                            text = stringResource(Res.string.downloads_season_pack_select_all),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${effectiveSelected.size}/${selectableEpisodes.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (toggleState != ToggleableState.Off) {
                                tokens.colors.accent
                            } else {
                                tokens.colors.textMuted
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

            if (displayedEpisodes.isEmpty() && isUnwatchedOnly) {
                Text(
                    text = stringResource(Res.string.downloads_season_pack_all_watched),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textMuted,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s80 + NuvioTokens.Space.s40),
                    verticalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
                ) {
                    items(
                        items = displayedEpisodes,
                        key = { "${it.seasonNumber}:${it.episodeNumber}:${it.video.id}" },
                    ) { candidate ->
                        val baseStatusDescription = when {
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
                        val statusDescription = if (candidate.isWatched && !isUnwatchedOnly) {
                            "$baseStatusDescription • ${stringResource(Res.string.downloads_season_pack_status_watched)}"
                        } else {
                            baseStatusDescription
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
