package fr.podcast.server.auth

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Limits password guessing (ADR 0009): after [maxFailures] failed sign-ins for one username from
 * one address within [window], that pair is refused until the oldest failure leaves the window.
 * Kept in memory: the server runs as a single instance, and a restart only clears the counts.
 */
class LoginThrottle(
    private val maxFailures: Int = 5,
    private val window: Duration = 15.minutes,
    private val clock: Clock = Clock.System,
) {
    private val failures = ConcurrentHashMap<String, List<Instant>>()

    /** Seconds to wait before the next attempt, or null when an attempt is allowed. */
    fun retryAfterSeconds(username: String, address: String): Long? {
        val recent = recent(key(username, address))
        if (recent.size < maxFailures) return null
        return ((recent.first() + window) - clock.now()).inWholeSeconds.coerceAtLeast(1)
    }

    fun failed(username: String, address: String) {
        failures.compute(key(username, address)) { _, previous ->
            (previous.orEmpty().filter { it > clock.now() - window } + clock.now()).takeLast(maxFailures)
        }
    }

    fun succeeded(username: String, address: String) {
        failures.remove(key(username, address))
    }

    private fun recent(key: String): List<Instant> = failures[key].orEmpty().filter { it > clock.now() - window }

    private fun key(username: String, address: String) = "${username.trim().lowercase()}|$address"
}
