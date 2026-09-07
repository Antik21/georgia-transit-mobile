package com.denis.georgiatransit.shared.data.config

import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfiguration
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource

/** Android host adapter for the app's non-secret bootstrap settings. */
class AndroidRuntimeBootstrapConfigurationSource : RuntimeBootstrapConfigurationSource {
    override suspend fun load(): RuntimeBootstrapConfiguration = RuntimeBootstrapConfiguration.default
}
