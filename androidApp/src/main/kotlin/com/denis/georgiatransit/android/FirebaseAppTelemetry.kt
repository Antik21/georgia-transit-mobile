package com.denis.georgiatransit.android

import android.content.Context
import com.denis.georgiatransit.shared.core.telemetry.AppTelemetry
import com.denis.georgiatransit.shared.core.telemetry.NonFatalIssue
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

internal class FirebaseAppTelemetry(context: Context) : AppTelemetry {
    private val analytics by lazy { FirebaseAnalytics.getInstance(context) }

    override fun setSelectedCity(cityId: String?) {
        analytics.setUserProperty("selected_city", cityId)
    }

    override fun recordNonFatal(issue: NonFatalIssue) {
        FirebaseCrashlytics.getInstance().recordException(HandledTransitException(issue.summary))
    }

    private class HandledTransitException(description: String) : RuntimeException(description)
}
