package io.music_assistant.client.auth

import kotlinx.serialization.Serializable

/**
 * A PKCE sign-in this app started over Remote Access and has not finished yet.
 *
 * Saved before the browser opens, so it survives the app being killed while the user signs in.
 *
 * @property state The server's state of the sign-in; the callback must return it unchanged.
 * @property codeVerifier The PKCE verifier that `auth/exchange` proves the sign-in with.
 * @property remoteId Raw Remote ID of the server the sign-in was started on.
 * @property expiresAtMs Epoch milliseconds after which the server no longer knows the sign-in.
 */
@Serializable
data class PendingSignIn(
    val state: String,
    val codeVerifier: String,
    val remoteId: String,
    val expiresAtMs: Long,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    companion object {
        /** Same lifetime as the server's pending sign-ins. */
        const val LIFETIME_MS = 10 * 60 * 1000L
    }
}
