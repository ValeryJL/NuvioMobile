package com.nuvio.app.features.downloads

import android.app.Notification
import android.content.Intent
import com.nuvio.app.core.build.AppFeaturePolicy
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35])
class DownloadsForegroundServiceTest {
    @Before
    fun setUp() {
        assumeTrue(AppFeaturePolicy.downloadForegroundServiceEnabled)
        val app = RuntimeEnvironment.getApplication()
        DownloadsStorage.initialize(app)
        DownloadsPlatformDownloader.initialize(app)
        DownloadsLiveStatusPlatform.initialize(app)
        DownloadsPlatformDownloader.scheduler(app).preferForegroundService = true
    }

    @After
    fun tearDown() {
        if (!AppFeaturePolicy.downloadForegroundServiceEnabled) return
        val app = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(app)
        scheduler.store.transfers.value.keys.toList().forEach(scheduler::remove)
        scheduler.preferForegroundService = AppFeaturePolicy.downloadForegroundServiceEnabled
    }

    @Test
    fun enqueueOnFullBuildStartsDownloadsForegroundServiceWithoutDuplicateSystemJob() {
        val app = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(app)
        val item = downloadItem().copy(id = "fgs-1", fileName = "fgs-1.mkv", title = "Movie FGS")

        val transfer = scheduler.enqueue(item)

        assertNull(scheduler.jobScheduler().getPendingJob(transfer.jobId))
        val startedIntent = shadowOf(app).nextStartedService
        assertNotNull(startedIntent)
        assertEquals(DownloadsForegroundService::class.java.name, startedIntent.component?.className)
        assertEquals(DownloadsForegroundService.ACTION_SYNC_DOWNLOADS, startedIntent.action)
    }

    @Test
    fun foregroundNotificationBuildsSingleAndCombinedContentAndHandlesTimeoutFallback() {
        val app = RuntimeEnvironment.getApplication()
        val scheduler = DownloadsPlatformDownloader.scheduler(app)
        val first = scheduler.store.begin(downloadItem().copy(id = "fgs-a", fileName = "a.mkv", title = "Show A"))
        val second = scheduler.store.begin(downloadItem().copy(id = "fgs-b", fileName = "b.mkv", title = "Show B"))

        val singleNotification = DownloadsLiveStatusPlatform.buildForegroundNotification(app, listOf(first.item))
        assertEquals("Show A", singleNotification.extras.getString(Notification.EXTRA_TITLE))

        val multiNotification = DownloadsLiveStatusPlatform.buildForegroundNotification(app, listOf(first.item, second.item))
        assertTrue(multiNotification.extras.getString(Notification.EXTRA_TITLE).orEmpty().contains("2"))

        val controller = Robolectric.buildService(DownloadsForegroundService::class.java).create()
        val service = controller.get()
        try {
            service.onTimeout(1, 1)
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertNotNull(scheduler.jobScheduler().getPendingJob(first.jobId))
            assertNotNull(scheduler.jobScheduler().getPendingJob(second.jobId))
        } finally {
            controller.destroy()
        }
    }
}
