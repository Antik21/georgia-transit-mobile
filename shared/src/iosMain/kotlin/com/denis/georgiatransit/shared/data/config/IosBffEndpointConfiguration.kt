package com.denis.georgiatransit.shared.data.config

/** Swift host calls this only from its DEBUG composition branch. */
fun debugIosSimulatorBffEndpointConfiguration(): BffEndpointConfiguration =
    BffEndpointConfiguration.debugIosSimulator
