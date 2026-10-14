package com.guuslab.nemi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {
    @Test
    fun `challenge is base64url sha256 of the verifier`() {
        // Expected value computed independently with Python hashlib.
        assertEquals(
            "2-JTmId0HonySCuP0Mw-Jrv7Xtft_Gk0eCAV6lIyxCk",
            Pkce.challengeFor("dBjftJeZ4CVP-mJ92K1Ss8eo9j4ZhUPg5u8vLvB9SXQ"),
        )
    }

    @Test
    fun `generated pairs are valid and unique`() {
        val a = Pkce.generate()
        val b = Pkce.generate()
        assertEquals(43, a.codeVerifier.length)
        assertTrue(a.codeVerifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertEquals(Pkce.challengeFor(a.codeVerifier), a.codeChallenge)
        assertEquals("S256", a.codeChallengeMethod)
        assertNotEquals(a.codeVerifier, b.codeVerifier)
        assertTrue(a.codeVerifier !in a.toString())
    }

    @Test
    fun `verifier length is enforced`() {
        assertFailsWith<IllegalArgumentException> { Pkce.challengeFor("short") }
    }

    @Test
    fun `base64url round trips every length`() {
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + 11).toByte() }
            val encoded = Base64Url.encode(bytes)
            assertTrue('=' !in encoded && '+' !in encoded && '/' !in encoded)
            assertContentEquals(bytes, Base64Url.decode(encoded))
        }
    }
}
