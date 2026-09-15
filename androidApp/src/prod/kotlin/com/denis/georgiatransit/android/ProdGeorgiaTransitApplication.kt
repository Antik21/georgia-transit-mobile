package com.denis.georgiatransit.android

import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration

class ProdGeorgiaTransitApplication : GeorgiaTransitApplication() {
    override val bffEndpointConfiguration =
        BffEndpointConfiguration(
            baseUrl = BuildConfig.BFF_BASE_URL,
            mapAssetsEnabled = true,
        ).also { endpoint ->
            check(endpoint.validationFailure() == null) {
                "The production BFF endpoint must be a valid HTTPS origin."
            }
        }
}
