package dev.evesharedmap.server.security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RateLimiterTest {
    @Test
    fun `token bucket is deterministic and refills`() {
        val clock = MutableClock(Instant.parse("2026-09-01T00:00:00Z"))
        val limiter = InMemoryTokenBucketRateLimiter(clock)

        repeat(5) { assertTrue(limiter.consume("invite", 5, Duration.ofMinutes(1)).allowed) }
        assertFalse(limiter.consume("invite", 5, Duration.ofMinutes(1)).allowed)

        clock.now = clock.now.plusSeconds(12)
        assertTrue(limiter.consume("invite", 5, Duration.ofMinutes(1)).allowed)
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
}
