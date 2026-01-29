package com.v2ray.ang.handler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles periodic server testing and automatic server switching via WorkManager.
 *
 * Smart Connect: Tests servers in batches, connects to first working server immediately,
 * then continues testing and switches to better servers if found.
 *
 * Server Selection Strategy (for volatile networks like Iran):
 * - Uses time-decayed reliability scoring: old failures fade over 24-72 hours
 * - Reserves 15% of test budget for "exploration" (retesting failed/stale servers)
 * - Interleaves exploration servers throughout the test to benefit from early stopping
 */
object BackgroundServerTester {

    private const val NOTIFICATION_ID = 4
    private const val SWITCH_THRESHOLD = 0.8
    private const val MAX_PARALLELISM = 2
    private const val SMART_CONNECT_BATCH_SIZE = 10
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"
    private const val CACHE_VALIDITY_MS = 5 * 60 * 1000L  // 5 minutes

    // Adaptive threshold constants
    private const val KEY_LATENCY_HISTORY = "latency_history"
    private const val MAX_HISTORY_SIZE = 20
    private const val DEFAULT_GOOD_ENOUGH_LATENCY = 300L

    // Maximum servers to test in one Smart Connect session
    private const val MAX_SERVERS_TO_TEST = 100

    // Common country names mapping for region extraction
    private val COUNTRY_NAME_MAP = mapOf(
        "usa" to "US", "united states" to "US", "america" to "US",
        "japan" to "JP", "tokyo" to "JP",
        "hong kong" to "HK", "hongkong" to "HK",
        "singapore" to "SG",
        "korea" to "KR", "seoul" to "KR",
        "taiwan" to "TW", "taipei" to "TW",
        "germany" to "DE", "frankfurt" to "DE",
        "uk" to "GB", "london" to "GB", "britain" to "GB",
        "france" to "FR", "paris" to "FR",
        "canada" to "CA", "toronto" to "CA",
        "australia" to "AU", "sydney" to "AU",
        "netherlands" to "NL", "amsterdam" to "NL",
        "russia" to "RU", "moscow" to "RU",
        "india" to "IN", "mumbai" to "IN",
        "brazil" to "BR", "sao paulo" to "BR",
        "mexico" to "MX",
        "italy" to "IT", "milan" to "IT",
        "spain" to "ES", "madrid" to "ES",
        "turkey" to "TR", "istanbul" to "TR",
        "iran" to "IR", "tehran" to "IR",
        "china" to "CN", "shanghai" to "CN", "beijing" to "CN"
    )

    // Cache for test results to avoid re-testing on quick reconnects
    private data class CachedTestResults(
        val subscriptionId: String,
        val bestServer: TestResult?,
        val timestamp: Long
    )

    private var cachedResults: CachedTestResults? = null

    // Mutex to prevent concurrent server switches
    private val switchMutex = Mutex()

    // Flag to track if a switch operation is in progress
    private val isSwitching = AtomicBoolean(false)

    // Flag to allow cancellation of Smart Connect
    private val smartConnectCancelled = AtomicBoolean(false)

