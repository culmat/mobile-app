package io.music_assistant.client.auth

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64

/** PKCE (RFC 7636) code verifiers and their S256 challenges. */
internal object Pkce {
    // 32 random bytes encode to the 43-character minimum the RFC allows
    private const val VERIFIER_BYTES = 32

    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** A new random code verifier of 43 unreserved characters. */
    fun createVerifier(): String = base64Url.encode(CryptographyRandom.nextBytes(VERIFIER_BYTES))

    /** The S256 code challenge of [verifier]: its unpadded base64url SHA-256. */
    suspend fun challenge(verifier: String): String =
        base64Url.encode(CryptographyProvider.Default.get(SHA256).hasher().hash(verifier.encodeToByteArray()))
}
