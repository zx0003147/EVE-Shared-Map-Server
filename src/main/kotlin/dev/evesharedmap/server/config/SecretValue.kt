package dev.evesharedmap.server.config

import java.util.Arrays

class SecretValue private constructor(
    private var characters: CharArray,
) : AutoCloseable {
    private var closed = false

    internal fun copyToString(): String = synchronized(this) {
        check(!closed) { "Secret value is no longer available." }
        String(characters)
    }

    override fun close() = synchronized(this) {
        if (!closed) {
            Arrays.fill(characters, '\u0000')
            characters = CharArray(0)
            closed = true
        }
    }

    override fun toString(): String = "<redacted>"

    companion object {
        internal fun from(value: String): SecretValue = SecretValue(value.toCharArray())
    }
}