    /**
     * Server selector with exploration budget for volatile networks.
     *
     * Strategy:
     * - Exploitation (80-85%): Test known good servers first, sorted by decayed weighted score
     * - Exploration (15-20%): Retest failed/stale servers that might have recovered
     * - Interleave exploration servers throughout the list for early stopping benefit
     */
    private object ServerSelector {
        /**
         * Select servers for testing with exploration budget.
         * @param allServers All available server GUIDs
         * @param testBudget How many servers to test
         * @param explorationRatio Portion of budget for failed/stale servers (0.15 = 15%)
         * @return Ordered list of server GUIDs to test
         */
        fun selectServersForTesting(
            allServers: List<String>,
            testBudget: Int,
            explorationRatio: Float = AppConfig.DEFAULT_EXPLORATION_BUDGET
        ): List<String> {
            val explorationBudget = (testBudget * explorationRatio).toInt().coerceAtLeast(1)
            val exploitationBudget = testBudget - explorationBudget

            // Categorize servers
            val (knownGood, stale, failedOrUnknown) = categorizeServers(allServers)

            Log.d(AppConfig.TAG, "ServerSelector: knownGood=${knownGood.size}, stale=${stale.size}, " +
                    "failedOrUnknown=${failedOrUnknown.size}, exploitBudget=$exploitationBudget, exploreBudget=$explorationBudget")

            // Sort known good by decayed score (best first)
            val sortedGood = knownGood.sortedBy { guid ->
                MmkvManager.decodeServerAffiliationInfo(guid)?.getDecayedWeightedScore() ?: Double.MAX_VALUE
            }

            // Sort exploration candidates by staleness (oldest first - most likely to have changed)
            val explorationPool = (stale + failedOrUnknown).sortedByDescending { guid ->
                MmkvManager.decodeServerAffiliationInfo(guid)?.getStalenessScore() ?: Double.MAX_VALUE
            }

            // Take from each pool
            val exploitation = sortedGood.take(exploitationBudget)
            val exploration = explorationPool.take(explorationBudget)

            Log.d(AppConfig.TAG, "ServerSelector: Selected ${exploitation.size} exploitation + ${exploration.size} exploration servers")

            // Interleave exploration throughout the list
            return interleaveExploration(exploitation, exploration)
        }

        /**
         * Categorize servers into: knownGood, stale, failedOrUnknown
         */
        private fun categorizeServers(guids: List<String>): Triple<List<String>, List<String>, List<String>> {
            val knownGood = mutableListOf<String>()
            val stale = mutableListOf<String>()
            val failedOrUnknown = mutableListOf<String>()

            for (guid in guids) {
                val info = MmkvManager.decodeServerAffiliationInfo(guid)
                when {
                    // Never tested or very old
                    info == null || info.isUnknown() -> failedOrUnknown.add(guid)
                    // Last test failed
                    info.testDelayMillis <= 0 -> failedOrUnknown.add(guid)
                    // Successful but stale (>6h old)
                    info.isStale() -> stale.add(guid)
                    // Recent successful test
                    else -> knownGood.add(guid)
                }
            }
            return Triple(knownGood, stale, failedOrUnknown)
        }

        /**
         * Interleave exploration servers at regular intervals.
         * This ensures they get tested even with early stopping.
         *
         * Example: exploitation=[G1,G2,G3,G4,G5,G6], exploration=[E1,E2]
         * interval = 6 / (2+1) = 2
         * Result: [G1, G2, E1, G3, G4, E2, G5, G6]
         */
        private fun interleaveExploration(
            exploitation: List<String>,
            exploration: List<String>
        ): List<String> {
            if (exploration.isEmpty()) return exploitation
            if (exploitation.isEmpty()) return exploration

            val result = mutableListOf<String>()
            val interval = (exploitation.size / (exploration.size + 1)).coerceAtLeast(1)
            var exploreIndex = 0

            for ((i, server) in exploitation.withIndex()) {
                result.add(server)
                // Insert exploration server at regular intervals
                if ((i + 1) % interval == 0 && exploreIndex < exploration.size) {
                    result.add(exploration[exploreIndex++])
                }
            }
            // Add any remaining exploration servers at the end
            while (exploreIndex < exploration.size) {
                result.add(exploration[exploreIndex++])
            }
            return result
        }
    }

    data class TestResult(
        val guid: String,
        val remarks: String,
        val delay: Long,
        val score: Double = delay.toDouble()  // Default to latency, weighted by reliability
    )

    /**
     * Checks if there's a valid cached result for the given subscription.
     * @return The cached best server if cache is valid, null otherwise.
     */
    private fun getCachedBestServer(subscriptionId: String): TestResult? {
        val cache = cachedResults ?: return null
        if (cache.subscriptionId != subscriptionId) return null
        val ageMs = System.currentTimeMillis() - cache.timestamp
        if (ageMs > CACHE_VALIDITY_MS) return null
        Log.i(AppConfig.TAG, "Smart Connect: Using cached result (${ageMs}ms old)")
        return cache.bestServer
    }

    /**
     * Updates the cache with the latest test results.
     */
    private fun updateCache(subscriptionId: String, bestServer: TestResult?) {
        cachedResults = CachedTestResults(subscriptionId, bestServer, System.currentTimeMillis())
        Log.d(AppConfig.TAG, "Smart Connect: Cache updated with ${bestServer?.remarks ?: "no server"}")
    }

