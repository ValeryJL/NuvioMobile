package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.ProfileScopedKey
import platform.Foundation.NSUserDefaults

internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"
    private const val wifiOnlyKey = "downloads_wifi_only"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = ProfileScopedKey.of(payloadKey))
    }

    actual fun loadWifiOnly(): Boolean {
        val defaults = NSUserDefaults.standardUserDefaults
        return if (defaults.objectForKey(wifiOnlyKey) == null) true else defaults.boolForKey(wifiOnlyKey)
    }

    actual fun saveWifiOnly(enabled: Boolean) {
        NSUserDefaults.standardUserDefaults.setBool(enabled, forKey = wifiOnlyKey)
    }
}
