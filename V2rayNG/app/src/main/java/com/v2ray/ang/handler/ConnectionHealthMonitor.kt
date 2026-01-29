package com.v2ray.ang.handler

import android.content.Context
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors connection health and triggers automatic server switching when degraded.
 *
 * Checks connection every 30 seconds when VPN is running.
 * If latency spikes >2x baseline or connection fails, triggers background test.
 */
object ConnectionHealthMonitor {

    private const val CHECK_INTERVAL_MS = 30_000L  // 30 seconds
    private const val DEGRADATION_THRESHOLD = 2.0   // 2x baseline = degraded
    private const val MIN_CHECKS_FOR_BASELINE = 3   // Need 3 checks to establish baseline

    private var monitorJob: Job? = null
    private val isMonitoring = AtomicBoolean(false)
    private val baselineLatency = AtomicLong(0L)
    private var checkCount = 0
    private var latencySum = 0L

    /**
     * Start monitoring connection health.
     * Call this when VPN connects.
     */
    fun startMonitoring(context: Context) {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SERVER_TEST_ENABLED, true)) {
            Log.d(AppConfig.TAG, "Health monitoring disabled (auto-test is off)")
            return
        }

        if (isMonitoring.getAndSet(true)) {
            Log.d(AppConfig.TAG, "Health monitor already running")
            return
        }

        resetBaseline()

        monitorJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            Log.i(AppConfig.TAG, "Connection health monitor started")

            while (isActive && V2RayServiceManager.isRunning()) {
                delay(CHECK_INTERVAL_MS)

                if (!V2RayServiceManager.isRunning()) break

                checkConnectionHealth(context)
            }

            Log.i(AppConfig.TAG, "Connection health monitor stopped")
            isMonitoring.set(false)
        }
    }

    /**
     * Stop monitoring connection health.
     * Call this when VPN disconnects.
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        isMonitoring.set(false)
        resetBaseline()
        Log.i(AppConfig.TAG, "Connection health monitor stopped")
    }

    private fun resetBaseline() {
        baselineLatency.set(0L)
        checkCount = 0
        latencySum = 0L
    }

    private suspend fun checkConnectionHealth(context: Context) {
        try {
            val testUrl = SettingsManager.getDelayTestUrl()
            val currentServer = MmkvManager.getSelectServer() ?: return

            val result = V2rayConfigManager.getV2rayConfig4Speedtest(context, currentServer)
            if (!result.status) {
                Log.w(AppConfig.TAG, "Health check: Failed to get config")
                handleDegradation(context, "config_failed")
                return
            }

            val latency = V2RayNativeManager.measureOutboundDelay(result.content, testUrl)

            if (latency <= 0) {
                Log.w(AppConfig.TAG, "Health check: Connection failed")
                handleDegradation(context, "connection_failed")
                return
            }

            // Update baseline
            checkCount++
            latencySum += latency

            if (checkCount >= MIN_CHECKS_FOR_BASELINE) {
                val avgLatency = latencySum / checkCount

                if (baselineLatency.get() == 0L) {
                    baselineLatency.set(avgLatency)
                    Log.i(AppConfig.TAG, "Health check: Baseline established at ${avgLatency}ms")
                } else {
                    // Check for degradation
                    val baseline = baselineLatency.get()
                    if (latency > baseline * DEGRADATION_THRESHOLD) {
                        Log.w(AppConfig.TAG, "Health check: Degradation detected (${latency}ms vs ${baseline}ms baseline)")
                        handleDegradation(context, "latency_spike")
                    } else {
                        // Gradually update baseline with exponential moving average
                        val newBaseline = (baseline * 0.7 + latency * 0.3).toLong()
                        baselineLatency.set(newBaseline)
                    }
                }
            }

            Log.d(AppConfig.TAG, "Health check: ${latency}ms (baseline: ${baselineLatency.get()}ms)")

        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Health check failed", e)
            handleDegradation(context, "exception")
        }
    }

    private fun handleDegradation(context: Context, reason: String) {
        Log.w(AppConfig.TAG, "Connection degradation detected: $reason")

        val autoSwitchEnabled = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SERVER_SWITCH_ENABLED, true)
        if (!autoSwitchEnabled) {
            Log.d(AppConfig.TAG, "Auto-switch disabled, skipping degradation handling")
            return
        }

        // Get current server info before switch
        val currentServer = MmkvManager.getSelectServer()
        val currentConfig = currentServer?.let { MmkvManager.decodeServerConfig(it) }
        val currentLatency = baselineLatency.get()

        CoroutineScope(Dispatchers.IO).launch {
            val subscriptionId = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "") ?: ""
            BackgroundServerTester.clearCache()  // Force fresh test

            val bestServer = BackgroundServerTester.testAndFindBest(context, subscriptionId)
            if (bestServer != null && bestServer.guid != currentServer && bestServer.delay > 0) {
                val switched = V2RayServiceManager.switchServer(context, bestServer.guid)
                if (switched) {
                    // Calculate improvement
                    val improvement = if (currentLatency > 0 && bestServer.delay > 0) {
                        ((currentLatency - bestServer.delay) * 100 / currentLatency).toInt()
                    } else {
                        0
                    }

                    val message = if (currentConfig != null && currentLatency > 0 && improvement > 0) {
                        context.getString(
                            R.string.notification_server_switched_comparison,
                            currentConfig.remarks.ifEmpty { "Unknown" },
                            currentLatency,
                            bestServer.remarks,
                            bestServer.delay,
                            improvement
                        )
                    } else {
                        context.getString(
                            R.string.notification_server_switched,
                            bestServer.remarks,
                            bestServer.delay
                        )
                    }

                    Log.i(AppConfig.TAG, "Health monitor switched: $message")

                    // Reset baseline for new server
                    resetBaseline()
                }
            }
        }
    }

    fun isMonitoring(): Boolean = isMonitoring.get()
}
