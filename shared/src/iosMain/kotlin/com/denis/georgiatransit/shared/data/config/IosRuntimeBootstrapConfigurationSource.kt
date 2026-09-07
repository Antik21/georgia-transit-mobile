package com.denis.georgiatransit.shared.data.config

import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfiguration
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource

/** iOS host adapter for the app's non-secret bootstrap settings. */
class IosRuntimeBootstrapConfigurationSource : RuntimeBootstrapConfigurationSource {
    override suspend fun load(): RuntimeBootstrapConfiguration = RuntimeBootstrapConfiguration.default
}
