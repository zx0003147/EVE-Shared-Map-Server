package dev.evesharedmap.server.security

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

data class RateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
)

interface RateLimiter {
    fun consume(key: String, capacity: Int, period: Duration): RateLimitDecision
}

class InMemoryTokenBucketRateLimiter(
    private val clock: Clock = Clock.systemUTC(),
) : RateLimiter {
    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun consume(key: String, capacity: Int, period: Duration): RateLimitDecision {
        require(capacity > 0 && !period.isZero && !period.isNegative)
        val now = clock.instant()
        val bucket = buckets.computeIfAbsent(key) { Bucket(capacity.toDouble(), now) }
        return synchronized(bucket) {
            val elapsedNanos = Duration.between(bucket.lastRefill, now).toNanos().coerceAtLeast(0)
            val refill = elapsedNanos.toDouble() * capacity.toDouble() / period.toNanos().toDouble()
            bucket.tokens = min(capacity.toDouble(), bucket.tokens + refill)
            bucket.lastRefill = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                RateLimitDecision(true)
            } else {
                val secondsPerToken = period.toMillis().toDouble() / capacity.toDouble() / 1000.0
                RateLimitDecision(false, ceil((1.0 - bucket.tokens) * secondsPerToken).toLong().coerceAtLeast(1))
            }
        }
    }

    private data class Bucket(
        var tokens: Double,
        var lastRefill: Instant,
    )
}

object RateLimits {
    val MINUTE: Duration = Duration.ofMinutes(1)
    val DAY: Duration = Duration.ofDays(1)
    const val INVITE_EXCHANGE_PER_MINUTE = 5
    const val INVITE_EXCHANGE_PER_DAY = 20
    const val AUTHENTICATED_READS_PER_MINUTE = 120
    const val MARKER_WRITES_PER_MINUTE = 30
    const val ADMIN_WRITES_PER_MINUTE = 20
    const val PUBLIC_READS_PER_MINUTE = 60
}
