package com.nuvio.app.features.downloads

internal expect object DownloadsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
    fun loadWifiOnly(): Boolean
    fun saveWifiOnly(enabled: Boolean)
}
