package com.denis.georgiatransit.shared.data.config

/** Swift host calls this only from its Sandbox composition branch. */
fun debugIosSandboxBffEndpointConfiguration(baseUrl: String): BffEndpointConfiguration =
    BffEndpointConfiguration.debugSandbox(baseUrl = baseUrl)

/** Fixed, non-secret production endpoint. Production never opts into insecure HTTP. */
fun productionIosBffEndpointConfiguration(): BffEndpointConfiguration =
    BffEndpointConfiguration(
        baseUrl = "https://antik21-georgia-transit-bff.onrender.com",
        mapAssetsEnabled = false,
    )
