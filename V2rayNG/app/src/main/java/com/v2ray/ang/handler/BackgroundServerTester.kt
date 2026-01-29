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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles periodic server testing and automatic server switching.
 * Uses WorkManager (RemoteWorkManager for multi-process support) for reliable background execution.
 *
 * Smart Connect Algorithm:
 * 1. Test servers in batches (BATCH_SIZE at a time)
 * 2. Connect user to FIRST working server immediately
 * 3. Continue testing remaining servers in background
 * 4. Switch to better server if found (with threshold)
 */
object BackgroundServerTester {

    // Notification ID for auto-switch notifications (4 to avoid collision with VPN=1, subscription=3)
    private const val NOTIFICATION_ID = 4

    // Switch threshold: switch if new server is 20% faster than current
    private const val SWITCH_THRESHOLD = 0.8

    // Max parallelism for battery efficiency (lower = less battery drain)
    private const val MAX_PARALLELISM = 2

    // Batch size for Smart Connect - test this many servers before checking for results
    private const val SMART_CONNECT_BATCH_SIZE = 10

    // "Good enough" latency threshold - stop searching if we find a server this fast
    private const val GOOD_ENOUGH_LATENCY_MS = 300L

    // Input data key for subscription ID
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"

    // Mutex to prevent concurrent server switches
    private val switchMutex = Mutex()

    // Flag to track if a switch operation is in progress
    private val isSwitching = AtomicBoolean(false)

    // Flag to allow cancellation of Smart Connect
    private val smartConnectCancelled = AtomicBoolean(false)

    data class TestResult(
        val guid: String,
        val remarks: String,
        val delay: Long
    )

    /**
     * Result holder for Smart Connect with progress tracking.
     */
    data class SmartConnectResult(
        val firstWorking: TestResult?,      // First working server (for immediate connection)
        val bestServer: TestResult?,        // Best server found overall
        val testedCount: Int,               // Number of servers tested
        val totalCount: Int,                // Total servers to test
        val isComplete: Boolean             // Whether all testing is done
    )

    /**
     * WorkManager task for background server testing and auto-switching.
     */
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

            val servers = MmkvManager.getServersBySubscriptionId(subscriptionId)
            if (servers.isEmpty()) {
                Log.i(AppConfig.TAG, "No servers to test")
                return Result.success()
            }

            showNotificationIfAllowed(applicationContext.getString(R.string.smart_connect_testing))

            val bestResult = testServers(applicationContext, servers, "background")
                .filter { it.delay > 0 }
                .minByOrNull { it.delay }

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