    /**
     * Clears the cached test results. Call this when servers are modified or subscription changes.
     */
    fun clearCache() {
        cachedResults = null
        Log.i(AppConfig.TAG, "Smart Connect cache cleared")
    }

    /**
     * Stores a successful latency measurement for adaptive threshold calculation.
     */
    private fun recordLatencyHistory(latencyMs: Long) {
        if (latencyMs <= 0) return

        val historyJson = MmkvManager.decodeSettingsString(KEY_LATENCY_HISTORY, "[]")
        val history = try {
            historyJson?.let {
                it.removeSurrounding("[", "]")
                    .split(",")
                    .filter { s -> s.isNotBlank() }
                    .map { s -> s.trim().toLong() }
                    .toMutableList()
            } ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf<Long>()
        }

        history.add(latencyMs)

        // Keep only last MAX_HISTORY_SIZE entries
        while (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
        }

        val newJson = history.joinToString(",", "[", "]")
        MmkvManager.encodeSettings(KEY_LATENCY_HISTORY, newJson)
        Log.d(AppConfig.TAG, "Smart Connect: Recorded latency ${latencyMs}ms, history size: ${history.size}")
    }

    /**
     * Calculates adaptive "good enough" threshold based on historical performance.
     * Uses 80th percentile of historical latencies, or default if insufficient data.
     */
    private fun getAdaptiveGoodEnoughThreshold(): Long {
        val historyJson = MmkvManager.decodeSettingsString(KEY_LATENCY_HISTORY, null)
        if (historyJson.isNullOrBlank()) {
            Log.d(AppConfig.TAG, "Smart Connect: No history, using default threshold ${DEFAULT_GOOD_ENOUGH_LATENCY}ms")
            return DEFAULT_GOOD_ENOUGH_LATENCY
        }

        val history = try {
            historyJson.removeSurrounding("[", "]")
                .split(",")
                .filter { it.isNotBlank() }
                .map { it.trim().toLong() }
                .filter { it > 0 }
                .sorted()
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Smart Connect: Failed to parse history", e)
            return DEFAULT_GOOD_ENOUGH_LATENCY
        }

        if (history.size < 3) {
            Log.d(AppConfig.TAG, "Smart Connect: Insufficient history (${history.size}), using default threshold")
            return DEFAULT_GOOD_ENOUGH_LATENCY
        }

        // Calculate 80th percentile
        val percentileIndex = (history.size * 0.8).toInt().coerceAtMost(history.size - 1)
        val threshold = history[percentileIndex]

        // Clamp threshold between 100ms and 1000ms
        val clampedThreshold = threshold.coerceIn(100L, 1000L)
        Log.d(AppConfig.TAG, "Smart Connect: Adaptive threshold ${clampedThreshold}ms (80th percentile of ${history.size} samples)")

        return clampedThreshold
    }

    /**
     * Extracts region code from server remarks.
     * Supports patterns: [US], (JP), -HK, _SG, US-, JP_, etc.
     */
    fun extractRegion(remarks: String): String? {
        // Pattern: [XX] or (XX) anywhere in the string
        val bracketPattern = Regex("""[\[\(]([A-Z]{2})[\]\)]""", RegexOption.IGNORE_CASE)
        bracketPattern.find(remarks)?.let { return it.groupValues[1].uppercase() }

        // Pattern: -XX or _XX at end (with optional numbers after)
        val suffixPattern = Regex("""[-_]([A-Z]{2})(?:\d*)?$""", RegexOption.IGNORE_CASE)
        suffixPattern.find(remarks)?.let { return it.groupValues[1].uppercase() }

        // Pattern: XX- or XX_ at start
        val prefixPattern = Regex("""^([A-Z]{2})[-_]""", RegexOption.IGNORE_CASE)
        prefixPattern.find(remarks)?.let { return it.groupValues[1].uppercase() }

        // Check for common country names
        val lowerRemarks = remarks.lowercase()
        for ((name, code) in COUNTRY_NAME_MAP) {
            if (lowerRemarks.contains(name)) return code
        }

        return null
    }

