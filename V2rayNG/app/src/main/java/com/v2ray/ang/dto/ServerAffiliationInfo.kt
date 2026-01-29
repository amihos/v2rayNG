package com.v2ray.ang.dto

data class ServerAffiliationInfo(
    var testDelayMillis: Long = 0L,
    var lastTestTime: Long = 0L,
    var testSource: String = "",
    var successCount: Int = 0,
    var failureCount: Int = 0
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
}