        /**
         * Checks if notification permission is granted (required for Android 13+).
         */
        private fun hasNotificationPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true // Permission not required before Android 13
            }
        }

        /**
         * Shows notification only if permission is granted.
         */
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

        /**
         * Handles auto-switching to a better server with synchronization to prevent race conditions.
         */
        private suspend fun handleAutoSwitch(bestResult: TestResult) {
            // Prevent concurrent switch operations
            if (!isSwitching.compareAndSet(false, true)) {
                Log.i(AppConfig.TAG, "Switch already in progress, skipping")
                return
            }

            try {
                switchMutex.withLock {
                    val autoSwitchEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SERVER_SWITCH_ENABLED, true)
                    // Re-check current server inside the lock to prevent race condition
                    val currentServer = MmkvManager.getSelectServer()

                    if (!autoSwitchEnabled || !V2RayServiceManager.isRunning() || bestResult.guid == currentServer) {
                        return@withLock
                    }

                    val currentDelay = MmkvManager.decodeServerAffiliationInfo(currentServer ?: "")?.testDelayMillis ?: Long.MAX_VALUE
                    val shouldSwitch = currentDelay <= 0 || bestResult.delay < currentDelay * SWITCH_THRESHOLD

                    if (!shouldSwitch) {
                        Log.i(AppConfig.TAG, "Current server is good enough, not switching")
                        return@withLock
                    }

                    val switched = V2RayServiceManager.switchServer(applicationContext, bestResult.guid)
                    if (switched) {
                        showNotificationIfAllowed(
                            applicationContext.getString(
                                R.string.notification_server_switched,
                                bestResult.remarks,
                                bestResult.delay
                            )
                        )
                        Log.i(AppConfig.TAG, "Switched to ${bestResult.remarks}")
                    }
                }
            } finally {
                isSwitching.set(false)
            }
        }
    }

    /**
     * Tests all servers and returns results.
     *
     * @param context Application context (should be applicationContext to avoid leaks).
     * @param serverGuids The list of server GUIDs to test.
     * @param testSource The source of the test (manual, background, post-sub, smart_connect).
     * @return A list of test results.
     */
    suspend fun testServers(
        context: Context,
        serverGuids: List<String>,
        testSource: String = "manual"
    ): List<TestResult> = coroutineScope {
        val appContext = context.applicationContext
        val testUrl = SettingsManager.getDelayTestUrl()

        // Use limited parallelism (MAX_PARALLELISM) to be battery-efficient
        serverGuids.chunked(MAX_PARALLELISM).flatMap { chunk ->
            chunk.map { guid ->
                async(Dispatchers.IO) {
                    testSingleServer(appContext, guid, testUrl, testSource)
                }
            }.awaitAll()
        }.filterNotNull()
    }

    /**
     * Tests a single server and returns the result.
     *
     * @param context Application context.
     * @param guid Server GUID to test.
     * @param testUrl URL to use for delay measurement.
     * @param testSource Source identifier for logging/tracking.
     * @return TestResult or null if server config not found.
     */
    private suspend fun testSingleServer(
        context: Context,
        guid: String,
        testUrl: String,
        testSource: String
    ): TestResult? = withContext(Dispatchers.IO) {
        try {
            val config = MmkvManager.decodeServerConfig(guid) ?: return@withContext null
            val remarks = config.remarks ?: guid

            // Get the speedtest config
            val result = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
            if (!result.status) {
                Log.d(AppConfig.TAG, "Failed to get config for server $guid: ${result.content}")
                MmkvManager.encodeServerTestDelayMillis(guid, -1L, testSource)
                return@withContext TestResult(guid, remarks, -1L)
            }

            // Measure delay using native library
            val delay = V2RayNativeManager.measureOutboundDelay(result.content, testUrl)
            MmkvManager.encodeServerTestDelayMillis(guid, delay, testSource)

            TestResult(guid, remarks, delay)
        } catch (e: java.net.SocketTimeoutException) {
            Log.d(AppConfig.TAG, "Server $guid timed out")
            MmkvManager.encodeServerTestDelayMillis(guid, -1L, testSource)
            null
        } catch (e: java.io.IOException) {
            Log.d(AppConfig.TAG, "Network error testing server $guid: ${e.message}")
            MmkvManager.encodeServerTestDelayMillis(guid, -1L, testSource)
            null
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Unexpected error testing server $guid", e)
            MmkvManager.encodeServerTestDelayMillis(guid, -1L, testSource)
            null
        }
    }

    /**
     * Tests servers and returns the best one (for Smart Connect) - legacy method.
     * Consider using smartConnectWithCallbacks for better UX.
     *
     * @param context Context (will use applicationContext internally).
     * @param subscriptionId The subscription ID (empty for all servers).
     * @return The best server result, or null if none found.
     */
    suspend fun testAndFindBest(context: Context, subscriptionId: String): TestResult? {
        val appContext = context.applicationContext
        val servers = MmkvManager.getServersBySubscriptionId(subscriptionId)
        if (servers.isEmpty()) return null

        val results = testServers(appContext, servers, "smart_connect")
        return results.filter { it.delay > 0 }.minByOrNull { it.delay }
    }

    /**
     * Smart Connect with callbacks - connects user quickly then optimizes in background.
     *
     * Algorithm:
     * 1. Shuffle servers to avoid always testing same ones first
     * 2. Test in batches of SMART_CONNECT_BATCH_SIZE
     * 3. Call onFirstWorking as soon as ANY working server is found
     * 4. Continue testing remaining servers
     * 5. Call onBetterFound if a significantly better server is discovered
     * 6. Stop early if a "good enough" server is found (< GOOD_ENOUGH_LATENCY_MS)
     *
     * @param context Application context.
     * @param subscriptionId Subscription ID (empty for all servers).
     * @param onProgress Called with progress updates (tested/total).
     * @param onFirstWorking Called when first working server found - connect user immediately!
     * @param onBetterFound Called if a better server is found during continued testing.
     * @param onComplete Called when all testing is complete.
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

        val servers = MmkvManager.getServersBySubscriptionId(subscriptionId).shuffled()
        if (servers.isEmpty()) {
            onComplete(null)
            return
        }

        val totalCount = servers.size
        val testUrl = SettingsManager.getDelayTestUrl()
        val bestServer = AtomicReference<TestResult?>(null)
        var firstWorkingFound = false
        var testedCount = 0

        Log.i(AppConfig.TAG, "Smart Connect: Starting test of $totalCount servers")

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

                    // If this server is "good enough", stop testing
                    if (result.delay <= GOOD_ENOUGH_LATENCY_MS) {
                        Log.i(AppConfig.TAG, "Smart Connect: Good enough server found, stopping early")
                        onComplete(result)
                        return
                    }
                } else if (currentBest != null && result.delay < currentBest.delay * SWITCH_THRESHOLD) {
                    // Found a significantly better server
                    bestServer.set(result)
                    Log.i(AppConfig.TAG, "Smart Connect: Better server found: ${result.remarks} (${result.delay}ms vs ${currentBest.delay}ms)")
                    onBetterFound(result)

                    // If this server is "good enough", stop testing
                    if (result.delay <= GOOD_ENOUGH_LATENCY_MS) {
                        Log.i(AppConfig.TAG, "Smart Connect: Good enough server found, stopping early")
                        onComplete(result)
                        return
                    }
                } else if (currentBest == null || result.delay < currentBest.delay) {
                    // Better but not significantly - just update tracking
                    bestServer.set(result)
                }
            }
        }

        val finalBest = bestServer.get()
        Log.i(AppConfig.TAG, "Smart Connect: Complete. Best server: ${finalBest?.remarks} (${finalBest?.delay}ms)")
        onComplete(finalBest)
    }

    /**
     * Cancels any ongoing Smart Connect operation.
     */
    fun cancelSmartConnect() {
        smartConnectCancelled.set(true)
        Log.i(AppConfig.TAG, "Smart Connect: Cancel requested")
    }

    /**
     * Schedules periodic server testing.
     *
     * @param context Context (will use applicationContext).
     * @param intervalMinutes The interval in minutes (minimum 15 for WorkManager).
     */
    fun schedulePeriodicTest(context: Context, intervalMinutes: Long) {
        val appContext = context.applicationContext
        val rw = RemoteWorkManager.getInstance(appContext)
        rw.cancelUniqueWork(AppConfig.AUTO_SERVER_TEST_TASK_NAME)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        rw.enqueueUniquePeriodicWork(
            AppConfig.AUTO_SERVER_TEST_TASK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(
                TestAndSwitchTask::class.java,
                intervalMinutes,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
                .build()
        )
        Log.i(AppConfig.TAG, "Scheduled background server testing every $intervalMinutes minutes")
    }

    /**
     * Cancels periodic server testing.
     *
     * @param context Context (will use applicationContext).
     */
    fun cancelPeriodicTest(context: Context) {
        val appContext = context.applicationContext
        val rw = RemoteWorkManager.getInstance(appContext)
        rw.cancelUniqueWork(AppConfig.AUTO_SERVER_TEST_TASK_NAME)
        Log.i(AppConfig.TAG, "Cancelled background server testing")
    }

    /**
     * Triggers an immediate test (e.g., after subscription update).
     *
     * @param context Context (will use applicationContext).
     * @param subscriptionId The subscription ID to test (passed to worker via input data).
     */
    fun triggerImmediateTest(context: Context, subscriptionId: String) {
        val appContext = context.applicationContext
        val rw = RemoteWorkManager.getInstance(appContext)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val inputData = Data.Builder()
            .putString(KEY_SUBSCRIPTION_ID, subscriptionId)
            .build()

        rw.enqueue(
            OneTimeWorkRequest.Builder(TestAndSwitchTask::class.java)
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()
        )
        Log.i(AppConfig.TAG, "Triggered immediate server test for subscription: $subscriptionId")
    }
}
