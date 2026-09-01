package dev.evesharedmap.server.security

import dev.evesharedmap.server.config.SecretValue
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class CredentialKind(
    val prefix: String,
) {
    INVITE("esm_inv_"),
    DEVICE("esm_dev_"),
}

interface CredentialGenerator {
    fun generate(kind: CredentialKind): String
}

class SecureCredentialGenerator(
    private val random: SecureRandom = SecureRandom(),
) : CredentialGenerator {
    override fun generate(kind: CredentialKind): String {
        val bytes = ByteArray(32)
        return try {
            random.nextBytes(bytes)
            kind.prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } finally {
            bytes.fill(0)
        }
    }
}

class CredentialHasher(
    pepper: SecretValue,
) : AutoCloseable {
    private var pepperBytes: ByteArray = pepper.copyToByteArray()
    private var closed = false

    @Synchronized
    fun hash(secret: String): ByteArray {
        check(!closed) { "Credential hasher is closed." }
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(pepperBytes, HMAC_ALGORITHM))
            mac.doFinal(secretBytes)
        } finally {
            secretBytes.fill(0)
        }
    }

    fun matches(expected: ByteArray, secret: String): Boolean {
        val actual = hash(secret)
        return try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            actual.fill(0)
        }
    }

    override fun close() = synchronized(this) {
        if (!closed) {
            pepperBytes.fill(0)
            pepperBytes = ByteArray(0)
            closed = true
        }
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private val materialPattern = Regex("[A-Za-z0-9_-]{43}")

        fun isWellFormed(secret: String, kind: CredentialKind): Boolean =
            secret.startsWith(kind.prefix) &&
                secret.length == kind.prefix.length + 43 &&
                materialPattern.matches(secret.substring(kind.prefix.length))

        fun operatorPrefix(secret: String): String = secret.take(16)
    }
}
