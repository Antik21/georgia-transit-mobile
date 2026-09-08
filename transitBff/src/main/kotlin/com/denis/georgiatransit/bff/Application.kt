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
import com.denis.georgiatransit.bff.observability.BffObservability
import com.denis.georgiatransit.bff.observability.HttpOperation
import com.denis.georgiatransit.bff.observability.ProbeRunner
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.provider.DemoFixtureTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.ProviderRegistry
import com.denis.georgiatransit.bff.provider.SyntheticProbeProvider
import com.denis.georgiatransit.bff.provider.TransitousTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.TransitousClient
import com.denis.georgiatransit.bff.service.TransitService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
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
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

private val RequestIdAttribute = AttributeKey<String>("request-id")
private val HttpOperationAttribute = AttributeKey<HttpOperation>("http-operation")
private val HttpStartedAtNanosAttribute = AttributeKey<Long>("http-started-at-nanos")
private val HttpMetricsRecordedAttribute = AttributeKey<Unit>("http-metrics-recorded")
private val safeRequestId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

private val RequestId = createApplicationPlugin("RequestId") {
    onCall { call ->
        // Metrics is an internal scrape body, not a client API response; do not echo or create a
        // per-scrape request identifier that could be mistaken for an exported telemetry label.
        if (call.request.path() == "/metrics") return@onCall
        val requestId = call.request.header(HttpHeaders.XRequestId)
            ?.takeIf(safeRequestId::matches)
            ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdAttribute, requestId)
        call.response.header(HttpHeaders.XRequestId, requestId)
    }
}

private class HttpMetricsConfiguration {
    lateinit var observability: BffObservability
}

private val HttpMetrics = createApplicationPlugin("HttpMetrics", ::HttpMetricsConfiguration) {
    val observability = pluginConfig.observability
    onCall { call ->
        call.attributes.put(HttpStartedAtNanosAttribute, System.nanoTime())
    }
    // ResponseSent proceeds through the engine send phase first, so the status is the final
    // response status rather than the nullable value visible while a route is still responding.
    on(ResponseSent) { call ->
        if (!call.attributes.contains(HttpOperationAttribute) || !call.attributes.contains(HttpStartedAtNanosAttribute)) {
            return@on
        }
        if (call.attributes.contains(HttpMetricsRecordedAttribute)) return@on
        val finalStatus = call.response.status() ?: return@on
        call.attributes.put(HttpMetricsRecordedAttribute, Unit)
        observability.recordHttp(
            operation = call.attributes[HttpOperationAttribute],
            statusCode = finalStatus.value,
            durationNanos = System.nanoTime() - call.attributes[HttpStartedAtNanosAttribute],
        )
    }
}

fun main() {
    val config = BffConfig.fromEnvironment()
    embeddedServer(Netty, host = config.host, port = config.port) {
        transitBffModule(config)
    }.start(wait = true)
}

