package com.nuvio.app.features.downloads

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface DownloadsSettingsStore {
    fun loadWifiOnly(): Boolean
    fun saveWifiOnly(enabled: Boolean)
}

internal object DownloadsSettingsRepository {
    private var store: DownloadsSettingsStore = StorageBackedDownloadsSettingsStore
    private var onWifiOnlyPolicyChanged: (Boolean) -> Unit = DownloadsPlatformDownloader::onWifiOnlyChanged

    private val _wifiOnly = MutableStateFlow(true)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _wifiOnly.value = store.loadWifiOnly()
    }

    fun setWifiOnly(enabled: Boolean) {
        hasLoaded = true
        store.saveWifiOnly(enabled)
        _wifiOnly.value = enabled
        onWifiOnlyPolicyChanged(enabled)
    }

    internal fun resetForTesting(
        testStore: DownloadsSettingsStore = StorageBackedDownloadsSettingsStore,
        onPolicyChanged: (Boolean) -> Unit = DownloadsPlatformDownloader::onWifiOnlyChanged,
    ) {
        store = testStore
        onWifiOnlyPolicyChanged = onPolicyChanged
        hasLoaded = false
        _wifiOnly.value = testStore.loadWifiOnly()
    }
}

private object StorageBackedDownloadsSettingsStore : DownloadsSettingsStore {
    override fun loadWifiOnly(): Boolean = DownloadsStorage.loadWifiOnly()

    override fun saveWifiOnly(enabled: Boolean) {
        DownloadsStorage.saveWifiOnly(enabled)
    }
}
