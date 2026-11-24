package com.denis.georgiatransit.android

import android.app.Application
import com.amplitude.android.Amplitude
import com.amplitude.android.AutocaptureOption
import com.amplitude.android.Configuration
import com.amplitude.android.TrackingOptions
import com.amplitude.core.events.BaseEvent
import com.amplitude.core.events.EventOptions
import com.denis.georgiatransit.shared.app.NativeAmplitudeConfiguration
import com.denis.georgiatransit.shared.domain.analytics.NativeAnalyticsEvent
import com.denis.georgiatransit.shared.domain.analytics.NativeAnalyticsSink

internal class AmplitudeProductAnalytics(application: Application, apiKey: String, deviceId: String) : NativeAnalyticsSink {
    private val amplitude = Amplitude(
        Configuration(
            apiKey = apiKey,
            context = application,
            deviceId = deviceId,
            autocapture = setOf(AutocaptureOption.APP_LIFECYCLES),
            minTimeBetweenSessionsMillis = NativeAmplitudeConfiguration.SessionIdleMillis,
            enableAutocaptureRemoteConfig = false,
            enableDiagnostics = false,
            trackingOptions = TrackingOptions().disableAdid().disableAppSetId().disableIpAddress()
                .disableLatLng().disableCity().disableDma().disableRegion().disableCountry(),
        ),
    )

    override fun send(event: NativeAnalyticsEvent) {
        if (event.isIdentify) {
            amplitude.identify(event.userProperties, EventOptions().apply {
                timestamp = event.timestamp
                insertId = event.insertId
            })
        } else {
            amplitude.track(BaseEvent().apply {
                eventType = event.name
                timestamp = event.timestamp
                insertId = event.insertId
                eventProperties = event.properties.toMutableMap()
                userProperties = event.userProperties.toMutableMap()
            })
        }
    }
}
