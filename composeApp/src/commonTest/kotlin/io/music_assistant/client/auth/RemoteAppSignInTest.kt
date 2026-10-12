package io.music_assistant.client.auth

import com.russhwolf.settings.MapSettings
import io.music_assistant.client.api.APICommands
import io.music_assistant.client.api.Answer
import io.music_assistant.client.api.Request
import io.music_assistant.client.data.model.server.AuthProvider
import io.music_assistant.client.data.model.server.ServerInfo
import io.music_assistant.client.data.model.server.StubServiceClient
import io.music_assistant.client.settings.SettingsRepository
import io.music_assistant.client.utils.ConnectionData
import io.music_assistant.client.utils.SessionState
import io.music_assistant.client.utils.currentTimeMillis
import io.music_assistant.client.webrtc.model.RemoteId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home Assistant sign-in over Remote Access: PKCE start, callback checks and `auth/exchange`. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAppSignInTest {
    private val settings = SettingsRepository(MapSettings(), MapSettings())
    private val client = ScriptedServiceClient()
    private lateinit var manager: AuthenticationManager

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        manager = AuthenticationManager(client, settings)
    }

    @AfterTest
    fun tearDown() {
        manager.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `remote sign-in sends a PKCE challenge and saves the pending sign-in`() = runTest {
        client.sessionState.value = remoteSession()
        client.answer = {
            result(
                buildJsonObject {
            put("authorization_url", AUTH_URL)
        put("state", STATE)
        },
            )
        }

        val url = manager.getOAuthUrl(HA_PROVIDER).getOrThrow()

        assertEquals(AUTH_URL, url)
        val signIn = settings.getPendingSignIn() ?: error("No pending sign-in saved")
        assertEquals(STATE, signIn.state)
        assertEquals(REMOTE_ID, signIn.remoteId)
        assertTrue(signIn.expiresAtMs > currentTimeMillis() + PendingSignIn.LIFETIME_MS - 60_000)
        val args = client.requests.single().args ?: error("No args")
        assertEquals(Pkce.challenge(signIn.codeVerifier), args.string("code_challenge"))
        assertEquals("S256", args.string("code_challenge_method"))
        assertEquals("app", args.string("redirect_target"))
        assertEquals(OAuthCallback.RETURN_URL, args.string("return_url"))
    }

    @Test
    fun `a server below the schema gate keeps the legacy flow`() = runTest {
        client.sessionState.value = remoteSession(schemaVersion = 86)
        client.answer = { result(buildJsonObject { put("authorization_url", AUTH_URL) }) }

        manager.getOAuthUrl(HA_PROVIDER).getOrThrow()

        assertFalse(client.requests.single().args?.containsKey("code_challenge") ?: true)
        assertNull(settings.getPendingSignIn())
    }

    @Test
    fun `a response without a native state fails instead of opening the browser`() = runTest {
        client.sessionState.value = remoteSession()
        client.answer = { result(buildJsonObject { put("authorization_url", AUTH_URL) }) }

        assertTrue(manager.getOAuthUrl(HA_PROVIDER).isFailure)
        assertNull(settings.getPendingSignIn())
        assertTrue(manager.authState.value is AuthState.Error)
    }

    @Test
    fun `remote providers hide a redirect sign-in that cannot return to the app`() = runTest {
        val providers = listOf(BUILTIN_PROVIDER, HA_PROVIDER.copy(supportsRemoteApp = false))
        client.answer = { result(providersJson(providers)) }

        client.sessionState.value = remoteSession()
        assertEquals(listOf(BUILTIN_PROVIDER), manager.getProviders().getOrThrow())

        client.sessionState.value = remoteSession(schemaVersion = 86)
        assertEquals(providers, manager.getProviders().getOrThrow())
    }

    @Test
    fun `a callback with the pending state exchanges the code and signs in with the token`() = runTest {
        client.sessionState.value = remoteSession()
        settings.setPendingSignIn(pendingSignIn())
        client.answer = { result(exchangeSuccess()) }

        assertTrue(manager.handleOAuthCallbackUrl(callbackUrl(STATE)))

        val args = client.requests.single { it.command == APICommands.AUTH_EXCHANGE }.args ?: error("No args")
        assertEquals(STATE, args.string("state"))
        assertEquals("ha-code", args.string("code"))
        assertEquals(VERIFIER, args.string("code_verifier"))
        assertEquals(listOf("ma-token"), client.authorizeCalls)
        assertNull(settings.getPendingSignIn(), "A pending sign-in is used once")
    }

    @Test
    fun `a callback with a state this app did not start is dropped`() = runTest {
        client.sessionState.value = remoteSession()
        val signIn = pendingSignIn()
        settings.setPendingSignIn(signIn)

        assertTrue(manager.handleOAuthCallbackUrl(callbackUrl("n.someone-elses-state-0123456789")))
        assertTrue(manager.handleOAuthCallbackUrl("musicassistant://auth/callback?error=access_denied&state=n.other"))

        assertTrue(client.requests.isEmpty())
        assertTrue(client.authorizeCalls.isEmpty())
        assertEquals(signIn, settings.getPendingSignIn(), "A crafted link must not end the real sign-in")
        assertTrue(manager.authState.value !is AuthState.Loading)
    }

    @Test
    fun `an expired pending sign-in is refused and deleted`() = runTest {
        client.sessionState.value = remoteSession()
        settings.setPendingSignIn(pendingSignIn(expiresAtMs = currentTimeMillis() - 1))

        manager.handleOAuthCallbackUrl(callbackUrl(STATE))

        assertTrue(client.requests.isEmpty())
        assertNull(settings.getPendingSignIn())
        assertTrue(manager.authState.value is AuthState.Error)
    }

    @Test
    fun `the exchange waits for the server the sign-in was started on`() = runTest {
        client.sessionState.value = remoteSession(remoteId = "ZYXWVUTSRQPONMLKJIHGFEDCBA")
        settings.setPendingSignIn(pendingSignIn())
        client.answer = { result(exchangeSuccess()) }

        manager.handleOAuthCallbackUrl(callbackUrl(STATE))
        assertTrue(client.requests.isEmpty(), "The code and verifier must not go to another server")

        client.sessionState.value = remoteSession()
        assertEquals(1, client.requests.count { it.command == APICommands.AUTH_EXCHANGE })
        assertEquals(listOf("ma-token"), client.authorizeCalls)
    }

    @Test
    fun `a refused exchange shows the server error`() = runTest {
        client.sessionState.value = remoteSession()
        settings.setPendingSignIn(pendingSignIn())
        client.answer = {
            result(
                buildJsonObject {
                put("success", false)
            put("error", "Invalid or expired sign-in")
            },
            )
        }

        manager.handleOAuthCallbackUrl(callbackUrl(STATE))

        assertEquals(AuthState.Error("Invalid or expired sign-in"), manager.authState.value)
        assertTrue(client.authorizeCalls.isEmpty())
    }

    private fun remoteSession(schemaVersion: Int = 87, remoteId: String = REMOTE_ID) =
        SessionState.Connected.WebRTC(
            RemoteId(remoteId),
            ConnectionData(serverInfo = ServerInfo(serverId = "server-1", schemaVersion = schemaVersion)),
        )

    private fun pendingSignIn(expiresAtMs: Long = currentTimeMillis() + PendingSignIn.LIFETIME_MS) =
        PendingSignIn(STATE, VERIFIER, REMOTE_ID, expiresAtMs)

    private fun callbackUrl(state: String) = "musicassistant://auth/callback?code=ha-code&state=$state"

    private fun exchangeSuccess() = buildJsonObject {
        put("success", true)
        put("access_token", "ma-token")
        putJsonObject("user") {
            put("user_id", "u1")
        put("username", "alice")
        }
    }

    private fun providersJson(providers: List<AuthProvider>) = JsonArray(
        providers.map {
            buildJsonObject {
                put("provider_id", it.id)
                put("provider_type", it.type)
                put("requires_redirect", it.requiresRedirect)
                put("supports_remote_app", it.supportsRemoteApp)
            }
        },
    )

    private fun result(payload: JsonElement) =
        Result.success(Answer(buildJsonObject { put("result", payload) }))

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.content

    private companion object {
        const val AUTH_URL = "https://ha.example/auth/authorize?client_id=x"
        const val STATE = "n.pending-state-0123456789abcdef"
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val REMOTE_ID = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val HA_PROVIDER = AuthProvider("hass", "homeassistant", requiresRedirect = true, supportsRemoteApp = true)
        val BUILTIN_PROVIDER = AuthProvider("builtin", "builtin", requiresRedirect = false)
    }
}

/** Answers every request with [answer] and records requests and authorize calls. */
private class ScriptedServiceClient : StubServiceClient() {
    override val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected.Initial)
    override val foregroundEvents = MutableSharedFlow<Unit>()
    var answer: (Request) -> Result<Answer> = { Result.failure(IllegalStateException("No answer scripted")) }
    val requests = mutableListOf<Request>()
    val authorizeCalls = mutableListOf<String>()

    override suspend fun sendRequest(request: Request): Result<Answer> {
        requests += request
        return answer(request)
    }

    override suspend fun authorize(token: String, isAutoLogin: Boolean) {
        authorizeCalls += token
    }
}
