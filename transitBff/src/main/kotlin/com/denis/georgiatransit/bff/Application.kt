package com.denis.georgiatransit.bff

import com.denis.georgiatransit.bff.api.ApiError
import com.denis.georgiatransit.bff.api.ErrorEnvelope
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.HealthResponse
import com.denis.georgiatransit.bff.api.InternalServerError
import com.denis.georgiatransit.bff.api.ServiceFailure
import com.denis.georgiatransit.bff.api.locale
import com.denis.georgiatransit.bff.api.mode
import com.denis.georgiatransit.bff.api.pathCityId
import com.denis.georgiatransit.bff.api.requiredInstant
import com.denis.georgiatransit.bff.api.requiredPathPublicId
import com.denis.georgiatransit.bff.api.requiredQueryDouble
import com.denis.georgiatransit.bff.api.requiredQueryInt
import com.denis.georgiatransit.bff.api.requiredQueryPublicId
import com.denis.georgiatransit.bff.config.BffConfig
import com.denis.georgiatransit.bff.config.BffConfigurationException
import com.denis.georgiatransit.bff.config.RuntimeMode
import com.denis.georgiatransit.bff.control.RuntimeCapabilityControl
import com.denis.georgiatransit.bff.provider.DemoFixtureTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.TransitousTransitProviderAdapter
import com.denis.georgiatransit.bff.service.TransitService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

private val RequestIdAttribute = AttributeKey<String>("request-id")
private val safeRequestId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private val RequestId = createApplicationPlugin("RequestId") {
    onCall { call ->
        val requestId = call.request.header(HttpHeaders.XRequestId)
            ?.takeIf(safeRequestId::matches)
            ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdAttribute, requestId)
        call.response.header(HttpHeaders.XRequestId, requestId)
    }
}

fun main() {
    val config = BffConfig.fromEnvironment()
    embeddedServer(Netty, host = config.host, port = config.port) {
        transitBffModule(config)
    }.start(wait = true)
}

