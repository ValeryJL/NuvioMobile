package com.nuvio.app.core.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthSessionControllerTest {

    @Test
    fun transientNotAuthenticatedRecoversWithoutSigningOutOrWipingLocalData() = runTest {
        val store = FakeAuthStore().apply {
            saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
            saveString(KEY_LAST_ACCOUNT_USER_ID, "user-1")
        }
        val gateway = FakeAuthGateway(AuthGatewayStatus.Authenticated("user-1", "a@example.com"))
        var wipeCalls = 0
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = { wipeCalls += 1 },
            clearInMemoryProfiles = {},
            signedOutNotice = { "Signed out" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()
        assertIs<AuthState.Authenticated>(controller.state.value)

        // Simulate supabase-kt 3.4.1 onStart race (#1266): Initializing -> NotAuthenticated(isSignOut=false) -> Authenticated
        gateway.status.value = AuthGatewayStatus.Initializing
        runCurrent()
        assertIs<AuthState.Authenticated>(controller.state.value)

        gateway.status.value = AuthGatewayStatus.NotAuthenticated(isSignOut = false)
        runCurrent()
        advanceTimeBy(500L)
        runCurrent()

        assertIs<AuthState.Authenticated>(controller.state.value)
        assertNull(controller.error.value)
        assertEquals(0, wipeCalls)

        gateway.status.value = AuthGatewayStatus.Authenticated("user-1", "a@example.com")
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertIs<AuthState.Authenticated>(controller.state.value)
        assertNull(controller.error.value)
        assertEquals(0, wipeCalls)
    }

    @Test
    fun transientNotAuthenticatedFollowedByRefreshFailureDoesNotSignOutOrWipe() = runTest {
        val store = FakeAuthStore().apply {
            saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
            saveString(KEY_LAST_ACCOUNT_USER_ID, "user-1")
        }
        val gateway = FakeAuthGateway(AuthGatewayStatus.NotAuthenticated(isSignOut = false))
        var wipeCalls = 0
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = { wipeCalls += 1 },
            clearInMemoryProfiles = {},
            signedOutNotice = { "Signed out" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        gateway.status.value = AuthGatewayStatus.RefreshFailure
        runCurrent()
        advanceTimeBy(5_000L)
        runCurrent()

        assertIs<AuthState.Loading>(controller.state.value)
        assertNull(controller.error.value)
        assertEquals(0, wipeCalls)
    }

    @Test
    fun genuineLostSessionPreservesLocalDataAndRestoresSameAccountCleanly() = runTest {
        val store = FakeAuthStore().apply {
            saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
            saveString(KEY_LAST_ACCOUNT_USER_ID, "user-1")
        }
        val gateway = FakeAuthGateway(AuthGatewayStatus.NotAuthenticated(isSignOut = false))
        var wipeCalls = 0
        var clearProfilesCalls = 0
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = { wipeCalls += 1 },
            clearInMemoryProfiles = { clearProfilesCalls += 1 },
            signedOutNotice = { "Signed out notice" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()
        advanceTimeBy(TRANSIENT_NOT_AUTHENTICATED_GRACE_MS - 100L)
        runCurrent()
        assertIs<AuthState.Loading>(controller.state.value)

        advanceTimeBy(200L)
        runCurrent()
        assertIs<AuthState.Unauthenticated>(controller.state.value)
        assertEquals("Signed out notice", controller.error.value)
        assertEquals(1, clearProfilesCalls)
        assertEquals(0, wipeCalls)
        assertEquals("user-1", store.loadString(KEY_LAST_ACCOUNT_USER_ID))

        // Signing back into the same account clears the notice and keeps local Trakt credentials intact.
        gateway.status.value = AuthGatewayStatus.Authenticated("user-1", "a@example.com")
        runCurrent()

        val state = assertIs<AuthState.Authenticated>(controller.state.value)
        assertEquals("user-1", state.userId)
        assertNull(controller.error.value)
        assertEquals(0, wipeCalls)
    }

    @Test
    fun signingIntoDifferentAccountAfterLostSessionWipesPreviousLocalData() = runTest {
        val store = FakeAuthStore().apply {
            saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
            saveString(KEY_LAST_ACCOUNT_USER_ID, "user-1")
        }
        val gateway = FakeAuthGateway(AuthGatewayStatus.NotAuthenticated(isSignOut = true))
        var wipeCalls = 0
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = { wipeCalls += 1 },
            clearInMemoryProfiles = {},
            signedOutNotice = { "Signed out notice" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()
        assertIs<AuthState.Unauthenticated>(controller.state.value)
        assertEquals(0, wipeCalls)

        gateway.status.value = AuthGatewayStatus.Authenticated("user-2", "b@example.com")
        runCurrent()

        val state = assertIs<AuthState.Authenticated>(controller.state.value)
        assertEquals("user-2", state.userId)
        assertEquals(1, wipeCalls)
        assertEquals("user-2", store.loadString(KEY_LAST_ACCOUNT_USER_ID))
    }

    @Test
    fun anonymousSignInAfterLostSessionWipesPreviousAccountData() = runTest {
        val store = FakeAuthStore().apply {
            saveFlag(KEY_HAD_ACCOUNT_SESSION, false)
            saveString(KEY_LAST_ACCOUNT_USER_ID, "user-1")
        }
        val gateway = FakeAuthGateway(AuthGatewayStatus.NotAuthenticated(isSignOut = false))
        var wipeCalls = 0
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = { wipeCalls += 1 },
            clearInMemoryProfiles = {},
            signedOutNotice = { "Signed out notice" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()
        controller.signInAnonymously()

        val state = assertIs<AuthState.Authenticated>(controller.state.value)
        assertTrue(state.isAnonymous)
        assertEquals(1, wipeCalls)
        assertNull(store.loadString(KEY_LAST_ACCOUNT_USER_ID))
    }

    @Test
    fun explicitSignOutWipesLocalDataAndClearsStoredAccountMetadata() = runTest {
        val store = FakeAuthStore().apply {
            saveFlag(KEY_HAD_ACCOUNT_SESSION, true)
            saveString(KEY_LAST_ACCOUNT_USER_ID, "user-1")
        }
        val gateway = FakeAuthGateway(AuthGatewayStatus.Authenticated("user-1", "a@example.com"))
        var wipeCalls = 0
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = { wipeCalls += 1 },
            clearInMemoryProfiles = {},
            signedOutNotice = { "Signed out notice" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()
        val result = controller.signOut()

        assertTrue(result.isSuccess)
        assertIs<AuthState.Unauthenticated>(controller.state.value)
        assertEquals(1, wipeCalls)
        assertEquals(1, gateway.signOutCalls)
        assertNull(store.loadString(KEY_LAST_ACCOUNT_USER_ID))
    }

    @Test
    fun tokenIsExpiredErrorRefreshesSessionInsteadOfInvalidatingOrWiping() = runTest {
        val store = FakeAuthStore()
        val gateway = FakeAuthGateway(AuthGatewayStatus.Authenticated("user-1", "a@example.com")).apply {
            retrieveUserError = IllegalStateException("invalid JWT: token is expired")
        }
        var wipeCalls = 0
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = { wipeCalls += 1 },
            clearInMemoryProfiles = {},
            signedOutNotice = { "Signed out notice" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()

        val state = assertIs<AuthState.Authenticated>(controller.state.value)
        assertEquals("user-1", state.userId)
        assertEquals(1, gateway.refreshCalls)
        assertEquals(0, wipeCalls)
    }

    @Test
    fun authSessionErrorsDetectsExpiredTokenVariants() {
        assertTrue(IllegalStateException("JWT expired").isJwtExpiredError())
        assertTrue(IllegalStateException("token is expired by 5m").isJwtExpiredError())
        assertTrue(IllegalStateException("Token has expired").isJwtExpiredError())
    }

    @Test
    fun mfaRequiredBlocksAuthenticatedTransitionUntilVerified() = runTest {
        val store = FakeAuthStore()
        val gateway = FakeAuthGateway(AuthGatewayStatus.Authenticated("user-1", "a@example.com")).apply {
            mfaVerificationRequired = true
        }
        val controller = AuthSessionController(
            scope = backgroundScope,
            store = store,
            gatewayProvider = { gateway },
            wipeLocalData = {},
            clearInMemoryProfiles = {},
            signedOutNotice = { "Signed out" },
            signOutFailedFallback = { "Sign out failed" },
        )

        controller.initialize()
        runCurrent()

        val pendingState = assertIs<AuthState.MfaRequired>(controller.state.value)
        assertEquals("user-1", pendingState.userId)
        assertEquals("a@example.com", pendingState.email)
        assertEquals(false, store.loadFlag(KEY_HAD_ACCOUNT_SESSION))

        gateway.mfaVerificationRequired = false
        controller.refreshMfaState()
        runCurrent()

        val activeState = assertIs<AuthState.Authenticated>(controller.state.value)
        assertEquals("user-1", activeState.userId)
        assertTrue(store.loadFlag(KEY_HAD_ACCOUNT_SESSION))
    }
}

private class FakeAuthStore : AuthKeyValueStore {
    private var anonymousUserId: String? = null
    private val flags = mutableMapOf<String, Boolean>()
    private val strings = mutableMapOf<String, String>()

    override fun loadAnonymousUserId(): String? = anonymousUserId
    override fun saveAnonymousUserId(userId: String) {
        anonymousUserId = userId
    }

    override fun clearAnonymousUserId() {
        anonymousUserId = null
    }

    override fun loadFlag(key: String): Boolean = flags[key] == true
    override fun saveFlag(key: String, value: Boolean) {
        flags[key] = value
    }

    override fun loadString(key: String): String? = strings[key]
    override fun saveString(key: String, value: String) {
        strings[key] = value
    }

    override fun remove(key: String) {
        flags.remove(key)
        strings.remove(key)
    }
}

private class FakeAuthGateway(
    initialStatus: AuthGatewayStatus,
) : AuthSessionGateway {
    val status = MutableStateFlow(initialStatus)
    var loadFromStorageResult: Boolean = false
    var hasSession: Boolean = false
    var retrieveUserError: Throwable? = null
    var refreshError: Throwable? = null
    var mfaVerificationRequired: Boolean = false
    var refreshCalls: Int = 0
    var signOutCalls: Int = 0

    override val statusFlow: Flow<AuthGatewayStatus> = status
    override fun currentStatus(): AuthGatewayStatus = status.value
    override fun hasCurrentSession(): Boolean = hasSession || status.value is AuthGatewayStatus.Authenticated

    override suspend fun loadFromStorage(): Boolean = loadFromStorageResult

    override suspend fun retrieveUserForCurrentSession() {
        retrieveUserError?.let { throw it }
    }

    override suspend fun requiresMfaVerification(): Boolean = mfaVerificationRequired

    override suspend fun refreshCurrentSession() {
        refreshCalls += 1
        refreshError?.let { throw it }
        retrieveUserError = null
    }

    override suspend fun signOut() {
        signOutCalls += 1
        status.value = AuthGatewayStatus.NotAuthenticated(isSignOut = true)
    }

    override suspend fun clearSession() {
        hasSession = false
        status.value = AuthGatewayStatus.NotAuthenticated(isSignOut = true)
    }
}
