package com.denis.georgiatransit.android

import android.app.Application
import com.denis.georgiatransit.shared.data.cache.AndroidTransitCacheStore
import com.denis.georgiatransit.shared.data.config.AndroidRuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.persistence.AndroidSelectedCityStore
import com.denis.georgiatransit.shared.di.initGeorgiaTransitKoin

open class GeorgiaTransitApplication : Application() {
    /** Release has no endpoint until an operator supplies a reviewed HTTPS BFF URL. */
    protected open val bffEndpointConfiguration: BffEndpointConfiguration? = null

    override fun onCreate() {
        super.onCreate()
        initGeorgiaTransitKoin(
            selectedCityStore = AndroidSelectedCityStore(this),
            runtimeConfigurationSource = AndroidRuntimeBootstrapConfigurationSource(),
            transitCacheStore = AndroidTransitCacheStore(this),
            bffEndpointConfiguration = bffEndpointConfiguration,
        )
    }
}
