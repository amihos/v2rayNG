package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import java.io.File

/**
 * Manages the tun2socks process that handles VPN traffic
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val isRunningProvider: () -> Boolean,
    private val restartCallback: () -> Unit
) : Tun2SocksControl {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        @Volatile
        private var libraryLoaded = false

        /**
         * Loads the native library on demand to avoid crashes when library is not present
         * and HevTun is disabled.
         * @return true if library was loaded successfully, false otherwise
         */
        @Synchronized
        fun loadLibrary(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("hev-socks5-tunnel")
                libraryLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(AppConfig.TAG, "Failed to load hev-socks5-tunnel library: ${e.message}")
                false
            }
        }

        /**
         * Check if the native library is available (can be loaded).
         * This is a non-crashing check that can be used before deciding to use HevTun.
         */
        fun isLibraryAvailable(): Boolean {
            return libraryLoaded || loadLibrary()
        }
    }

    /**
     * Starts the tun2socks process with the appropriate parameters.
     */
    override fun startTun2Socks() {
        // Load library on demand - fail gracefully if not available
        if (!loadLibrary()) {
            Log.e(AppConfig.TAG, "Cannot start HevSocks5Tunnel - library not available")
            return
        }

        val configContent = buildConfig()
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
        Log.d(AppConfig.TAG, "HevSocks5Tunnel Config content:\n$configContent")

        try {
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "HevSocks5Tunnel exception: ${e.message}")
        }
    }

    private fun buildConfig(): String {
        val socksPort = SettingsManager.getSocksPort()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${SettingsManager.getVpnMtu()}")
            appendLine("  ipv4: ${vpnConfig.ipv4Client}")

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6)) {
                appendLine("  ipv6: '${vpnConfig.ipv6Client}'")
            }

            appendLine("socks5:")
            appendLine("  port: ${socksPort}")
            appendLine("  address: ${AppConfig.LOOPBACK}")
            appendLine("  udp: 'udp'")

            // Read-write timeout settings
            val timeoutSetting = MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_RW_TIMEOUT) ?: AppConfig.HEVTUN_RW_TIMEOUT
            val parts = timeoutSetting.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val tcpTimeout = parts.getOrNull(0)?.toIntOrNull() ?: 300
            val udpTimeout = parts.getOrNull(1)?.toIntOrNull() ?: 60

            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: ${tcpTimeout * 1000}")
            appendLine("  udp-read-write-timeout: ${udpTimeout * 1000}")
            appendLine("  log-level: ${MmkvManager.decodeSettingsString(AppConfig.PREF_HEV_TUNNEL_LOGLEVEL) ?: "warn"}")
        }
    }

    /**
     * Stops the tun2socks process
     */
    override fun stopTun2Socks() {
        try {
            Log.i(AppConfig.TAG, "TProxyStopService...")
            TProxyStopService()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to stop hev-socks5-tunnel", e)
        }
    }
}
