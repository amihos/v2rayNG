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

/**
 * Handles periodic server testing and automatic server switching.
 * Uses WorkManager (RemoteWorkManager for multi-process support) for reliable background execution.
 */
object BackgroundServerTester {

    // Notification ID for auto-switch notifications (4 to avoid collision with VPN=1, subscription=3)
    private const val NOTIFICATION_ID = 4

    // Switch threshold: switch if new server is 20% faster than current
    private const val SWITCH_THRESHOLD = 0.8

    // Max parallelism for battery efficiency (lower = less battery drain)
    private const val MAX_PARALLELISM = 2

    // Input data key for subscription ID
    private const val KEY_SUBSCRIPTION_ID = "subscription_id"

    // Mutex to prevent concurrent server switches
    private val switchMutex = Mutex()

    // Flag to track if a switch operation is in progress
    private val isSwitching = AtomicBoolean(false)

    data class TestResult(
        val guid: String,
        val remarks: String,
        val delay: Long
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
     * Tests servers and returns the best one (for Smart Connect).
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
