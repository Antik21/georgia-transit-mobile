package com.denis.georgiatransit.bff.api

import io.ktor.http.HttpStatusCode
import kotlin.time.Duration.Companion.seconds

sealed class ServiceFailure(
    val status: HttpStatusCode,
    val errorCode: String,
    override val message: String,
    val retryAfterSeconds: Int? = null,
) : RuntimeException(message)

class InvalidArgument(message: String) :
    ServiceFailure(HttpStatusCode.BadRequest, "INVALID_ARGUMENT", message)

class CityNotFound(message: String) :
    ServiceFailure(HttpStatusCode.NotFound, "CITY_NOT_FOUND", message)

class RouteNotFound(message: String) :
    ServiceFailure(HttpStatusCode.NotFound, "ROUTE_NOT_FOUND", message)

class StopNotFound(message: String) :
    ServiceFailure(HttpStatusCode.NotFound, "STOP_NOT_FOUND", message)

class StateConflict(message: String) :
    ServiceFailure(HttpStatusCode.Conflict, "PROVIDER_ID_CHANGED", message)

class RequestRateLimited(message: String, retryAfterSeconds: Int) :
    ServiceFailure(HttpStatusCode.TooManyRequests, "RATE_LIMITED", message, retryAfterSeconds)

class CapabilityNotAvailable(message: String) :
    ServiceFailure(HttpStatusCode.NotImplemented, "CAPABILITY_NOT_AVAILABLE", message)

class UpstreamBadGateway(message: String) :
    ServiceFailure(HttpStatusCode.BadGateway, "UPSTREAM_BAD_RESPONSE", message)

open class UpstreamUnavailable(message: String, retryAfterSeconds: Int? = null) :
    ServiceFailure(HttpStatusCode.ServiceUnavailable, "UPSTREAM_UNAVAILABLE", message, retryAfterSeconds)

class UpstreamTimeout(message: String) :
    ServiceFailure(HttpStatusCode.GatewayTimeout, "UPSTREAM_TIMEOUT", message)

class SingleFlightCapacityExceeded :
    UpstreamUnavailable("The real-time request queue is at capacity", retryAfterSeconds = 1)

fun ServiceFailure.retryAfterHeader(): String? = retryAfterSeconds?.seconds?.inWholeSeconds?.toString()
