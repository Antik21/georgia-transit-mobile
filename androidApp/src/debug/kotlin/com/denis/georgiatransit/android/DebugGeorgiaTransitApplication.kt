package com.denis.georgiatransit.android

import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import com.denis.georgiatransit.shared.data.network.installTransitOkHttpInterceptor

class DebugGeorgiaTransitApplication : GeorgiaTransitApplication() {
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
        const val DEBUG_NETWORK_INSPECTOR_SHORTCUT_ID = "debug_network_inspector"
        const val ACTION_OPEN_DEBUG_NETWORK_INSPECTOR =
            "com.denis.georgiatransit.android.action.OPEN_DEBUG_NETWORK_INSPECTOR"
    }
}
