package com.denis.georgiatransit.shared.core.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

interface AppTelemetry {
    fun setSelectedCity(cityId: String?)
    fun recordNonFatal(issue: NonFatalIssue)
}

enum class NonFatalIssue(val code: Int, val summary: String) {
    Startup(1, "Transit startup failed"),
    SelectedCityStorage(2, "Selected city storage failed"),
    TransitCacheStorage(3, "Transit cache storage failed"),
    TransitCacheDecode(4, "Transit cache decoding failed"),
    BffResponseDecode(5, "Transit response decoding failed"),
    BffResponseMapping(6, "Transit response mapping failed"),
    BffUnexpectedResponse(7, "Transit returned an unexpected response"),
}

internal object NoOpAppTelemetry : AppTelemetry {
    override fun setSelectedCity(cityId: String?) = Unit
    override fun recordNonFatal(issue: NonFatalIssue) = Unit
}

/** A polling failure must not fill Crashlytics' bounded buffer with copies of the same issue. */
internal class SafeAppTelemetry(private val delegate: AppTelemetry) : AppTelemetry {
    private val reportedIssues = MutableStateFlow(emptySet<NonFatalIssue>())

    override fun setSelectedCity(cityId: String?) {
        val validId = cityId?.takeIf(AnalyticsCityId::matches)
        runCatching { delegate.setSelectedCity(validId) }
    }

    override fun recordNonFatal(issue: NonFatalIssue) {
        if (issue in reportedIssues.getAndUpdate { it + issue }) return
        runCatching { delegate.recordNonFatal(issue) }
    }

    private companion object {
        val AnalyticsCityId = Regex("[a-z][a-z0-9-]{1,31}")
    }
}