fun Application.transitBffModule(config: BffConfig = BffConfig.fromEnvironment()) {
    val observability = BffObservability(
        config = config,
        allowedProviders = buildSet {
            if (config.mode == RuntimeMode.DEVELOPMENT && config.fixturesEnabled) {
                add("demo" to TelemetryProvider.FIXTURE)
            }
            if (config.transitous.isActivated) add("tbilisi" to TelemetryProvider.TRANSITOUS)
        },
    )
    val adapters = buildList {
        if (config.mode == RuntimeMode.DEVELOPMENT && config.fixturesEnabled) {
            add(DemoFixtureTransitProviderAdapter())
        }
        // Construction is the final activation gate: no Transitous HTTP client exists before the
        // explicit policy/contact prerequisites pass. It performs no startup probe.
        if (config.transitous.isActivated) {
            add(
                TransitousTransitProviderAdapter(
                    activation = config.transitous,
                    client = TransitousClient(config.transitous, observability = observability),
                ),
            )
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
        capabilityTelemetryObserver = observability::recordCapabilitySnapshot,
    ).also(RuntimeCapabilityControl::start)
    val service = TransitService(
        capabilitySnapshots = capabilityControl,
        directoryCacheTtlSeconds = config.directoryCacheTtlSeconds,
        shapeCacheTtlSeconds = config.shapeCacheTtlSeconds,
        realtimeSingleFlightSeconds = config.realtimeSingleFlightSeconds,
        observability = observability,
        schemaDriftObserver = capabilityControl::observeSchemaDrift,
    )
    val probeRunner = ProbeRunner(
        config = config,
        service = service,
        targets = adapters.filterIsInstance<SyntheticProbeProvider>().flatMap(SyntheticProbeProvider::probeTargets),
        observability = observability,
        audit = environment.log::info,
    ).also(ProbeRunner::start)

    install(RequestId)
    install(HttpMetrics) { this.observability = observability }
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
        probeRunner.close()
        capabilityControl.close()
        service.close()
        adapters.filterIsInstance<AutoCloseable>().forEach(AutoCloseable::close)
    }

    routing {
        get("/healthz") {
            call.markHttpOperation(HttpOperation.HEALTH)
            val ready = service.isReady
            val status = if (ready) "ready" else "not_ready"
            call.respond(
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
                HealthResponse(status = status, mode = config.mode.name.lowercase()),
            )
        }

        if (config.metricsEnabled) {
            get("/metrics") {
                // This route intentionally has no HttpOperation marker: scraping must not create
                // telemetry feedback or exercise provider/cache/circuit paths.
                call.respondText(
                    text = observability.render(),
                    contentType = ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
                )
            }
        }

        route("/v1/cities") {
            get {
                call.markHttpOperation(HttpOperation.CITIES)
                call.respond(service.cities())
            }

            route("/{cityId}") {
                get("/routes") {
                    call.markHttpOperation(HttpOperation.LIST_ROUTES)
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
                    call.markHttpOperation(HttpOperation.ROUTE)
                    val cityId = call.pathCityId()
                    val routeId = call.requiredPathPublicId("routeId", cityId, "route")
                    call.respond(service.route(cityId, routeId, call.locale()))
                }
                get("/routes/{routeId}/directions/{directionId}/stops") {
                    call.markHttpOperation(HttpOperation.DIRECTION_STOPS)
                    val cityId = call.pathCityId()
                    val routeId = call.requiredPathPublicId("routeId", cityId, "route")
                    val directionId = call.requiredPathPublicId("directionId", cityId, "direction")
                    call.respond(service.directionStops(cityId, routeId, directionId, call.locale()))
                }
                get("/routes/{routeId}/directions/{directionId}/shape") {
                    call.markHttpOperation(HttpOperation.SHAPE)
                    val cityId = call.pathCityId()
                    val routeId = call.requiredPathPublicId("routeId", cityId, "route")
                    val directionId = call.requiredPathPublicId("directionId", cityId, "direction")
                    call.respond(service.shape(cityId, routeId, directionId))
                }
                get("/vehicles") {
                    call.markHttpOperation(HttpOperation.VEHICLES)
                    val cityId = call.pathCityId()
                    val routeId = call.requiredQueryPublicId("routeId", cityId, "route")
                    val directionId = call.request.queryParameters["directionId"]?.also {
                        com.denis.georgiatransit.bff.api.validatePublicId(it, cityId, "direction", "directionId")
                    }
                    call.respond(service.vehicles(cityId, routeId, directionId))
                }
                get("/stops/nearby") {
                    call.markHttpOperation(HttpOperation.NEARBY_STOPS)
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
                    call.markHttpOperation(HttpOperation.ARRIVALS)
                    val cityId = call.pathCityId()
                    val stopId = call.requiredPathPublicId("stopId", cityId, "stop")
                    val limit = call.requiredQueryInt("limit", 1, 100)
                    call.respond(service.arrivals(cityId, stopId, limit, call.locale()))
                }
                get("/journeys") {
                    call.markHttpOperation(HttpOperation.JOURNEYS)
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

private fun ApplicationCall.markHttpOperation(operation: HttpOperation) {
    if (!attributes.contains(HttpOperationAttribute)) attributes.put(HttpOperationAttribute, operation)
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
