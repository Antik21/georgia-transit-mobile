package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.domain.analytics.schema.AnalyticsCatalog

object NativeAmplitudeConfiguration {
    /** Public ingestion key for the Geo Transit Amplitude project in the US region. */
    const val ProductionApiKey = "bfebb555dc2a5d5f69c84b71131ab573"
    const val SessionIdleMillis = AnalyticsCatalog.SessionIdleMillis
}
