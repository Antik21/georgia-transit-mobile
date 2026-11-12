package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.InvalidArgument
import com.denis.georgiatransit.bff.api.RequestRateLimited
import com.denis.georgiatransit.bff.api.RouteNotFound
import com.denis.georgiatransit.bff.api.ServiceFailure
import com.denis.georgiatransit.bff.api.StateConflict
import com.denis.georgiatransit.bff.api.StopNotFound
import com.denis.georgiatransit.bff.api.UpstreamBadGateway
import com.denis.georgiatransit.bff.api.UpstreamTimeout
import com.denis.georgiatransit.bff.api.UpstreamUnavailable
import com.denis.georgiatransit.bff.observability.ProviderOutcome
import com.denis.georgiatransit.bff.provider.ProviderBadGateway
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import com.denis.georgiatransit.bff.provider.ProviderConflict
import com.denis.georgiatransit.bff.provider.ProviderFailure
import com.denis.georgiatransit.bff.provider.ProviderInvalidArgument
import com.denis.georgiatransit.bff.provider.ProviderRateLimited
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import com.denis.georgiatransit.bff.provider.ProviderStopNotFound
import com.denis.georgiatransit.bff.provider.ProviderTimeout
import com.denis.georgiatransit.bff.provider.ProviderUnavailable

private const val MaximumRetryAfterSeconds = 86_400

internal fun ProviderFailure.toServiceFailure(): ServiceFailure =
    when (this) {
        is ProviderInvalidArgument -> InvalidArgument("The transit provider rejected the request")
        is ProviderRouteNotFound -> RouteNotFound("The requested route was not found")
        is ProviderStopNotFound -> StopNotFound("The requested stop was not found")
        is ProviderConflict -> StateConflict("The provider ID changed")
        is ProviderRateLimited -> RequestRateLimited(
            "The transit provider is rate limited",
            retryAfterSeconds.validatedRetryAfterSeconds(),
        )
        is ProviderCapabilityUnavailable -> CapabilityNotAvailable("The requested capability is not available")
        is ProviderBadGateway -> UpstreamBadGateway("The provider returned an invalid response")
        is ProviderUnavailable -> UpstreamUnavailable(
            "The transit provider is unavailable",
            retryAfterSeconds.validatedRetryAfterSeconds(),
        )
        is ProviderTimeout -> UpstreamTimeout("The transit provider did not respond in time")
    }

internal fun ProviderFailure.telemetryOutcome(): ProviderOutcome =
    when (this) {
        is ProviderInvalidArgument -> ProviderOutcome.INVALID_ARGUMENT
        is ProviderRouteNotFound,
        is ProviderStopNotFound,
        is ProviderConflict,
        -> ProviderOutcome.INVALID_ARGUMENT
        is ProviderRateLimited -> ProviderOutcome.RATE_LIMITED
        is ProviderCapabilityUnavailable -> ProviderOutcome.CAPABILITY_UNAVAILABLE
        is ProviderBadGateway -> ProviderOutcome.BAD_GATEWAY
        is ProviderUnavailable -> ProviderOutcome.UNAVAILABLE
        is ProviderTimeout -> ProviderOutcome.TIMEOUT
    }

private fun Int?.validatedRetryAfterSeconds(): Int? = takeIf { it in 1..MaximumRetryAfterSeconds }
