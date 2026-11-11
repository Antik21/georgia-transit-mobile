package com.denis.georgiatransit.shared.data.config

import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfiguration
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource

class BrowserRuntimeBootstrapConfigurationSource : RuntimeBootstrapConfigurationSource {
    override suspend fun load(): RuntimeBootstrapConfiguration = RuntimeBootstrapConfiguration.default
}
