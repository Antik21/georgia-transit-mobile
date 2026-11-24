package com.denis.georgiatransit.android

import android.app.Application
import com.denis.georgiatransit.shared.app.NativeAmplitudeConfiguration
import com.denis.georgiatransit.shared.data.cache.AndroidTransitCacheStore
import com.denis.georgiatransit.shared.data.config.AndroidRuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.persistence.AndroidSelectedCityStore
import com.denis.georgiatransit.shared.di.initGeorgiaTransitKoin
import com.denis.georgiatransit.shared.domain.analytics.AnalyticsClient
import com.denis.georgiatransit.shared.domain.analytics.loadAnalyticsDeviceId

open class GeorgiaTransitApplication : Application() {
    /** Each enabled environment variant supplies its reviewed BFF endpoint. */
    protected open val bffEndpointConfiguration: BffEndpointConfiguration? = null
    override fun onCreate() {
        super.onCreate()
        val analytics = getSharedPreferences("product_analytics", MODE_PRIVATE)
        val deviceId = loadAnalyticsDeviceId(
            { analytics.getString("device_id", null) },
            { analytics.edit().putString("device_id", it).apply() },
        )
        val amplitudeKey = if (BuildConfig.DEBUG) {
            BuildConfig.SANDBOX_AMPLITUDE_API_KEY
        } else {
            NativeAmplitudeConfiguration.ProductionApiKey
        }
        initGeorgiaTransitKoin(
            selectedCityStore = AndroidSelectedCityStore(this),
            runtimeConfigurationSource = AndroidRuntimeBootstrapConfigurationSource(),
            transitCacheStore = AndroidTransitCacheStore(this),
            bffEndpointConfiguration = bffEndpointConfiguration,
            appTelemetry = FirebaseAppTelemetry(this),
            analyticsClient = AnalyticsClient.Android,
            analyticsDeviceId = deviceId,
            nativeAnalytics = amplitudeKey.takeIf(String::isNotBlank)?.let {
                AmplitudeProductAnalytics(this, it, deviceId)
            },
        )
    }
}
