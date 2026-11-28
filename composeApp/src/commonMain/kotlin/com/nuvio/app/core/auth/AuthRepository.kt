package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import com.nuvio.app.features.profiles.ProfileRepository
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.mfa.FactorType
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.auth_account_deletion_failed
import nuvio.composeapp.generated.resources.auth_mfa_invalid_code
import nuvio.composeapp.generated.resources.auth_notice_signed_out
import nuvio.composeapp.generated.resources.auth_sign_in_failed
import nuvio.composeapp.generated.resources.auth_sign_out_failed
import nuvio.composeapp.generated.resources.auth_sign_up_failed
import nuvio.composeapp.generated.resources.auth_signup_resend_failed
import nuvio.composeapp.generated.resources.settings_security_email_change_failed
import nuvio.composeapp.generated.resources.settings_security_invalid_email
import nuvio.composeapp.generated.resources.settings_security_password_too_short
import nuvio.composeapp.generated.resources.settings_security_password_update_failed
import nuvio.composeapp.generated.resources.settings_security_same_email
import nuvio.composeapp.generated.resources.settings_security_totp_enroll_failed
import nuvio.composeapp.generated.resources.settings_security_totp_unenroll_failed
import nuvio.composeapp.generated.resources.settings_security_wrong_password
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

    suspend fun signUpWithEmail(email: String, password: String): Result<SignUpOutcome> = runCatching {
        controller.clearError()
        val cleanEmail = email.trim()
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = cleanEmail
            this.password = password
        }
        if (SupabaseProvider.client.auth.currentSessionOrNull() != null) {
            SignUpOutcome.SignedIn
        } else {
            SignUpOutcome.EmailConfirmationRequired(cleanEmail)
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        log.e(e) { "Email sign-up failed" }
        controller.setError(
            e.safeAuthErrorDescription() ?: getString(Res.string.auth_sign_up_failed),
        )
    }

    suspend fun resendConfirmationEmail(email: String): Result<Unit> = runCatching {
        SupabaseProvider.client.auth.resendEmail(
            type = OtpType.Email.SIGNUP,
            email = email.trim(),
        )
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        log.w(e) { "Failed to resend signup confirmation email" }
        throw IllegalStateException(
            e.safeAuthErrorDescription() ?: getString(Res.string.auth_signup_resend_failed),
            e,
        )
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        controller.clearError()
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        log.e(e) { "Email sign-in failed" }
        controller.setError(
            e.safeAuthErrorDescription() ?: getString(Res.string.auth_sign_in_failed),
        )
    }

    suspend fun listVerifiedTotpFactors(): Result<List<TotpFactorInfo>> = runCatching {
        SupabaseProvider.client.auth.mfa
            .retrieveFactorsForCurrentUser()
            .filter { it.factorType.equals("totp", ignoreCase = true) && it.isVerified }
            .map { factor ->
                TotpFactorInfo(
                    id = factor.id,
                    friendlyName = factor.friendlyName,
                    isVerified = factor.isVerified,
                )
            }
    }.onFailure { e ->
        if (e is CancellationException) throw e
    }

    suspend fun enrollTotp(friendlyName: String = "Nuvio Authenticator"): Result<TotpEnrollmentData> = runCatching {
        val mfa = SupabaseProvider.client.auth.mfa
        val existingFactors = runCatching { mfa.retrieveFactorsForCurrentUser() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(emptyList())
        existingFactors
            .filter { it.factorType.equals("totp", ignoreCase = true) && !it.isVerified }
            .forEach { staleFactor ->
                runCatching { mfa.unenroll(staleFactor.id) }
                    .onFailure { if (it is CancellationException) throw it }
            }

        val enrolled = mfa.enroll(
            factorType = FactorType.TOTP,
            friendlyName = friendlyName,
        )
        TotpEnrollmentData(
            factorId = enrolled.id,
            secret = enrolled.data.secret,
            uri = enrolled.data.uri,
            qrCodeSvg = normalizeTotpQrSvg(enrolled.data.qrCode),
        )
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        log.w(e) { "TOTP enrollment failed" }
        throw IllegalStateException(
            e.safeAuthErrorDescription() ?: getString(Res.string.settings_security_totp_enroll_failed),
            e,
        )
    }

    suspend fun verifyTotp(factorId: String, code: String): Result<Unit> = runCatching {
        controller.clearError()
        val digits = code.filter(Char::isDigit)
        if (digits.length != 6) {
            throw IllegalArgumentException(getString(Res.string.auth_mfa_invalid_code))
        }
        SupabaseProvider.client.auth.mfa.createChallengeAndVerify(
            factorId = factorId,
            code = digits,
        )
        controller.refreshMfaState()
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        log.w(e) { "TOTP verification failed" }
        val message = e.safeAuthErrorDescription()
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: getString(Res.string.auth_mfa_invalid_code)
        controller.setError(message)
        throw IllegalStateException(message, e)
    }

    suspend fun verifyTotpLogin(code: String): Result<Unit> = runCatching {
        val digits = code.filter(Char::isDigit)
        if (digits.length != 6) {
            val message = getString(Res.string.auth_mfa_invalid_code)
            controller.setError(message)
            throw IllegalArgumentException(message)
        }
        val verifiedFactors = listVerifiedTotpFactors().getOrThrow()
        val factor = verifiedFactors.firstOrNull()
            ?: throw IllegalStateException(getString(Res.string.auth_mfa_invalid_code))
        verifyTotp(factorId = factor.id, code = digits).getOrThrow()
    }

    suspend fun unenrollTotp(factorId: String): Result<Unit> = runCatching {
        SupabaseProvider.client.auth.mfa.unenroll(factorId)
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        log.w(e) { "TOTP unenroll failed" }
        throw IllegalStateException(
            e.safeAuthErrorDescription() ?: getString(Res.string.settings_security_totp_unenroll_failed),
            e,
        )
    }

    suspend fun updatePasswordWithCurrent(
        currentPassword: String,
        newPassword: String,
    ): Result<Unit> = runCatching {
        if (newPassword.length < 6) {
            throw IllegalArgumentException(getString(Res.string.settings_security_password_too_short))
        }
        val currentEmail = currentNonAnonymousEmail()
        verifyCurrentPasswordStatelessly(email = currentEmail, password = currentPassword)
        SupabaseProvider.client.auth.updateUser {
            password = newPassword
        }
        Unit
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        log.w(e) { "Password update failed" }
        val message = e.safeAuthErrorDescription()
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: getString(Res.string.settings_security_password_update_failed)
        throw IllegalStateException(message, e)
    }

    suspend fun requestEmailChange(
        currentPassword: String,
        newEmail: String,
    ): Result<Unit> = runCatching {
        val cleanEmail = newEmail.trim()
        if (!cleanEmail.contains('@') || !cleanEmail.substringAfter('@').contains('.')) {
            throw IllegalArgumentException(getString(Res.string.settings_security_invalid_email))
        }
        val currentEmail = currentNonAnonymousEmail()
        if (cleanEmail.equals(currentEmail, ignoreCase = true)) {
            throw IllegalArgumentException(getString(Res.string.settings_security_same_email))
        }
        verifyCurrentPasswordStatelessly(email = currentEmail, password = currentPassword)
        SupabaseProvider.client.auth.updateUser {
            email = cleanEmail
        }
        Unit
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        log.w(e) { "Email change request failed" }
        val message = e.safeAuthErrorDescription()
            ?: e.message?.takeIf { it.isNotBlank() }
            ?: getString(Res.string.settings_security_email_change_failed)
        throw IllegalStateException(message, e)
    }

    private fun currentNonAnonymousEmail(): String {
        val authenticated = state.value as? AuthState.Authenticated
            ?: throw IllegalStateException("Not authenticated")
        if (authenticated.isAnonymous) {
            throw IllegalStateException("Not authenticated")
        }
        return authenticated.email?.trim()?.takeIf { it.isNotBlank() }
            ?: SupabaseProvider.client.auth.currentUserOrNull()?.email?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Email missing on current session")
    }

    /**
     * Checks the user's current password via GoTrue's stateless token endpoint without
     * calling `auth.signInWith` on [SupabaseProvider.client], so an active AAL2 session
     * is never downgraded back to AAL1 when TOTP 2FA is enabled.
     */
    @OptIn(SupabaseInternal::class)
    private suspend fun verifyCurrentPasswordStatelessly(email: String, password: String) {
        if (password.isBlank()) {
            throw IllegalArgumentException(getString(Res.string.settings_security_wrong_password))
        }
        val config = ServerConfigurationRepository.active.value
        val endpoint = "${config.backendUrl.trimEnd('/')}/auth/v1/token?grant_type=password"
        val response = SupabaseProvider.client.httpClient.httpClient.post(endpoint) {
            header("apikey", config.publishableKey)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("email", email)
                    put("password", password)
                }.toString(),
            )
        }
        if (!response.status.isSuccess()) {
            throw IllegalStateException(getString(Res.string.settings_security_wrong_password))
        }
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

    override suspend fun requiresMfaVerification(): Boolean {
        val status = auth.mfa.status
        if (status.enabled && !status.active) return true
        if (status.active) return false
        val factors = runCatching { auth.mfa.retrieveFactorsForCurrentUser() }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
            .orEmpty()
        val hasVerifiedTotp = factors.any {
            it.factorType.equals("totp", ignoreCase = true) && it.isVerified
        }
        return hasVerifiedTotp && !status.active
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
