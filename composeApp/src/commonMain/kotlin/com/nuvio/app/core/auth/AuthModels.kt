package com.nuvio.app.core.auth

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class MfaRequired(
        val userId: String,
        val email: String?,
    ) : AuthState
    data class Authenticated(
        val userId: String,
        val email: String?,
        val isAnonymous: Boolean,
    ) : AuthState
}

sealed interface SignUpOutcome {
    data object SignedIn : SignUpOutcome
    data class EmailConfirmationRequired(val email: String) : SignUpOutcome
}

data class TotpEnrollmentData(
    val factorId: String,
    val secret: String,
    val uri: String,
    val qrCodeSvg: String,
)

data class TotpFactorInfo(
    val id: String,
    val friendlyName: String?,
    val isVerified: Boolean,
)

val AuthState.isLoggedIn: Boolean
    get() = this is AuthState.Authenticated

val AuthState.userId: String?
    get() = (this as? AuthState.Authenticated)?.userId

val AuthState.isAnonymous: Boolean
    get() = (this as? AuthState.Authenticated)?.isAnonymous == true