    /**
     * Sorts servers by region priority, putting preferred region first.
     * Servers in preferred region are shuffled among themselves, others are shuffled too.
     */
    private fun sortByRegionPriority(servers: List<String>, preferredRegion: String?): List<String> {
        if (preferredRegion == null) return servers.shuffled()

        val (preferred, others) = servers.partition { guid ->
            val config = MmkvManager.decodeServerConfig(guid) ?: return@partition false
            extractRegion(config.remarks) == preferredRegion
        }

        Log.d(AppConfig.TAG, "Smart Connect: Region priority - ${preferred.size} servers in $preferredRegion, ${others.size} others")
        return preferred.shuffled() + others.shuffled()
    }

    /**
     * Gets user's preferred region based on last successful connection.
     */
    private fun getPreferredRegion(): String? {
        return MmkvManager.decodeSettingsString(AppConfig.PREF_PREFERRED_REGION, null)
    }

    /**
     * Updates user's preferred region based on successful connection.
     */
    fun updatePreferredRegion(serverGuid: String) {
        val config = MmkvManager.decodeServerConfig(serverGuid) ?: return
        val region = extractRegion(config.remarks) ?: return
        MmkvManager.encodeSettings(AppConfig.PREF_PREFERRED_REGION, region)
        Log.d(AppConfig.TAG, "Smart Connect: Updated preferred region to $region")
    }

    data class SmartConnectResult(
        val firstWorking: TestResult?,
        val bestServer: TestResult?,
        val testedCount: Int,
        val totalCount: Int,
        val isComplete: Boolean
    )

    class TestAndSwitchTask(context: Context, params: WorkerParameters) :
        CoroutineWorker(context, params) {

        private val notificationManager = NotificationManagerCompat.from(applicationContext)

        override suspend fun doWork(): Result {
            Log.i(AppConfig.TAG, "Background server testing starting")

            createNotificationChannel()

            // Get subscription ID from input data, fallback to cached value
            val subscriptionId = inputData.getString(KEY_SUBSCRIPTION_ID)
                ?: MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "")
                ?: ""

            val allServers = MmkvManager.getServersBySubscriptionId(subscriptionId)
            if (allServers.isEmpty()) {
                Log.i(AppConfig.TAG, "No servers to test")
                return Result.success()
            }

            // Use intelligent server selection with exploration budget
            val testBudget = allServers.size.coerceAtMost(MAX_SERVERS_TO_TEST)
            val servers = ServerSelector.selectServersForTesting(allServers, testBudget)

            showNotificationIfAllowed(applicationContext.getString(R.string.smart_connect_testing))

            val bestResult = testServers(applicationContext, servers, "background")
                .filter { it.delay > 0 }
                .minByOrNull { it.score }

            if (bestResult != null) {
                Log.i(AppConfig.TAG, "Best server: ${bestResult.remarks} (${bestResult.delay}ms)")
                handleAutoSwitch(bestResult)
            } else {
                Log.w(AppConfig.TAG, "No working servers found")
            }

            cancelNotification()
            return Result.success()
        }

