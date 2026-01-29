package com.v2ray.ang.dto

import com.v2ray.ang.AppConfig
import kotlin.math.pow

/**
 * Server affiliation information including test results, reliability metrics,
 * and time-decayed scoring for intelligent server ranking.
 *
 * The time-decay algorithm allows servers that failed in the past to gradually
 * recover their reputation, enabling re-discovery when network conditions change
 * (especially important for volatile networks like Iran's GFW).
 */
data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var lastTestTime: Long = 0L,
    var testSource: String = "",
    var successCount: Int = 0,
    var failureCount: Int = 0,

    // Time-stamped events for decay calculation
    // Each timestamp represents when a success/failure occurred
    var recentSuccesses: MutableList<Long> = mutableListOf(),
    var recentFailures: MutableList<Long> = mutableListOf()
) {
    fun getTestDelayString(): String {
        if (testDelayMillis == 0L) {
            return ""
        }
        return testDelayMillis.toString() + "ms"
    }

    /**
     * Calculate reliability score based on historical test results.
     * @return A value between 0.0 (always fails) and 1.0 (always succeeds).
     *         Returns 1.0 if no history exists (neutral).
     */
    fun getReliabilityScore(): Double {
        val total = successCount + failureCount
        if (total == 0) return 1.0  // No history = neutral
        return successCount.toDouble() / total
    }

    /**
     * Calculate weighted score: latency adjusted by reliability.
     * Lower is better. Failed servers or unreliable servers get penalized.
     * @return The weighted score, or Double.MAX_VALUE for failed servers.
     */
    fun getWeightedScore(): Double {
        if (testDelayMillis <= 0) return Double.MAX_VALUE  // Failed = worst
        val reliability = getReliabilityScore()
        // Penalize unreliable servers: score = latency * (1 + failRate)
        return testDelayMillis * (2.0 - reliability)
    }

    /**
     * Calculate time-decayed reliability score.
     * Recent events have more weight; old failures naturally fade.
     *
     * Formula: effectiveReliability = sum(successes * decay^age) / sum(all_events * decay^age)
     * where decay = 0.5^(hours_since_event / halfLifeHours)
     *
     * This allows servers that failed days ago to get another chance,
     * while recent failures are still heavily penalized.
     *
     * @return A value between 0.0 and 1.0, or 1.0 if insufficient history.
     */
    fun getDecayedReliabilityScore(): Double {
        val now = System.currentTimeMillis()
        val halfLifeMs = AppConfig.DECAY_HALF_LIFE_HOURS * 3600 * 1000L

        fun decayedSum(timestamps: List<Long>): Double {
            return timestamps.sumOf { ts ->
                val ageMs = (now - ts).coerceAtLeast(0)
                0.5.pow(ageMs.toDouble() / halfLifeMs)
            }
        }

        val decayedSuccesses = decayedSum(recentSuccesses)
        val decayedFailures = decayedSum(recentFailures)
        val total = decayedSuccesses + decayedFailures

        // Not enough meaningful history - return neutral
        if (total < 0.1) return 1.0
        return decayedSuccesses / total
    }

    /**
     * Calculate time-decayed weighted score: latency adjusted by decayed reliability.
     * Lower is better. Uses the same formula as getWeightedScore() but with decay.
     *
     * @return The decayed weighted score, or Double.MAX_VALUE for failed servers.
     */
    fun getDecayedWeightedScore(): Double {
        if (testDelayMillis <= 0) return Double.MAX_VALUE  // Failed = worst
        val reliability = getDecayedReliabilityScore()
        // Same penalty formula: score = latency * (2 - reliability)
        // reliability = 1.0 -> multiplier = 1.0 (no penalty)
        // reliability = 0.5 -> multiplier = 1.5 (50% penalty)
        // reliability = 0.0 -> multiplier = 2.0 (double penalty)
        return testDelayMillis * (2.0 - reliability)
    }

    /**
     * Check if test results are stale (older than threshold).
     * Stale servers should be prioritized for re-testing.
     *
     * @return true if lastTestTime is older than STALE_THRESHOLD_HOURS, or never tested.
     */
    fun isStale(): Boolean {
        if (lastTestTime == 0L) return true
        val ageHours = (System.currentTimeMillis() - lastTestTime) / (3600 * 1000)
        return ageHours >= AppConfig.STALE_THRESHOLD_HOURS
    }

    /**
     * Check if test results are unknown/very old (need priority retesting).
     * Unknown servers are the oldest stale servers - highest exploration priority.
     *
     * @return true if lastTestTime is older than UNKNOWN_THRESHOLD_HOURS, or never tested.
     */
    fun isUnknown(): Boolean {
        if (lastTestTime == 0L) return true
        val ageHours = (System.currentTimeMillis() - lastTestTime) / (3600 * 1000)
        return ageHours >= AppConfig.UNKNOWN_THRESHOLD_HOURS
    }

    /**
     * Get staleness score in hours since last test.
     * Higher = more stale = should be tested sooner in exploration phase.
     *
     * @return Hours since last test, or Double.MAX_VALUE if never tested.
     */
    fun getStalenessScore(): Double {
        if (lastTestTime == 0L) return Double.MAX_VALUE
        return (System.currentTimeMillis() - lastTestTime) / (3600.0 * 1000)
    }

    /**
     * Record a test result with timestamp for time-decay calculation.
     * Also updates the legacy successCount/failureCount for backward compatibility.
     * Automatically cleans up events older than EVENT_EXPIRY_DAYS.
     *
     * @param success true if the test succeeded (delay > 0), false otherwise.
     */
    fun recordResult(success: Boolean) {
        val now = System.currentTimeMillis()
        val cutoff = now - (AppConfig.EVENT_EXPIRY_DAYS * 24 * 3600 * 1000L)

        if (success) {
            recentSuccesses.add(now)
            successCount++
            // Trim to max size (remove oldest first)
            while (recentSuccesses.size > AppConfig.MAX_RECENT_EVENTS) {
                recentSuccesses.removeAt(0)
            }
            // Remove expired events
            recentSuccesses.removeAll { it < cutoff }
        } else {
            recentFailures.add(now)
            failureCount++
            while (recentFailures.size > AppConfig.MAX_RECENT_EVENTS) {
                recentFailures.removeAt(0)
            }
            recentFailures.removeAll { it < cutoff }
        }
    }
}
