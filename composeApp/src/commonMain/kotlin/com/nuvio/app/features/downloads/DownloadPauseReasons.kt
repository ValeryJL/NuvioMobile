package com.nuvio.app.features.downloads

/**
 * Structured pause diagnostic codes stored on [DownloadItem.pauseReason].
 * Values optionally carry a technical detail after [DETAIL_SEPARATOR] (for example,
 * `"missing_job|TIMEOUT:STOP_REASON_TIMEOUT"`), allowing the UI and system notifications to
 * localize the reason while preserving exact Android stop codes for debugging.
 */
internal object DownloadPauseReasons {
    const val DETAIL_SEPARATOR = "|"
    const val STOP_TOKEN_SEPARATOR = ":"

    const val USER_APP = "user_app"
    const val USER_NOTIFICATION = "user_notification"
    const val SYSTEM_USER_STOP = "system_user_stop"
    const val MISSING_JOB = "missing_job"
    const val MISSING_TRANSFER_STATE = "missing_transfer_state"
    const val WORKER_STOPPED = "worker_stopped"
    const val FG_SERVICE_BLOCKED = "fg_service_blocked"
    const val TRANSFER_CANCELLED = "transfer_cancelled"
    const val WIFI_ONLY = "wifi_only"
    const val ACCOUNT_CLEARED = "account_cleared"
    const val IOS_BACKGROUND = "ios_background"

    const val STOP_CATEGORY_TIMEOUT = "TIMEOUT"
    const val STOP_CATEGORY_QUOTA = "QUOTA"
    const val STOP_CATEGORY_BACKGROUND_RESTRICTION = "BACKGROUND_RESTRICTION"
    const val STOP_CATEGORY_APP_STANDBY = "APP_STANDBY"
    const val STOP_CATEGORY_CONNECTIVITY = "CONNECTIVITY"
    const val STOP_CATEGORY_DEVICE_STATE = "DEVICE_STATE"
    const val STOP_CATEGORY_PREEMPT = "PREEMPT"

    data class ParsedPauseReason(
        val code: String,
        val detail: String?,
    )

    fun withDetail(code: String, detail: String?): String {
        val clean = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return code
        return "$code$DETAIL_SEPARATOR$clean"
    }

    fun encodeStopDetail(category: String?, rawCode: String): String =
        if (category.isNullOrBlank()) rawCode else "$category$STOP_TOKEN_SEPARATOR$rawCode"

    fun parse(rawReason: String?): ParsedPauseReason {
        val clean = rawReason?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ParsedPauseReason(code = "", detail = null)
        val sepIndex = clean.indexOf(DETAIL_SEPARATOR)
        if (sepIndex < 0) return ParsedPauseReason(code = clean, detail = null)
        return ParsedPauseReason(
            code = clean.substring(0, sepIndex).trim(),
            detail = clean.substring(sepIndex + 1).trim().takeIf { it.isNotEmpty() },
        )
    }

    fun parseStopDetail(detail: String): Pair<String?, String> {
        val idx = detail.indexOf(STOP_TOKEN_SEPARATOR)
        if (idx <= 0) return null to detail
        return detail.substring(0, idx) to detail.substring(idx + 1)
    }
}
