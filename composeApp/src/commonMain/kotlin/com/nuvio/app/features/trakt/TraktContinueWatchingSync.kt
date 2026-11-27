package com.nuvio.app.features.trakt

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object TraktContinueWatchingSync {
    private val syncMutex = Mutex()
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    suspend fun forceSync(
        profileId: Int = ProfileRepository.activeProfileId,
    ): Boolean = syncMutex.withLock {
        TraktAuthRepository.ensureLoaded(profileId)
        if (!TraktAuthRepository.isAuthenticated.value) return@withLock false

        _isSyncing.value = true
        try {
            WatchProgressSourceCoordinator.refreshProviderAndActiveSource(
                profileId = profileId,
                providerId = TrackingProviderId.TRAKT,
                refreshProvider = {
                    TraktProgressRepository.invalidateAndRefresh()
                    val snapshot = TraktProgressRepository.uiState.value
                    snapshot.errorMessage == null && snapshot.hasLoadedRemoteProgress
                },
            )
        } finally {
            _isSyncing.value = false
        }
    }
}
