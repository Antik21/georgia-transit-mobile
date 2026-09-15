package com.denis.georgiatransit.android

import android.app.Application
import com.denis.georgiatransit.shared.data.cache.AndroidTransitCacheStore
import com.denis.georgiatransit.shared.data.config.AndroidRuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.persistence.AndroidSelectedCityStore
import com.denis.georgiatransit.shared.di.initGeorgiaTransitKoin

open class GeorgiaTransitApplication : Application() {
    /** Each enabled environment variant supplies its reviewed BFF endpoint. */
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
