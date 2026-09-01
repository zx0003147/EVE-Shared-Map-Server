package dev.evesharedmap.server.security

import dev.evesharedmap.server.config.SecretValue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CredentialSecurityTest {
    @Test
    fun `generated credentials carry 256 random bits in frozen format`() {
        val generator = SecureCredentialGenerator()
        val invites = List(100) { generator.generate(CredentialKind.INVITE) }
        val devices = List(100) { generator.generate(CredentialKind.DEVICE) }

        assertTrue(invites.all { CredentialHasher.isWellFormed(it, CredentialKind.INVITE) })
        assertTrue(devices.all { CredentialHasher.isWellFormed(it, CredentialKind.DEVICE) })
        assertEqualsDistinct(invites)
        assertEqualsDistinct(devices)
    }

    @Test
    fun `HMAC is deterministic pepper-bound and does not expose secret in toString`() {
        val pepper = SecretValue.from("test-pepper-with-enough-random-material")
        val hasher = CredentialHasher(pepper)
        pepper.close()
        val secret = SecureCredentialGenerator().generate(CredentialKind.DEVICE)
        val first = hasher.hash(secret)
        val second = hasher.hash(secret)
        val other = hasher.hash(secret + "x")

        assertContentEquals(first, second)
        assertFalse(first.contentEquals(other))
        assertTrue(hasher.matches(first, secret))
        assertNotEquals(secret, hasher.toString())
        first.fill(0)
        second.fill(0)
        other.fill(0)
        hasher.close()
    }

    private fun assertEqualsDistinct(values: List<String>) {
        assertTrue(values.size == values.toSet().size)
    }
}
