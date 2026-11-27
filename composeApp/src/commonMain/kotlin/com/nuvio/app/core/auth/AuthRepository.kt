package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.auth_account_deletion_failed
import nuvio.composeapp.generated.resources.auth_notice_signed_out
import nuvio.composeapp.generated.resources.auth_sign_in_failed
import nuvio.composeapp.generated.resources.auth_sign_out_failed
import nuvio.composeapp.generated.resources.auth_sign_up_failed
import org.jetbrains.compose.resources.getString

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val controller = AuthSessionController(
        scope = scope,
        store = StorageBackedAuthStore,
        gatewayProvider = ::SupabaseSessionGatewayAdapter,
        wipeLocalData = LocalAccountDataCleaner::wipe,
        clearInMemoryProfiles = ProfileRepository::clearInMemory,
        signedOutNotice = { getString(Res.string.auth_notice_signed_out) },
        signOutFailedFallback = {
            runCatching { getString(Res.string.auth_sign_out_failed) }.getOrDefault("Sign out failed")
        },
    )

    val state: StateFlow<AuthState> = controller.state
    val error: StateFlow<String?> = controller.error

    fun initialize() {
        controller.initialize()
    }

    fun signInAnonymously() {
        controller.signInAnonymously()
    }

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        controller.clearError()
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
        Unit
    }.onFailure { e ->
        log.e(e) { "Email sign-up failed" }
        controller.setError(
            e.safeAuthErrorDescription() ?: getString(Res.string.auth_sign_up_failed),
        )
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        controller.clearError()
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }.onFailure { e ->
        log.e(e) { "Email sign-in failed" }
        controller.setError(
            e.safeAuthErrorDescription() ?: getString(Res.string.auth_sign_in_failed),
        )
    }

    suspend fun signOut(): Result<Unit> = controller.signOut()

    suspend fun prepareForServerSwitch(): Result<Unit> = controller.prepareForServerSwitch()

    fun reinitialize() {
        controller.reinitialize()
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean =
        controller.signOutIfSessionInvalid(error, source)

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        controller.clearError()
        SupabaseProvider.client.functions.invoke("delete-account")
        controller.onAccountDeleted()
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        controller.setError(e.message ?: getString(Res.string.auth_account_deletion_failed))
    }

    fun clearError() {
        controller.clearError()
    }

    private fun Throwable.safeAuthErrorDescription(): String? =
        findCause<AuthRestException>()
            ?.errorDescription
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: findCause<RestException>()
                ?.description
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
}

private object StorageBackedAuthStore : AuthKeyValueStore {
    override fun loadAnonymousUserId(): String? = AuthStorage.loadAnonymousUserId()
    override fun saveAnonymousUserId(userId: String) = AuthStorage.saveAnonymousUserId(userId)
    override fun clearAnonymousUserId() = AuthStorage.clearAnonymousUserId()
    override fun loadFlag(key: String): Boolean = AuthStorage.loadFlag(key)
    override fun saveFlag(key: String, value: Boolean) = AuthStorage.saveFlag(key, value)
    override fun loadString(key: String): String? = AuthStorage.loadString(key)
    override fun saveString(key: String, value: String) = AuthStorage.saveString(key, value)
    override fun remove(key: String) = AuthStorage.remove(key)
}

private class SupabaseSessionGatewayAdapter : AuthSessionGateway {
    private val auth get() = SupabaseProvider.client.auth

    override val statusFlow: Flow<AuthGatewayStatus>
        get() = auth.sessionStatus.map(::toGatewayStatus)

    override fun currentStatus(): AuthGatewayStatus = toGatewayStatus(auth.sessionStatus.value)

    override fun hasCurrentSession(): Boolean = auth.currentSessionOrNull() != null

    override suspend fun loadFromStorage(): Boolean = auth.loadFromStorage(autoRefresh = true)

    override suspend fun retrieveUserForCurrentSession() {
        auth.retrieveUserForCurrentSession(false)
    }

    override suspend fun refreshCurrentSession() {
        auth.refreshCurrentSession()
    }

    override suspend fun signOut() {
        auth.signOut()
    }

    override suspend fun clearSession() {
        auth.clearSession()
    }

    private fun toGatewayStatus(status: SessionStatus): AuthGatewayStatus = when (status) {
        is SessionStatus.Authenticated -> AuthGatewayStatus.Authenticated(
            userId = status.session.user?.id.orEmpty(),
            email = status.session.user?.email,
        )

        is SessionStatus.NotAuthenticated -> AuthGatewayStatus.NotAuthenticated(
            isSignOut = status.isSignOut,
        )

        is SessionStatus.Initializing -> AuthGatewayStatus.Initializing

        is SessionStatus.RefreshFailure -> AuthGatewayStatus.RefreshFailure
    }
}
