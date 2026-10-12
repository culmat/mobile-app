package io.music_assistant.client.auth

import co.touchlab.kermit.Logger
import io.music_assistant.client.api.Request
import io.music_assistant.client.api.ServiceClient
import io.music_assistant.client.data.model.server.AuthProvider
import io.music_assistant.client.data.model.server.LoginResponse
import io.music_assistant.client.data.model.server.OauthUrl
import io.music_assistant.client.data.model.server.User
import io.music_assistant.client.data.model.server.supportsRemoteAppSignIn
import io.music_assistant.client.settings.ConnectionHistoryEntry
import io.music_assistant.client.settings.SettingsRepository
import io.music_assistant.client.utils.AuthProcessState
import io.music_assistant.client.utils.DataConnectionState
import io.music_assistant.client.utils.SessionState
import io.music_assistant.client.utils.currentTimeMillis
import io.music_assistant.client.utils.mainDispatcher
import io.music_assistant.client.utils.resultAs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthState {
    data object Idle : AuthState()
    data object Loading : AuthState()
    data class ProvidersLoaded(val providers: List<AuthProvider>) : AuthState()
    data class Authenticated(val user: User) : AuthState()
    data class Error(val message: String) : AuthState()
}

private val log = Logger.withTag("AuthMgr")

class AuthenticationManager(
    private val serviceClient: ServiceClient,
    private val settings: SettingsRepository,
) : AuthCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // OAuthHandler will be set by platform (e.g., MainActivity on Android)
    var oauthHandler: OAuthHandler? = null

    // True while an OAuth browser is open and we're awaiting its deep-link callback.
    private var awaitingOAuthCallback = false

    /**
     * Result of an OAuth deep link, held until an auth attempt consumes it.
     *
     * The deep link foregrounds the app, and foregrounding tears the transport down for
     * a reconnect. Sending the token straight away would write it into a socket that is
     * already dying, so the server never sees it and the user gets a connectivity error
     * on a login that actually succeeded. Instead we buffer the result and let the
     * `AwaitingAuth(NotStarted)` branch below spend it once the transport settles — the
     * same state-driven path the saved-token auto-login already uses. The result survives
     * an aborted attempt, so a reconnect mid-round-trip retries instead of dead-ending.
     */
    private var pendingCallback: PendingCallback? = null

    /** Guards against the two entry points spending [pendingCallback] twice. */
    private var spendingPendingCallback = false

    /** Fails the flow if [pendingCallback] is never spent. */
    private var pendingOAuthWatchdog: Job? = null

    // Flag to prevent auto-login during intentional logout - using StateFlow for proper synchronization
    private val _isLoggingOut = MutableStateFlow(false)
    private val isLoggingOut: Boolean
        get() = _isLoggingOut.value

    /**
     * Snapshot at construction: will the cold-launch auto-connect produce a silent auto-login?
     * True iff the most-recent saved server has a saved token.
     */
    val willAutoLoginOnLaunch: Boolean =
        settings.connectionHistory.value.firstOrNull()
            ?.serverId?.let { settings.getTokenForServer(it) } != null

    init {
        // Monitor session state to update auth UI state
        scope.launch {
            serviceClient.sessionState.collect { state ->
                if (state is SessionState.Connected) {
                    when (val dataConnectionState = state.dataConnectionState) {
                        is DataConnectionState.AwaitingAuth -> {
                            when (dataConnectionState.authProcessState) {
                                AuthProcessState.NotStarted -> {
                                    // A pending OAuth callback outranks the saved token: the
                                    // user just completed an interactive login, so no server-id
                                    // check applies — the fresh token defines the server. An
                                    // exchange checks its Remote ID itself.
                                    val pending = pendingCallback
                                    if (pending != null) {
                                        log.i { "AwaitingAuth(NotStarted) — spending pending OAuth callback" }
                                        spendPendingCallback(pending)
                                    } else if (isLoggingOut) {
                                        log.i { "AwaitingAuth(NotStarted) — skipping auto-login (logging out)" }
                                    } else {
                                        // Keyed by server id, so a different server at the
                                        // same address simply has no token here: the login
                                        // fields unblock instead of the flow dying.
                                        val serverId = dataConnectionState.serverInfo.serverId
                                        val token = settings.getTokenForServer(serverId)
                                        if (token == null) {
                                            log.i { "AwaitingAuth(NotStarted) — no saved token for server" }
                                        } else {
                                            log.i { "AwaitingAuth(NotStarted) — auto-login with saved token" }
                                            authorizeWithSavedToken(token)
                                        }
                                    }
                                }

                                AuthProcessState.InProgress -> {
                                    log.i { "AwaitingAuth(InProgress)" }
                                    _authState.value = AuthState.Loading
                                }

                                is AuthProcessState.Failed -> {
                                    clearPendingCallback()
                                    settings.setTokenForServer(
                                        dataConnectionState.serverInfo.serverId,
                                        null,
                                    )
                                    log.i { "Cleared token for server due to auth failure" }

                                    log.i {
                                        "AwaitingAuth(Failed): " +
                                            dataConnectionState.authProcessState.reason
                                    }
                                    _authState.value =
                                        AuthState.Error(dataConnectionState.authProcessState.reason)
                                }

                                AuthProcessState.LoggedOut -> {
                                    clearPendingCallback()
                                    settings.setTokenForServer(
                                        dataConnectionState.serverInfo.serverId,
                                        null,
                                    )
                                    log.d { "Cleared token for server" }

                                    log.i { "AwaitingAuth(LoggedOut)" }
                                    _authState.value = AuthState.Idle
                                }
                            }
                        }

                        is DataConnectionState.Authenticated -> {
                            state.user?.let { user ->
                                clearPendingCallback()
                                log.i { "Authenticated" }
                                _authState.value = AuthState.Authenticated(user)

                                val serverInfo = dataConnectionState.serverInfo
                                settings.setTokenForServer(
                                    serverInfo.serverId,
                                    dataConnectionState.token,
                                )
                                // The one point where the connection is proven to work and
                                // the server has named itself, so the only place to save it.
                                settings.addOrUpdateHistoryEntry(
                                    ConnectionHistoryEntry.from(state, serverInfo),
                                )
                            }
                        }

                        DataConnectionState.AwaitingServerInfo -> {
                            // Logged so a stuck reconnect (no `server/hello`) is observable.
                            log.i { "AwaitingServerInfo" }
                        }
                    }
                }
            }
        }

        // Recover from an abandoned OAuth flow: if the user backs out of the
        // external browser, no callback arrives and authState is stuck on
        // Loading. Only for handlers that cannot report the cancellation
        // themselves — an in-app session does, and it never backgrounds the app,
        // so for it this heuristic could only ever fire spuriously.
        scope.launch {
            serviceClient.foregroundEvents.collect {
                if (oauthHandler?.reportsCancellation == true) return@collect
                if (awaitingOAuthCallback) log.i { "OAuth flow abandoned (foregrounded without callback)" }
                cancelOAuthFlow(reason = null)
            }
        }
    }

    override suspend fun getProviders(): Result<List<AuthProvider>> {
        return try {
            _authState.value = AuthState.Loading
            val response = serviceClient.sendRequest(Request.Auth.providers())

            if (response.isFailure) {
                val error = "Failed to fetch auth providers"
                _authState.value = AuthState.Error(error)
                return Result.failure(Exception(error))
            }

            response.resultAs<List<AuthProvider>>()?.let { all ->
                // over Remote Access a redirect sign-in only works when it can return to the app
                val providers = all.filter {
                    !it.requiresRedirect || remoteAppSignInSession() == null || it.supportsRemoteApp
                }
                _authState.value = AuthState.ProvidersLoaded(providers)
                Result.success(providers)
            } ?: run {
                val error = "Failed to parse providers"
                _authState.value = AuthState.Error(error)
                Result.failure(Exception(error))
            }
        } catch (e: CancellationException) {
            // Coroutine cancellation (e.g. AuthenticationViewModel's flatMapLatest
            // switching loads on a session-state change) must propagate, not be
            // swallowed by the broad catch below — otherwise it would spuriously
            // drive authState to Error on every disconnect/connection-type switch.
            throw e
        } catch (e: Exception) {
            val error = e.message ?: "Exception fetching providers"
            _authState.value = AuthState.Error(error)
            Result.failure(e)
        }
    }

    @Suppress("UnusedParameter") // providerId reserved — current server login API doesn't yet route per-provider
    override suspend fun loginWithCredentials(
        providerId: String,
        username: String,
        password: String,
    ): Result<Unit> {
        return try {
            _isLoggingOut.value = false  // Reset flag when user explicitly logs in
            _authState.value = AuthState.Loading
            serviceClient.login(username, password)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.message ?: "Login failed"
            _authState.value = AuthState.Error(error)
            Result.failure(e)
        }
    }

    override suspend fun getOAuthUrl(provider: AuthProvider): Result<String> {
        return try {
            // Remote ID and PKCE verifier, when the browser returns to the app over Remote Access
            val remoteSignIn = remoteAppSignInSession()
                ?.takeIf { provider.supportsRemoteApp }
                ?.let { it.remoteId.rawId to Pkce.createVerifier() }
            val response = serviceClient.sendRequest(
                Request.Auth.authorizationUrl(
                    provider.id,
                    OAuthCallback.RETURN_URL,
                    codeChallenge = remoteSignIn?.let { Pkce.challenge(it.second) },
                ),
            )

            if (response.isFailure) return oauthUrlFailure("Failed to get OAuth URL")
            val oauthUrl = response.resultAs<OauthUrl>()
                ?: return oauthUrlFailure("Failed to parse OAuth URL")
            val url = oauthUrl.url ?: return oauthUrlFailure(oauthUrl.error ?: "Failed to get OAuth URL")

            remoteSignIn?.let { (remoteId, verifier) ->
                // the server marks a sign-in that returns to the native app with "n."
                val state = oauthUrl.state?.takeIf { it.startsWith("n.") }
                    ?: return oauthUrlFailure("Unexpected sign-in response from the server")
                settings.setPendingSignIn(
                    PendingSignIn(state, verifier, remoteId, currentTimeMillis() + PendingSignIn.LIFETIME_MS),
                )
            }
            Result.success(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            oauthUrlFailure(e.message ?: "Failed to get OAuth URL")
        }
    }

    override fun startOAuthFlow(oauthUrl: String): Result<Unit> {
        val handler = oauthHandler
        if (handler == null) {
            val error = "OAuth not supported on this platform"
            _authState.value = AuthState.Error(error)
            return Result.failure(Exception(error))
        }

        return try {
            _authState.value = AuthState.Loading
            // Set BEFORE the call, not after: a handler that fails synchronously
            // reports it through cancelOAuthFlow() from inside openOAuthUrl, and
            // setting the flag afterwards would resurrect a flow that already ended.
            awaitingOAuthCallback = true
            handler.openOAuthUrl(oauthUrl)
            Result.success(Unit)
        } catch (e: Exception) {
            awaitingOAuthCallback = false
            val error = e.message ?: "OAuth flow failed"
            _authState.value = AuthState.Error(error)
            Result.failure(e)
        }
    }

    /**
     * End a pending OAuth flow that produced no token: the user dismissed the auth
     * session, or presenting it failed.
     *
     * Idempotent and a no-op when no flow is pending, so the handler may call it
     * without tracking whether anything else already settled the flow. [reason] is
     * shown to the user; a plain dismissal passes null and drops back to [AuthState.Idle]
     * silently.
     */
    fun cancelOAuthFlow(reason: String?) {
        if (!awaitingOAuthCallback || _authState.value !is AuthState.Loading) return
        awaitingOAuthCallback = false
        log.i { "OAuth flow cancelled${reason?.let { ": $it" } ?: ""}" }
        _authState.value = reason?.let { AuthState.Error(it) } ?: AuthState.Idle
    }

    /**
     * Route an incoming URL that may be an OAuth callback. The single entry point for
     * all three delivery paths: the iOS in-app auth session, the iOS deep-link handler
     * and Android's launch intent.
     *
     * @return true when the URL was an OAuth callback and has been dealt with, so a
     *   deep-link dispatcher knows not to forward it anywhere else.
     */
    fun handleOAuthCallbackUrl(urlString: String): Boolean =
        when (val result = OAuthCallback.parse(urlString)) {
            is OAuthCallbackResult.Code -> {
                when (val state = result.state) {
                    null -> handleOAuthCallback(result.code)
                    else -> handleSignInCallback(state, result.code)
                }
                true
            }

            is OAuthCallbackResult.Failed -> {
                // a failure of a sign-in this app did not start must not end the one it did
                if (result.state == null || takePendingSignIn(result.state) != null) {
                    cancelOAuthFlow(result.reason)
                }
                true
            }

            OAuthCallbackResult.NotOAuth -> false
        }

    /** Sign in with the access [token] from a callback of the server's own redirect page. */
    fun handleOAuthCallback(token: String) = acceptCallback(PendingCallback.Token(token))

    private fun acceptCallback(callback: PendingCallback) {
        log.d { "OAuth callback received" }
        // Clear synchronously (before the launch) so the foreground collector,
        // which fires after this on the success path, sees no pending flow.
        awaitingOAuthCallback = false
        _isLoggingOut.value = false
        pendingCallback = callback
        _authState.value = AuthState.Loading

        pendingOAuthWatchdog?.cancel()
        pendingOAuthWatchdog = scope.launch {
            delay(OAUTH_TOKEN_WAIT_MS)
            if (pendingCallback == null) return@launch
            pendingCallback = null
            log.e { "OAuth: callback unspent after ${OAUTH_TOKEN_WAIT_MS}ms — cannot authorize" }
            _authState.value = AuthState.Error("Connection timeout. Please try again.")
        }

        // The session may already sit in a state that can spend the token — including
        // `Failed` from an earlier attempt, which never emits again on its own. The
        // collector saw that state before the token existed and a StateFlow does not
        // repeat itself, so drive it here. `InProgress` is left alone: an attempt is
        // already in flight, and its outcome re-enters the collector.
        scope.launch {
            val awaitingAuth = (serviceClient.sessionState.value as? SessionState.Connected)
                ?.dataConnectionState as? DataConnectionState.AwaitingAuth
            if (awaitingAuth != null && awaitingAuth.authProcessState != AuthProcessState.InProgress) {
                spendPendingCallback(callback)
            }
        }
    }

    /**
     * Spend a deep-link OAuth callback. The callback is NOT cleared here: an attempt that the
     * transport aborts mid-round-trip leaves the session state untouched, so the next
     * `AwaitingAuth(NotStarted)` must be able to retry with it. Only a settled outcome
     * (Authenticated, Failed, LoggedOut) or the watchdog clears it.
     */
    private suspend fun spendPendingCallback(callback: PendingCallback) {
        if (spendingPendingCallback) return
        spendingPendingCallback = true
        try {
            when (callback) {
                is PendingCallback.Token -> serviceClient.authorize(callback.token, isAutoLogin = false)
                is PendingCallback.Exchange -> exchangeAndAuthorize(callback)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e) { "Authorization failed" }
            clearPendingCallback()
            _authState.value = AuthState.Error(e.message ?: "Authorization failed")
        } finally {
            spendingPendingCallback = false
        }
    }

    private fun clearPendingCallback() {
        pendingCallback = null
        pendingOAuthWatchdog?.cancel()
        pendingOAuthWatchdog = null
    }

    private suspend fun authorizeWithSavedToken(token: String) {
        try {
            serviceClient.authorize(token, isAutoLogin = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Silent failure - user will see auth UI
        }
    }

    override suspend fun logout(): Result<Unit> {
        return try {
            // Set flag FIRST, before any async operations
            _isLoggingOut.value = true
            clearPendingCallback()
            val currentState = serviceClient.sessionState.value
            (currentState as? SessionState.Connected)?.serverInfo?.serverId?.let {
                settings.setTokenForServer(it, null)
            }

            serviceClient.logout()
            _authState.value = AuthState.Idle
            // Keep the flag set to prevent auto-login until user explicitly logs in again
            Result.success(Unit)
        } catch (e: Exception) {
            _isLoggingOut.value = false
            Result.failure(e)
        }
    }

    fun close() {
        scope.cancel()
    }

    private fun handleSignInCallback(state: String, code: String) {
        val signIn = takePendingSignIn(state) ?: return
        if (signIn.isExpired(currentTimeMillis())) {
            awaitingOAuthCallback = false
            _authState.value = AuthState.Error("The sign-in has expired. Please try again.")
            return
        }
        acceptCallback(PendingCallback.Exchange(signIn, code))
    }

    /**
     * Remove and return the pending sign-in of [state].
     *
     * Returns null and keeps the pending sign-in when this app did not start [state], so a
     * crafted link can neither use nor end it.
     */
    private fun takePendingSignIn(state: String): PendingSignIn? {
        val signIn = settings.getPendingSignIn()?.takeIf { it.state == state } ?: run {
            log.w { "Dropping a sign-in callback this app did not start" }
            return null
        }
        settings.setPendingSignIn(null)
        return signIn
    }

    /** Trade the code of [callback] for an access token with `auth/exchange`, then authorize with it. */
    private suspend fun exchangeAndAuthorize(callback: PendingCallback.Exchange) {
        val signIn = callback.signIn
        // the code and verifier only ever go to the server the sign-in was started on
        val session = serviceClient.sessionState.value as? SessionState.Connected.WebRTC
        if (session?.remoteId?.rawId != signIn.remoteId) {
            log.i { "Sign-in callback waits for the server it was started on" }
            return
        }
        val response = serviceClient.sendRequest(
            Request.Auth.exchange(signIn.state, callback.code, signIn.codeVerifier, settings.deviceName.value),
        )
        // the connection dropped mid-request; the next AwaitingAuth(NotStarted) retries
        if (response.isFailure) return
        val login = response.resultAs<LoginResponse>()
        val token = login?.token?.takeIf { login.success && it.isNotBlank() } ?: run {
            log.w { "auth/exchange failed: ${login?.error}" }
            clearPendingCallback()
            _authState.value = AuthState.Error(login?.error ?: "Sign-in failed")
            return
        }
        // the server accepts a code only once, so a retry from here on must reuse the token
        pendingCallback = PendingCallback.Token(token)
        serviceClient.authorize(token, isAutoLogin = false)
    }

    /** The Remote Access session when its server supports PKCE sign-in over it, else null. */
    private fun remoteAppSignInSession(): SessionState.Connected.WebRTC? =
        (serviceClient.sessionState.value as? SessionState.Connected.WebRTC)
            ?.takeIf { supportsRemoteAppSignIn(it.serverInfo?.schemaVersion) }

    private fun oauthUrlFailure(message: String): Result<String> {
        _authState.value = AuthState.Error(message)
        return Result.failure(Exception(message))
    }

    /** What an OAuth deep link delivered, waiting for a connection that can spend it. */
    private sealed interface PendingCallback {
        /** An access token from the server's own redirect page. */
        data class Token(val token: String) : PendingCallback

        /** An authorization code for [signIn], to trade for a token with `auth/exchange`. */
        data class Exchange(val signIn: PendingSignIn, val code: String) : PendingCallback
    }

    private companion object {
        /**
         * How long a deep-link OAuth token may wait for a transport that can spend it.
         * Longer than the old connect wait because the window now covers a full
         * foreground reconnect, including its backoff, not just a readiness check.
         */
        const val OAUTH_TOKEN_WAIT_MS = 20_000L
    }
}
