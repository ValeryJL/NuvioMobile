package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val KEY_HAD_ACCOUNT_SESSION = "had_account_session"
internal const val KEY_SIGNED_OUT_NOTICE = "signed_out_notice"
internal const val KEY_LAST_ACCOUNT_USER_ID = "last_account_user_id"

internal const val TRANSIENT_NOT_AUTHENTICATED_GRACE_MS = 2_500L
internal const val STORAGE_RELOAD_SETTLE_MS = 1_000L

internal sealed interface AuthGatewayStatus {
    data object Initializing : AuthGatewayStatus
    data class Authenticated(
        val userId: String,
        val email: String? = null,
    ) : AuthGatewayStatus

    data class NotAuthenticated(
        val isSignOut: Boolean = false,
    ) : AuthGatewayStatus

    data object RefreshFailure : AuthGatewayStatus
}

internal interface AuthKeyValueStore {
    fun loadAnonymousUserId(): String?
    fun saveAnonymousUserId(userId: String)
    fun clearAnonymousUserId()
    fun loadFlag(key: String): Boolean
    fun saveFlag(key: String, value: Boolean)
    fun loadString(key: String): String?
    fun saveString(key: String, value: String)
    fun remove(key: String)
}

internal interface AuthSessionGateway {
    val statusFlow: Flow<AuthGatewayStatus>
    fun currentStatus(): AuthGatewayStatus
    fun hasCurrentSession(): Boolean
    suspend fun loadFromStorage(): Boolean
    suspend fun retrieveUserForCurrentSession()
    suspend fun refreshCurrentSession()
    suspend fun signOut()
    suspend fun clearSession()
}

private enum class RefreshAttemptResult {
    SUCCEEDED,
    TRANSIENT_FAILURE,
    INVALID,
}

