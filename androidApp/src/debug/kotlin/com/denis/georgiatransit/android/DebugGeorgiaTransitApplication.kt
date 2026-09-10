package com.denis.georgiatransit.android

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.network.installTransitOkHttpInterceptor

class DebugGeorgiaTransitApplication : GeorgiaTransitApplication() {
    override val bffEndpointConfiguration: BffEndpointConfiguration =
        if (isEmulator()) {
            BffEndpointConfiguration.debugAndroidEmulatorWithMapAssets
        } else {
            BffEndpointConfiguration.debugAndroidPhysicalDeviceWithMapAssets
        }

    override fun onCreate() {
        installTransitOkHttpInterceptor(RedactingChuckerInterceptor(this))
        super.onCreate()
        registerNetworkInspectorShortcut()
    }

    private fun registerNetworkInspectorShortcut() {
        val shortcut =
            ShortcutInfo.Builder(this, DEBUG_NETWORK_INSPECTOR_SHORTCUT_ID)
                .setShortLabel("Network inspector")
                .setLongLabel("Open debug network inspector")
                .setIntent(
                    Intent(this, DebugNetworkInspectorActivity::class.java)
                        .setAction(ACTION_OPEN_DEBUG_NETWORK_INSPECTOR)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                ).build()
        getSystemService(ShortcutManager::class.java).addDynamicShortcuts(listOf(shortcut))
    }

    private companion object {
        fun isEmulator(): Boolean =
                Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.HARDWARE in setOf("goldfish", "ranchu") ||
                Build.PRODUCT.startsWith("sdk") ||
                Build.DEVICE.startsWith("emu")

        const val DEBUG_NETWORK_INSPECTOR_SHORTCUT_ID = "debug_network_inspector"
        const val ACTION_OPEN_DEBUG_NETWORK_INSPECTOR =
            "com.denis.georgiatransit.android.action.OPEN_DEBUG_NETWORK_INSPECTOR"
    }
}