fun Application.transitBffModule(config: BffConfig = BffConfig.fromEnvironment()) {
    val adapters = buildList {
        if (config.mode == RuntimeMode.DEVELOPMENT && config.fixturesEnabled) {
            add(DemoFixtureTransitProviderAdapter())
        }
        // Construction is the final activation gate: no Transitous HTTP client exists before the
        // explicit policy/contact prerequisites pass. It performs no startup probe.
        if (config.transitous.isActivated) {
            add(TransitousTransitProviderAdapter(config.transitous))
        }
    }
    val registry = ProviderRegistry(adapters)
    if (config.mode == RuntimeMode.PRODUCTION && !registry.isReady) {
        throw BffConfigurationException(
            "No production provider adapters are configured; production startup fails closed",
        )
    }
    val capabilityControl = RuntimeCapabilityControl(
        config = config,
        registry = registry,
        audit = environment.log::info,
    ).also(RuntimeCapabilityControl::start)
    val service = TransitService(
        capabilitySnapshots = capabilityControl,
        directoryCacheTtlSeconds = config.directoryCacheTtlSeconds,
        shapeCacheTtlSeconds = config.shapeCacheTtlSeconds,
        realtimeSingleFlightSeconds = config.realtimeSingleFlightSeconds,
    )

    install(RequestId)
    install(CallLogging) {
        format { call ->
            val requestId = if (call.attributes.contains(RequestIdAttribute)) {
                call.attributes[RequestIdAttribute]
            } else {
                "unassigned"
            }
            "${call.request.httpMethod.value} ${call.request.path()} status=" +
                "${call.response.status()?.value ?: 0} requestId=$requestId"
        }
    }
    install(ContentNegotiation) {
        json(Json {
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = false
        })
    }
    install(StatusPages) {
        exception<ServiceFailure> { call, failure -> call.respondFailure(failure) }
        exception<CancellationException> { _, exception -> throw exception }
        exception<Throwable> { call, _ ->
            this@transitBffModule.environment.log.error(
                "request_failure classification=internal status=500 requestId=${call.requestId()}",
            )
            call.respondFailure(InternalServerError())
        }
    }
    monitor.subscribe(ApplicationStopped) {
        capabilityControl.close()
        service.close()
        adapters.filterIsInstance<AutoCloseable>().forEach(AutoCloseable::close)
    }

    routing {
        get("/healthz") {
            val ready = service.isReady
            val status = if (ready) "ready" else "not_ready"
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(status = status, mode = config.mode.name.lowercase()),
            )
        }

        route("/v1/cities") {
            get {
                call.respond(service.cities())
            }

            route("/{cityId}") {
                get("/routes") {
                    val cityId = call.pathCityId()
                    val page = service.routes(cityId, call.locale(), call.mode())
                    val etag = service.routeEtag(page)
                    call.response.header(HttpHeaders.ETag, etag)
                    if (call.request.header(HttpHeaders.IfNoneMatch).matchesEtag(etag)) {
                        call.respond(HttpStatusCode.NotModified)
                    } else {
                        call.respond(page)
                    }
                }
                get("/routes/{routeId}") {
                    val cityId = call.pathCityId()
                    val routeId = call.requiredPathPublicId("routeId", cityId, "route")
                    call.respond(service.route(cityId, routeId, call.locale()))
                }
                get("/routes/{routeId}/directions/{directionId}/stops") {
                    val cityId = call.pathCityId()
                    val routeId = call.requiredPathPublicId("routeId", cityId, "route")
                    val directionId = call.requiredPathPublicId("directionId", cityId, "direction")
                    call.respond(service.directionStops(cityId, routeId, directionId, call.locale()))
                }
                get("/routes/{routeId}/directions/{directionId}/shape") {
                    val cityId = call.pathCityId()
                    val routeId = call.requiredPathPublicId("routeId", cityId, "route")
                    val directionId = call.requiredPathPublicId("directionId", cityId, "direction")
                    call.respond(service.shape(cityId, routeId, directionId))
                }
                get("/vehicles") {
                    val cityId = call.pathCityId()
                    val routeId = call.requiredQueryPublicId("routeId", cityId, "route")
                    val directionId = call.request.queryParameters["directionId"]?.also {
                        com.denis.georgiatransit.bff.api.validatePublicId(it, cityId, "direction", "directionId")
                    }
                    call.respond(service.vehicles(cityId, routeId, directionId))
                }
                get("/stops/nearby") {
                    val cityId = call.pathCityId()
                    val location = GeoPoint(
                        latitude = call.requiredQueryDouble("lat", -90.0, 90.0),
                        longitude = call.requiredQueryDouble("lon", -180.0, 180.0),
                    )
                    val radiusMeters = call.requiredQueryInt("radiusMeters", 1, 50_000)
                    val limit = call.requiredQueryInt("limit", 1, 100)
                    call.respond(service.nearbyStops(cityId, location, radiusMeters, limit, call.locale()))
                }
                get("/stops/{stopId}/arrivals") {
                    val cityId = call.pathCityId()
                    val stopId = call.requiredPathPublicId("stopId", cityId, "stop")
                    val limit = call.requiredQueryInt("limit", 1, 100)
                    call.respond(service.arrivals(cityId, stopId, limit, call.locale()))
                }
                get("/journeys") {
                    val cityId = call.pathCityId()
                    val query = JourneyQuery(
                        from = GeoPoint(
                            latitude = call.requiredQueryDouble("fromLat", -90.0, 90.0),
                            longitude = call.requiredQueryDouble("fromLon", -180.0, 180.0),
                        ),
                        to = GeoPoint(
                            latitude = call.requiredQueryDouble("toLat", -90.0, 90.0),
                            longitude = call.requiredQueryDouble("toLon", -180.0, 180.0),
                        ),
                        departureAt = call.requiredInstant("departureAt"),
                        locale = call.locale(),
                        maxTransfers = call.requiredQueryInt("maxTransfers", 0, 6),
                    )
                    call.respond(service.journeys(cityId, query))
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondFailure(failure: ServiceFailure) {
    failure.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    respond(
        failure.status,
        ErrorEnvelope(
            ApiError(
                code = failure.errorCode,
                message = failure.message,
                retryAfterSeconds = failure.retryAfterSeconds,
                requestId = requestId(),
            ),
        ),
    )
}

private fun ApplicationCall.requestId(): String =
    if (attributes.contains(RequestIdAttribute)) attributes[RequestIdAttribute] else UUID.randomUUID().toString()

private fun String?.matchesEtag(etag: String): Boolean =
    this?.split(',')?.any { candidate ->
        val normalized = candidate.trim()
        normalized == "*" || normalized.removePrefix("W/").trim() == etag
    } == true
