package com.nuvio.app.features.downloads

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsSettingsRepositoryTest {
    @AfterTest
    fun tearDown() {
        DownloadsSettingsRepository.resetForTesting()
    }

    @Test
    fun defaultsToWifiOnlyAndPersistsToggleAcrossReloads() {
        val store = InMemoryDownloadsSettingsStore()
        val policyNotifications = mutableListOf<Boolean>()
        DownloadsSettingsRepository.resetForTesting(
            testStore = store,
            onPolicyChanged = { policyNotifications += it },
        )

        DownloadsSettingsRepository.ensureLoaded()
        assertTrue(DownloadsSettingsRepository.wifiOnly.value)
        assertTrue(store.savedValue)
        assertTrue(policyNotifications.isEmpty())

        DownloadsSettingsRepository.setWifiOnly(false)
        assertFalse(DownloadsSettingsRepository.wifiOnly.value)
        assertFalse(store.savedValue)
        assertEquals(listOf(false), policyNotifications)

        // Simulating an app restart reads the saved device-wide preference back from store.
        DownloadsSettingsRepository.resetForTesting(
            testStore = store,
            onPolicyChanged = { policyNotifications += it },
        )
        DownloadsSettingsRepository.ensureLoaded()
        assertFalse(DownloadsSettingsRepository.wifiOnly.value)

        DownloadsSettingsRepository.setWifiOnly(true)
        assertTrue(DownloadsSettingsRepository.wifiOnly.value)
        assertTrue(store.savedValue)
        assertEquals(listOf(false, true), policyNotifications)
    }

    private class InMemoryDownloadsSettingsStore : DownloadsSettingsStore {
        var savedValue: Boolean = true

        override fun loadWifiOnly(): Boolean = savedValue

        override fun saveWifiOnly(enabled: Boolean) {
            savedValue = enabled
        }
    }
}
