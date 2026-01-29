package com.v2ray.ang.service

import android.util.Log
import com.v2ray.ang.AppConfig

/**
 * Checks if the hev-socks5-tunnel native library is available.
 * Isolated from TProxyService to prevent class verification failures when library is missing.
 */
object HevLibraryChecker {
    @Volatile
    private var libraryAvailable: Boolean? = null

    /**
     * Checks if the native library can be loaded (cached after first check).
     */
    @Synchronized
    fun isLibraryAvailable(): Boolean {
        libraryAvailable?.let { return it }

        return try {
            System.loadLibrary("hev-socks5-tunnel")
            Log.i(AppConfig.TAG, "hev-socks5-tunnel library loaded successfully")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(AppConfig.TAG, "hev-socks5-tunnel library not available: ${e.message}")
            false
        }.also { libraryAvailable = it }
    }
}
