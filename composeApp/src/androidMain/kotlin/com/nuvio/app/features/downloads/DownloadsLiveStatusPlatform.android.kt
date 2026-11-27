package com.nuvio.app.features.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nuvio.app.core.deeplink.buildDownloadsDeepLinkUrl
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

internal actual object DownloadsLiveStatusPlatform {
    private const val channelId = "downloads_live_status"
    private const val notificationsPrefName = "nuvio_download_live_notifications"
    private const val trackedDownloadIdsKey = "tracked_download_ids"

    private var appContext: Context? = null
    private val lastRenderStateById = mutableMapOf<String, RenderState>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureNotificationChannel()
    }

    actual fun onItemsChanged(items: List<DownloadItem>) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return

        val manager = NotificationManagerCompat.from(context)
        val trackedBefore = preferences(context)
            .getStringSet(trackedDownloadIdsKey, emptySet())
            .orEmpty()
            .toMutableSet()

        val activeItems = items.filter { item ->
            item.status == DownloadStatus.Downloading ||
                item.status == DownloadStatus.Paused ||
                item.status == DownloadStatus.Failed
        }
        val managedIds = DownloadsPlatformDownloader.managedTransfers().map { it.item.id }.toSet()

        val trackedNow = managedIds.toMutableSet()
        activeItems.filterNot { it.id in managedIds }.forEach { item ->
            val renderState = RenderState(
                status = item.status,
                progressPercent = progressPercent(item),
                downloadedBucket = item.downloadedBytes / (512L * 1024L),
                totalBytes = item.totalBytes,
                errorMessage = item.errorMessage,
            )

            val existingState = lastRenderStateById[item.id]
            if (existingState == renderState) {
                trackedNow += item.id
                return@forEach
            }

            manager.notify(notificationId(item.id), buildNotification(context, item))
            lastRenderStateById[item.id] = renderState
            trackedNow += item.id
        }

        val staleIds = trackedBefore - trackedNow
        staleIds.forEach { downloadId ->
            manager.cancel(notificationId(downloadId))
            lastRenderStateById.remove(downloadId)
        }

        preferences(context)
            .edit()
            .putStringSet(trackedDownloadIdsKey, trackedNow)
            .apply()
    }

    internal fun notifyTransfer(item: DownloadItem) {
        val context = appContext ?: return
        if (!canPostNotifications(context)) return
        val manager = NotificationManagerCompat.from(context)
        if (item.status == DownloadStatus.Completed || item.status == DownloadStatus.Queued) {
            removeNotification(item.id)
        } else if (item.status == DownloadStatus.Downloading && DownloadsForegroundService.isHosting(item.id)) {
            manager.cancel(notificationId(item.id))
            manager.cancel(notificationId("inactive:${item.id}"))
            DownloadsForegroundService.updateNotificationIfRunning(context)
        } else {
            val active = item.status == DownloadStatus.Downloading
            manager.cancel(notificationId(if (active) "inactive:${item.id}" else item.id))
            manager.notify(notificationId(if (active) item.id else "inactive:${item.id}"), buildNotification(context, item))
        }
    }

    internal fun buildIdleForegroundNotification(context: Context): android.app.Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(runBlocking { getString(Res.string.downloads_channel_name) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    internal fun buildForegroundNotification(
        context: Context,
        activeItems: List<DownloadItem>,
    ): android.app.Notification {
        ensureNotificationChannel()
        if (activeItems.size == 1) {
            return buildNotification(context, activeItems.first())
        }
        val first = activeItems.firstOrNull() ?: return buildIdleForegroundNotification(context)
        val totalDownloaded = activeItems.sumOf { it.downloadedBytes.coerceAtLeast(0L) }
        val knownTotals = activeItems.mapNotNull { it.totalBytes?.takeIf { size -> size > 0L } }
        val hasCompleteTotal = knownTotals.size == activeItems.size && knownTotals.isNotEmpty()
        val combinedTotal = if (hasCompleteTotal) knownTotals.sum() else null
        val downloadedText = formatBytes(totalDownloaded)
        val subtitle = if (combinedTotal != null) {
            runBlocking {
                getString(
                    Res.string.downloads_live_downloading_with_total,
                    activeItems.size.toString(),
                    downloadedText,
                    formatBytes(combinedTotal),
                )
            }
        } else {
            runBlocking { getString(Res.string.downloads_live_multiple_summary, downloadedText) }
        }
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(buildDownloadsDeepLinkUrl())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            context,
            DOWNLOADS_FOREGROUND_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(runBlocking { getString(Res.string.downloads_live_multiple_title, activeItems.size) })
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(
                0,
                runBlocking { getString(Res.string.compose_action_pause) },
                buildActionPendingIntent(
                    context = context,
                    action = DownloadsNotificationActionReceiver.actionPause,
                    downloadId = first.id,
                    fileName = first.fileName,
                ),
            )
        if (combinedTotal != null && combinedTotal > 0L) {
            val pct = ((totalDownloaded.toDouble() / combinedTotal.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            builder.setProgress(100, pct, false)
        } else {
            builder.setProgress(100, 0, true)
        }
        return builder.build()
    }

    internal fun removeNotification(downloadId: String) {
        val context = appContext ?: return
        NotificationManagerCompat.from(context).apply {
            cancel(notificationId(downloadId))
            cancel(notificationId("inactive:$downloadId"))
        }
    }

    internal fun buildNotification(context: Context, item: DownloadItem): android.app.Notification {
        val subtitle = buildSubtitle(item)
        val launchIntent = Intent().apply {
            component = AppIconPlatform.currentLauncherComponent(context)
            action = Intent.ACTION_VIEW
            data = android.net.Uri.parse(buildDownloadsDeepLinkUrl())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val launchPendingIntent = PendingIntent.getActivity(
            context,
            notificationId(item.id),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
            .setContentTitle(item.title)
            .setContentText(subtitle)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setOnlyAlertOnce(true)
            .setContentIntent(launchPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        when (item.status) {
            DownloadStatus.Downloading -> {
                notificationBuilder
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.compose_action_pause) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionPause,
                            downloadId = item.id,
                            fileName = item.fileName,
                        ),
                    )

                val progress = progressPercent(item)
                if (progress >= 0) {
                    notificationBuilder.setProgress(100, progress, false)
                } else {
                    notificationBuilder.setProgress(100, 0, true)
                }
            }

            DownloadStatus.Queued,
            DownloadStatus.Paused,
            DownloadStatus.Failed,
            DownloadStatus.Completed,
            -> {
                notificationBuilder
                    .setOngoing(false)
                    .setAutoCancel(false)
                    .setPriority(
                        if (item.status == DownloadStatus.Failed) {
                            NotificationCompat.PRIORITY_DEFAULT
                        } else {
                            NotificationCompat.PRIORITY_LOW
                        },
                    )
                    .setProgress(0, 0, false)
                    .addAction(
                        0,
                        runBlocking { getString(Res.string.action_resume) },
                        buildActionPendingIntent(
                            context = context,
                            action = DownloadsNotificationActionReceiver.actionResume,
                            downloadId = item.id,
                            fileName = item.fileName,
                        ),
                    )
            }
        }

        return notificationBuilder.build()
    }

    private fun buildSubtitle(item: DownloadItem): String {
        val detail = item.displaySubtitle
        return when (item.status) {
            DownloadStatus.Downloading -> {
                val downloaded = formatBytes(item.downloadedBytes)
                val total = item.totalBytes?.let(::formatBytes)
                if (total != null) {
                    runBlocking { getString(Res.string.downloads_live_downloading_with_total, detail, downloaded, total) }
                } else {
                    runBlocking { getString(Res.string.downloads_live_downloading, detail, downloaded) }
                }
            }

            DownloadStatus.Queued -> runBlocking { getString(Res.string.downloads_live_queued, detail) }
            DownloadStatus.Paused -> runBlocking {
                val base = getString(Res.string.downloads_live_paused, detail)
                val diagnostic = item.pauseReason?.let { resolvePauseDiagnostic(it, includePrefix = false) }
                if (diagnostic != null) "$base • $diagnostic" else base
            }
            DownloadStatus.Failed -> item.errorMessage?.takeIf { it.isNotBlank() } ?: runBlocking { getString(Res.string.downloads_live_failed) }
            DownloadStatus.Completed -> runBlocking { getString(Res.string.downloads_live_completed) }
        }
    }

    private fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L).toDouble()
        val units = runBlocking {
            arrayOf(
                getString(Res.string.unit_bytes_b),
                getString(Res.string.unit_bytes_kb),
                getString(Res.string.unit_bytes_mb),
                getString(Res.string.unit_bytes_gb),
                getString(Res.string.unit_bytes_tb),
            )
        }
        var value = safe
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return if (unitIndex == 0) {
            "${value.toLong()} ${units[unitIndex]}"
        } else {
            "${"%.1f".format(value)} ${units[unitIndex]}"
        }
    }

    private fun progressPercent(item: DownloadItem): Int {
        val total = item.totalBytes?.takeIf { it > 0L } ?: return -1
        return ((item.downloadedBytes.toDouble() / total.toDouble()) * 100.0)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun buildActionPendingIntent(
        context: Context,
        action: String,
        downloadId: String,
        fileName: String,
    ): PendingIntent {
        val intent = Intent(context, DownloadsNotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadsNotificationActionReceiver.extraDownloadId, downloadId)
            putExtra(AndroidDownloadScheduler.FILE_NAME, fileName)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId("$action:$downloadId"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureNotificationChannel() {
        val context = appContext ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                runBlocking { getString(Res.string.downloads_channel_name) },
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = runBlocking { getString(Res.string.downloads_channel_description) }
            },
        )
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionState = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(notificationsPrefName, Context.MODE_PRIVATE)

    internal fun notificationId(downloadId: String): Int = (downloadId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    private data class RenderState(
        val status: DownloadStatus,
        val progressPercent: Int,
        val downloadedBucket: Long,
        val totalBytes: Long?,
        val errorMessage: String?,
    )
}
