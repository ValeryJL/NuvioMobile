package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_pause_reason_account_cleared
import nuvio.composeapp.generated.resources.downloads_pause_reason_fg_service_blocked
import nuvio.composeapp.generated.resources.downloads_pause_reason_fg_service_blocked_detail
import nuvio.composeapp.generated.resources.downloads_pause_reason_ios_background
import nuvio.composeapp.generated.resources.downloads_pause_reason_missing_job
import nuvio.composeapp.generated.resources.downloads_pause_reason_missing_job_detail
import nuvio.composeapp.generated.resources.downloads_pause_reason_missing_transfer_state
import nuvio.composeapp.generated.resources.downloads_pause_reason_prefix
import nuvio.composeapp.generated.resources.downloads_pause_reason_system_user_stop
import nuvio.composeapp.generated.resources.downloads_pause_reason_transfer_cancelled
import nuvio.composeapp.generated.resources.downloads_pause_reason_unknown
import nuvio.composeapp.generated.resources.downloads_pause_reason_user_app
import nuvio.composeapp.generated.resources.downloads_pause_reason_user_notification
import nuvio.composeapp.generated.resources.downloads_pause_reason_worker_stopped
import nuvio.composeapp.generated.resources.downloads_pause_reason_worker_stopped_detail
import nuvio.composeapp.generated.resources.downloads_stop_detail_app_standby
import nuvio.composeapp.generated.resources.downloads_stop_detail_background_restriction
import nuvio.composeapp.generated.resources.downloads_stop_detail_connectivity
import nuvio.composeapp.generated.resources.downloads_stop_detail_device_state
import nuvio.composeapp.generated.resources.downloads_stop_detail_preempt
import nuvio.composeapp.generated.resources.downloads_stop_detail_quota
import nuvio.composeapp.generated.resources.downloads_stop_detail_timeout
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private data class DiagnosticResourceSpec(
    val resource: StringResource,
    val detailArg: String? = null,
)

private fun stopCategoryResource(category: String?): StringResource? = when (category) {
    DownloadPauseReasons.STOP_CATEGORY_TIMEOUT -> Res.string.downloads_stop_detail_timeout
    DownloadPauseReasons.STOP_CATEGORY_QUOTA -> Res.string.downloads_stop_detail_quota
    DownloadPauseReasons.STOP_CATEGORY_BACKGROUND_RESTRICTION -> Res.string.downloads_stop_detail_background_restriction
    DownloadPauseReasons.STOP_CATEGORY_APP_STANDBY -> Res.string.downloads_stop_detail_app_standby
    DownloadPauseReasons.STOP_CATEGORY_CONNECTIVITY -> Res.string.downloads_stop_detail_connectivity
    DownloadPauseReasons.STOP_CATEGORY_DEVICE_STATE -> Res.string.downloads_stop_detail_device_state
    DownloadPauseReasons.STOP_CATEGORY_PREEMPT -> Res.string.downloads_stop_detail_preempt
    else -> null
}

private fun diagnosticSpecForCode(code: String, resolvedDetail: String?): DiagnosticResourceSpec = when (code) {
    DownloadPauseReasons.USER_APP -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_user_app)
    DownloadPauseReasons.USER_NOTIFICATION -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_user_notification)
    DownloadPauseReasons.SYSTEM_USER_STOP -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_system_user_stop)
    DownloadPauseReasons.MISSING_JOB -> if (resolvedDetail != null) {
        DiagnosticResourceSpec(Res.string.downloads_pause_reason_missing_job_detail, resolvedDetail)
    } else {
        DiagnosticResourceSpec(Res.string.downloads_pause_reason_missing_job)
    }
    DownloadPauseReasons.WORKER_STOPPED -> if (resolvedDetail != null) {
        DiagnosticResourceSpec(Res.string.downloads_pause_reason_worker_stopped_detail, resolvedDetail)
    } else {
        DiagnosticResourceSpec(Res.string.downloads_pause_reason_worker_stopped)
    }
    DownloadPauseReasons.FG_SERVICE_BLOCKED -> if (resolvedDetail != null) {
        DiagnosticResourceSpec(Res.string.downloads_pause_reason_fg_service_blocked_detail, resolvedDetail)
    } else {
        DiagnosticResourceSpec(Res.string.downloads_pause_reason_fg_service_blocked)
    }
    DownloadPauseReasons.MISSING_TRANSFER_STATE -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_missing_transfer_state)
    DownloadPauseReasons.TRANSFER_CANCELLED -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_transfer_cancelled)
    DownloadPauseReasons.ACCOUNT_CLEARED -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_account_cleared)
    DownloadPauseReasons.IOS_BACKGROUND -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_ios_background)
    else -> DiagnosticResourceSpec(Res.string.downloads_pause_reason_unknown)
}

@Composable
internal fun formatPauseDiagnostic(reason: String?, includePrefix: Boolean = true): String {
    val parsed = DownloadPauseReasons.parse(reason)
    val resolvedDetail = parsed.detail?.let { rawDetail ->
        val (category, rawCode) = DownloadPauseReasons.parseStopDetail(rawDetail)
        val categoryRes = stopCategoryResource(category)
        if (categoryRes != null) stringResource(categoryRes, rawCode) else rawDetail
    }
    val spec = diagnosticSpecForCode(parsed.code, resolvedDetail)
    val message = if (spec.detailArg != null) {
        stringResource(spec.resource, spec.detailArg)
    } else {
        stringResource(spec.resource)
    }
    return if (includePrefix) {
        stringResource(Res.string.downloads_pause_reason_prefix, message)
    } else {
        message
    }
}

internal suspend fun resolvePauseDiagnostic(reason: String?, includePrefix: Boolean = false): String {
    val parsed = DownloadPauseReasons.parse(reason)
    val resolvedDetail = parsed.detail?.let { rawDetail ->
        val (category, rawCode) = DownloadPauseReasons.parseStopDetail(rawDetail)
        val categoryRes = stopCategoryResource(category)
        if (categoryRes != null) getString(categoryRes, rawCode) else rawDetail
    }
    val spec = diagnosticSpecForCode(parsed.code, resolvedDetail)
    val message = if (spec.detailArg != null) {
        getString(spec.resource, spec.detailArg)
    } else {
        getString(spec.resource)
    }
    return if (includePrefix) {
        getString(Res.string.downloads_pause_reason_prefix, message)
    } else {
        message
    }
}