internal class AuthSessionController(
    private val scope: CoroutineScope,
    private val store: AuthKeyValueStore,
    private val gatewayProvider: () -> AuthSessionGateway,
    private val wipeLocalData: () -> Unit,
    private val clearInMemoryProfiles: () -> Unit,
    private val signedOutNotice: suspend () -> String?,
    private val signOutFailedFallback: suspend () -> String,
    private val transientGraceMs: Long = TRANSIENT_NOT_AUTHENTICATED_GRACE_MS,
    private val reloadSettleMs: Long = STORAGE_RELOAD_SETTLE_MS,
) {
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var sessionStatusJob: Job? = null
    private var pendingLostSessionJob: Job? = null
    private var validatedRemoteUserId: String? = null
    private val refreshMutex = Mutex()

    fun initialize() {
        if (initialized) return
        initialized = true

        val savedAnonId = store.loadAnonymousUserId()
        if (savedAnonId != null) {
            _state.value = AuthState.Authenticated(
                userId = savedAnonId,
                email = null,
                isAnonymous = true,
            )
        }

        val gateway = gatewayProvider()
        sessionStatusJob = scope.launch {
            if (store.loadFlag(KEY_SIGNED_OUT_NOTICE)) {
                _error.value = signedOutNotice()
            }
            gateway.statusFlow.collect { status ->
                if (store.loadAnonymousUserId() != null) return@collect
                handleGatewayStatus(gateway, status)
            }
        }
    }

    internal suspend fun handleGatewayStatus(
        gateway: AuthSessionGateway,
        status: AuthGatewayStatus,
    ) {
        when (status) {
            is AuthGatewayStatus.Authenticated -> {
                cancelPendingLostSessionReset()
                if (!validateRemoteSession(gateway, status.userId)) return

                val previousUserId = store.loadString(KEY_LAST_ACCOUNT_USER_ID)
                if (
                    status.userId.isNotBlank() &&
                    !previousUserId.isNullOrBlank() &&
                    previousUserId != status.userId
                ) {
                    runCatching { wipeLocalData() }
                        .onFailure { error ->
                            log.e(error) { "Local account cleanup failed when switching accounts" }
                        }
                }

                store.saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
                store.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
                if (status.userId.isNotBlank()) {
                    store.saveString(KEY_LAST_ACCOUNT_USER_ID, status.userId)
                }
                _error.value = null
                _state.value = AuthState.Authenticated(
                    userId = status.userId,
                    email = status.email,
                    isAnonymous = false,
                )
            }

            is AuthGatewayStatus.NotAuthenticated -> {
                if (!store.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                    cancelPendingLostSessionReset()
                    _state.value = AuthState.Unauthenticated
                    return
                }

                if (status.isSignOut) {
                    cancelPendingLostSessionReset()
                    resetAfterLostSession()
                } else {
                    scheduleLostSessionVerification(gateway)
                }
            }

            is AuthGatewayStatus.Initializing -> {
                if (store.loadAnonymousUserId() == null && _state.value !is AuthState.Authenticated) {
                    _state.value = AuthState.Loading
                }
            }

            is AuthGatewayStatus.RefreshFailure -> {
                cancelPendingLostSessionReset()
            }
        }
    }

    private fun scheduleLostSessionVerification(gateway: AuthSessionGateway) {
        if (pendingLostSessionJob?.isActive == true) return
        pendingLostSessionJob = scope.launch {
            // supabase-kt 3.4.1 can transiently emit NotAuthenticated(isSignOut = false) on
            // startup/foreground transitions while storage loading or token refresh is in flight.
            if (transientGraceMs > 0L) {
                delay(transientGraceMs)
            }
            if (!isActive || store.loadAnonymousUserId() != null || !store.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                return@launch
            }
            if (hasRecoveredOrTransientSession(gateway)) {
                return@launch
            }

            val reloaded = runCatching { gateway.loadFromStorage() }.getOrDefault(false)
            if (!isActive || store.loadAnonymousUserId() != null || !store.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                return@launch
            }
            if (reloaded || gateway.currentStatus() is AuthGatewayStatus.Initializing) {
                if (reloadSettleMs > 0L) {
                    delay(reloadSettleMs)
                }
                if (!isActive || store.loadAnonymousUserId() != null || !store.loadFlag(KEY_HAD_ACCOUNT_SESSION)) {
                    return@launch
                }
                if (hasRecoveredOrTransientSession(gateway)) {
                    return@launch
                }
            }

            resetAfterLostSession()
        }
    }

    private fun hasRecoveredOrTransientSession(gateway: AuthSessionGateway): Boolean {
        val currentStatus = gateway.currentStatus()
        return currentStatus is AuthGatewayStatus.Authenticated ||
            currentStatus is AuthGatewayStatus.RefreshFailure ||
            gateway.hasCurrentSession()
    }

    private fun cancelPendingLostSessionReset() {
        pendingLostSessionJob?.cancel()
        pendingLostSessionJob = null
    }

    private suspend fun validateRemoteSession(gateway: AuthSessionGateway, userId: String): Boolean {
        if (userId.isBlank() || validatedRemoteUserId == userId) return true

        return try {
            gateway.retrieveUserForCurrentSession()
            validatedRemoteUserId = userId
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            when {
                e.isDeletedAccountError() -> {
                    log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                    clearLocalSessionAfterRemoteInvalidation(gateway)
                    false
                }

                e.isJwtExpiredError() || e.isInvalidSessionError() -> {
                    when (attemptRefresh(gateway)) {
                        RefreshAttemptResult.SUCCEEDED -> {
                            validatedRemoteUserId = userId
                            true
                        }

                        RefreshAttemptResult.TRANSIENT_FAILURE -> {
                            log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                            true
                        }

                        RefreshAttemptResult.INVALID -> {
                            log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                            clearLocalSessionAfterRemoteInvalidation(gateway)
                            false
                        }
                    }
                }

                else -> {
                    log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                    true
                }
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        cancelPendingLostSessionReset()
        _error.value = null
        val previousUserId = store.loadString(KEY_LAST_ACCOUNT_USER_ID)
        if (!previousUserId.isNullOrBlank()) {
            runCatching { wipeLocalData() }
                .onFailure { error ->
                    log.e(error) { "Local account cleanup failed before anonymous sign-in" }
                }
        }
        store.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        store.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        store.remove(KEY_LAST_ACCOUNT_USER_ID)
        validatedRemoteUserId = null
        val userId = Uuid.random().toString()
        store.saveAnonymousUserId(userId)
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = null,
            isAnonymous = true,
        )
    }

    suspend fun signOut(): Result<Unit> {
        cancelPendingLostSessionReset()
        _error.value = null
        val anonymousRead = runCatching { store.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { store.clearAnonymousUserId() }
        store.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        store.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        store.remove(KEY_LAST_ACCOUNT_USER_ID)
        validatedRemoteUserId = null

        val gateway = gatewayProvider()
        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { gateway.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { gateway.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { wipeLocalData() }
        _state.value = AuthState.Unauthenticated

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: signOutFailedFallback()
            Result.failure(failure)
        }
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        cancelPendingLostSessionReset()
        _error.value = null
        val anonymousClear = runCatching { store.clearAnonymousUserId() }
        store.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        store.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        validatedRemoteUserId = null
        val sessionClear = runCatching { gatewayProvider().clearSession() }
        _state.value = AuthState.Unauthenticated
        val failure = anonymousClear.exceptionOrNull() ?: sessionClear.exceptionOrNull()
        val cancellation = sessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) Result.success(Unit) else Result.failure(failure)
    }

    fun reinitialize() {
        cancelPendingLostSessionReset()
        sessionStatusJob?.cancel()
        sessionStatusJob = null
        initialized = false
        validatedRemoteUserId = null
        _state.value = AuthState.Loading
        initialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        val gateway = gatewayProvider()
        val invalid = when {
            error.isDeletedAccountError() -> true
            error.isJwtExpiredError() -> attemptRefresh(gateway) == RefreshAttemptResult.INVALID
            else -> false
        }
        if (!invalid) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation(gateway)
        return true
    }

    suspend fun onAccountDeleted() {
        cancelPendingLostSessionReset()
        store.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        store.saveFlag(KEY_SIGNED_OUT_NOTICE, false)
        store.remove(KEY_LAST_ACCOUNT_USER_ID)
        gatewayProvider().signOut()
        validatedRemoteUserId = null
        try {
            wipeLocalData()
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }

    private suspend fun attemptRefresh(gateway: AuthSessionGateway): RefreshAttemptResult =
        refreshMutex.withLock {
            try {
                gateway.refreshCurrentSession()
                RefreshAttemptResult.SUCCEEDED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.w(e) { "Supabase session refresh failed" }
                if (e.isInvalidRefreshError()) {
                    RefreshAttemptResult.INVALID
                } else {
                    RefreshAttemptResult.TRANSIENT_FAILURE
                }
            }
        }

    private suspend fun clearLocalSessionAfterRemoteInvalidation(gateway: AuthSessionGateway) {
        cancelPendingLostSessionReset()
        store.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        runCatching {
            gateway.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        resetAfterLostSession()
    }

    private suspend fun resetAfterLostSession() {
        if (!store.loadAnonymousUserId().isNullOrBlank()) return
        val notice = runCatching { signedOutNotice() }.getOrNull()
        store.clearAnonymousUserId()
        validatedRemoteUserId = null
        runCatching { clearInMemoryProfiles() }
            .onFailure { error ->
                log.e(error) { "In-memory profile cleanup failed after losing the session" }
            }
        store.saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
        store.saveFlag(KEY_SIGNED_OUT_NOTICE, true)
        _error.value = notice
        _state.value = AuthState.Unauthenticated
    }

    fun setError(message: String?) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }
}
