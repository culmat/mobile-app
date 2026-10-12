package io.music_assistant.client.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun `challenge matches the RFC 7636 appendix B vector`() = runTest {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `verifier has the shape the server accepts`() {
        val verifier = Pkce.createVerifier()

        assertTrue(Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier), "Not an RFC 7636 verifier: $verifier")
        assertNotEquals(verifier, Pkce.createVerifier())
    }

    @Test
    fun `challenge has the shape the server accepts`() = runTest {
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(Pkce.challenge(Pkce.createVerifier())))
    }
}