        private fun createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    AppConfig.AUTO_SWITCH_CHANNEL,
                    AppConfig.AUTO_SWITCH_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
                notificationManager.createNotificationChannel(channel)
            }
        }

        private fun buildNotification(): NotificationCompat.Builder {
            return NotificationCompat.Builder(applicationContext, AppConfig.AUTO_SWITCH_CHANNEL)
                .setWhen(0)
                .setTicker("Testing")
                .setContentTitle(applicationContext.getString(R.string.title_auto_server_test))
                .setSmallIcon(R.drawable.ic_stat_name)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        }

        private fun hasNotificationPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        private fun showNotificationIfAllowed(text: String) {
            if (!hasNotificationPermission()) {
                Log.d(AppConfig.TAG, "Notification permission not granted, skipping notification")
                return
            }
            try {
                notificationManager.notify(NOTIFICATION_ID, buildNotification().setContentText(text).build())
            } catch (e: SecurityException) {
                Log.w(AppConfig.TAG, "Notification permission denied", e)
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "Failed to show notification", e)
            }
        }

        private fun cancelNotification() {
            try {
                notificationManager.cancel(NOTIFICATION_ID)
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "Failed to cancel notification", e)
            }
        }

        private suspend fun handleAutoSwitch(bestResult: TestResult) {
            if (!isSwitching.compareAndSet(false, true)) {
                Log.i(AppConfig.TAG, "Switch already in progress, skipping")
                return
            }

            try {
                switchMutex.withLock {
                    val currentServer = MmkvManager.getSelectServer()
                    val currentConfig = currentServer?.let { MmkvManager.decodeServerConfig(it) }
                    val currentDelay = MmkvManager.decodeServerAffiliationInfo(currentServer ?: "")?.testDelayMillis ?: 0L

                    if (!shouldPerformSwitch(bestResult)) return@withLock

                    val switched = V2RayServiceManager.switchServer(applicationContext, bestResult.guid)
                    if (switched) {
                        // Calculate improvement percentage
                        val improvement = if (currentDelay > 0 && bestResult.delay > 0) {
                            ((currentDelay - bestResult.delay) * 100 / currentDelay).toInt()
                        } else {
                            0
                        }

                        val notificationText = if (currentConfig != null && currentDelay > 0 && improvement > 0) {
                            applicationContext.getString(
                                R.string.notification_server_switched_comparison,
                                currentConfig.remarks.ifEmpty { "Unknown" },
                                currentDelay,
                                bestResult.remarks,
                                bestResult.delay,
                                improvement
                            )
                        } else {
                            applicationContext.getString(
                                R.string.notification_server_switched,
                                bestResult.remarks,
                                bestResult.delay
                            )
                        }

                        showNotificationIfAllowed(notificationText)
                        Log.i(AppConfig.TAG, "Switched from ${currentConfig?.remarks} (${currentDelay}ms) to ${bestResult.remarks} (${bestResult.delay}ms) - ${improvement}% faster")
                    }
                }
            } finally {
                isSwitching.set(false)
            }
        }

        private fun shouldPerformSwitch(bestResult: TestResult): Boolean {
            val autoSwitchEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SERVER_SWITCH_ENABLED, true)
            val currentServer = MmkvManager.getSelectServer()

            if (!autoSwitchEnabled || !V2RayServiceManager.isRunning() || bestResult.guid == currentServer) {
                return false
            }

            val currentDelay = MmkvManager.decodeServerAffiliationInfo(currentServer ?: "")?.testDelayMillis ?: Long.MAX_VALUE
            val shouldSwitch = currentDelay <= 0 || bestResult.delay < currentDelay * SWITCH_THRESHOLD

            if (!shouldSwitch) {
                Log.i(AppConfig.TAG, "Current server is good enough, not switching")
            }
            return shouldSwitch
        }
    }

    /**
     * Tests all servers with limited parallelism for battery efficiency.
     */
    suspend fun testServers(
        context: Context,
        serverGuids: List<String>,
        testSource: String = "manual"
    ): List<TestResult> = coroutineScope {
        val appContext = context.applicationContext
        val testUrl = SettingsManager.getDelayTestUrl()

        serverGuids.chunked(MAX_PARALLELISM).flatMap { chunk ->
            chunk.map { guid ->
                async(Dispatchers.IO) {
                    testSingleServer(appContext, guid, testUrl, testSource)
                }
            }.awaitAll()
        }.filterNotNull()
    }

    private suspend fun testSingleServer(
        context: Context,
        guid: String,
        testUrl: String,
        testSource: String
    ): TestResult? = withContext(Dispatchers.IO) {
        val config = MmkvManager.decodeServerConfig(guid) ?: return@withContext null
        val remarks = config.remarks.ifEmpty { guid }

        try {
            val result = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!result.status) {
                Log.d(AppConfig.TAG, "Failed to get config for server $guid: ${result.content}")
                return@withContext recordFailure(guid, remarks, testSource)
            }

            val delay = V2RayNativeManager.measureOutboundDelay(result.content, testUrl)
            MmkvManager.encodeServerTestDelayMillis(guid, delay, testSource)
            val affiliationInfo = MmkvManager.decodeServerAffiliationInfo(guid)
            // Use time-decayed scoring: old failures have less impact
            val score = affiliationInfo?.getDecayedWeightedScore() ?: delay.toDouble()
            TestResult(guid, remarks, delay, score)
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(AppConfig.TAG, "Server $guid timed out")
            recordFailure(guid, remarks, testSource)
        } catch (e: java.io.IOException) {
            Log.d(AppConfig.TAG, "Network error testing server $guid: ${e.message}")
            recordFailure(guid, remarks, testSource)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Unexpected error testing server $guid", e)
            recordFailure(guid, remarks, testSource)
        }
    }

    private fun recordFailure(guid: String, remarks: String, testSource: String): TestResult {
        MmkvManager.encodeServerTestDelayMillis(guid, -1L, testSource)
        val affiliationInfo = MmkvManager.decodeServerAffiliationInfo(guid)
        // Use time-decayed scoring: old failures have less impact
        val score = affiliationInfo?.getDecayedWeightedScore() ?: Double.MAX_VALUE
        return TestResult(guid, remarks, -1L, score)
    }

    /**
     * Tests servers and returns the best one. Consider smartConnectWithCallbacks for better UX.
     * Uses cached results if available and valid (<5 minutes old).
     */
    suspend fun testAndFindBest(context: Context, subscriptionId: String): TestResult? {
        // Check cache first
        getCachedBestServer(subscriptionId)?.let { cachedServer ->
            return cachedServer
        }

        val servers = MmkvManager.getServersBySubscriptionId(subscriptionId)
        if (servers.isEmpty()) return null

        val bestServer = testServers(context.applicationContext, servers, "smart_connect")
            .filter { it.delay > 0 }
            .minByOrNull { it.score }

        // Update cache with results
        updateCache(subscriptionId, bestServer)

        return bestServer
    }

    /**
     * Smart Connect: Tests servers in batches, connects to first working server immediately,
     * continues testing for better servers, stops early if good enough server found.
     * Uses cached results if available and valid (<5 minutes old).
     */
    suspend fun smartConnectWithCallbacks(
        context: Context,
        subscriptionId: String,
        onProgress: (tested: Int, total: Int) -> Unit = { _, _ -> },
        onFirstWorking: (TestResult) -> Unit,
        onBetterFound: (TestResult) -> Unit = {},
        onComplete: (bestServer: TestResult?) -> Unit = {}
    ) {
        val appContext = context.applicationContext
        smartConnectCancelled.set(false)

        // Check cache first - if valid, use cached result immediately
        getCachedBestServer(subscriptionId)?.let { cachedServer ->
            Log.i(AppConfig.TAG, "Smart Connect: Using cached server: ${cachedServer.remarks} (${cachedServer.delay}ms)")
            onFirstWorking(cachedServer)
            onComplete(cachedServer)
            return
        }

        // Get all servers for this subscription
        val allServers = MmkvManager.getServersBySubscriptionId(subscriptionId)
        if (allServers.isEmpty()) {
            onComplete(null)
            return
        }

        // Apply region priority first
        val preferredRegion = getPreferredRegion()
        val regionSortedServers = sortByRegionPriority(allServers, preferredRegion)

        // Apply intelligent server selection with exploration budget
        // This ensures failed/stale servers get retested periodically
        val testBudget = regionSortedServers.size.coerceAtMost(MAX_SERVERS_TO_TEST)
        val servers = ServerSelector.selectServersForTesting(
            allServers = regionSortedServers,
            testBudget = testBudget
        )

        val totalCount = servers.size
        val testUrl = SettingsManager.getDelayTestUrl()
        val bestServer = AtomicReference<TestResult?>(null)
        var firstWorkingFound = false
        var testedCount = 0

        Log.i(AppConfig.TAG, "Smart Connect: Starting test of $totalCount servers (from ${allServers.size} total) with preferred region: $preferredRegion")

        // Process servers in batches
        for (batch in servers.chunked(SMART_CONNECT_BATCH_SIZE)) {
            if (smartConnectCancelled.get()) {
                Log.i(AppConfig.TAG, "Smart Connect: Cancelled")
                break
            }

            // Test batch in parallel
            val batchResults = coroutineScope {
                batch.map { guid ->
                    async(Dispatchers.IO) {
                        testSingleServer(appContext, guid, testUrl, "smart_connect")
                    }
                }.awaitAll()
            }.filterNotNull()

            testedCount += batch.size
            onProgress(testedCount, totalCount)

            // Check results from this batch
            for (result in batchResults) {
                if (result.delay <= 0) continue // Skip failed servers

                val currentBest = bestServer.get()

                // First working server found - connect immediately!
                if (!firstWorkingFound) {
                    firstWorkingFound = true
                    bestServer.set(result)
                    Log.i(AppConfig.TAG, "Smart Connect: First working server found: ${result.remarks} (${result.delay}ms)")
                    onFirstWorking(result)
                    recordLatencyHistory(result.delay)

                    // If this server is "good enough", stop testing
                    val goodEnoughThreshold = getAdaptiveGoodEnoughThreshold()
                    if (result.delay <= goodEnoughThreshold) {
                        Log.i(AppConfig.TAG, "Smart Connect: Good enough server found (${result.delay}ms <= ${goodEnoughThreshold}ms threshold), stopping early")
                        updateCache(subscriptionId, result)
                        updatePreferredRegion(result.guid)
                        onComplete(result)
                        return
                    }
                } else if (currentBest != null && result.score < currentBest.score * SWITCH_THRESHOLD) {
                    // Found a significantly better server (by weighted score)
                    bestServer.set(result)
                    Log.i(AppConfig.TAG, "Smart Connect: Better server found: ${result.remarks} (${result.delay}ms vs ${currentBest.delay}ms)")
                    onBetterFound(result)
                    recordLatencyHistory(result.delay)

                    // If this server is "good enough" by latency, stop testing (user cares about perceived latency)
                    val goodEnoughThreshold = getAdaptiveGoodEnoughThreshold()
                    if (result.delay <= goodEnoughThreshold) {
                        Log.i(AppConfig.TAG, "Smart Connect: Good enough server found (${result.delay}ms <= ${goodEnoughThreshold}ms threshold), stopping early")
                        updateCache(subscriptionId, result)
                        updatePreferredRegion(result.guid)
                        onComplete(result)
                        return
                    }
                } else if (currentBest == null || result.score < currentBest.score) {
                    // Better by score but not significantly - just update tracking
                    bestServer.set(result)
                }
            }
        }

        val finalBest = bestServer.get()
        Log.i(AppConfig.TAG, "Smart Connect: Complete. Best server: ${finalBest?.remarks} (${finalBest?.delay}ms)")
        updateCache(subscriptionId, finalBest)
        finalBest?.let { updatePreferredRegion(it.guid) }
        onComplete(finalBest)
    }

    fun cancelSmartConnect() {
        smartConnectCancelled.set(true)
        Log.i(AppConfig.TAG, "Smart Connect: Cancel requested")
    }

    fun schedulePeriodicTest(context: Context, intervalMinutes: Long) {
        val rw = RemoteWorkManager.getInstance(context.applicationContext)
        rw.cancelUniqueWork(AppConfig.AUTO_SERVER_TEST_TASK_NAME)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        rw.enqueueUniquePeriodicWork(
            AppConfig.AUTO_SERVER_TEST_TASK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(TestAndSwitchTask::class.java, intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
                .build()
        )
        Log.i(AppConfig.TAG, "Scheduled background server testing every $intervalMinutes minutes")
    }

    fun cancelPeriodicTest(context: Context) {
        RemoteWorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(AppConfig.AUTO_SERVER_TEST_TASK_NAME)
        Log.i(AppConfig.TAG, "Cancelled background server testing")
    }

    fun triggerImmediateTest(context: Context, subscriptionId: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(KEY_SUBSCRIPTION_ID, subscriptionId)
            .build()

        RemoteWorkManager.getInstance(context.applicationContext).enqueue(
            OneTimeWorkRequest.Builder(TestAndSwitchTask::class.java)
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()
        )
        Log.i(AppConfig.TAG, "Triggered immediate server test for subscription: $subscriptionId")
    }
}
