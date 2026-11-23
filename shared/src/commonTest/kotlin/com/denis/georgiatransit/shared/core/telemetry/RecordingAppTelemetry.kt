package com.denis.georgiatransit.shared.core.telemetry

internal class RecordingAppTelemetry : AppTelemetry {
    val cities = mutableListOf<String?>()
    val nonFatals = mutableListOf<NonFatalIssue>()

    override fun setSelectedCity(cityId: String?) {
        cities += cityId
    }

    override fun recordNonFatal(issue: NonFatalIssue) {
        nonFatals += issue
    }
}
