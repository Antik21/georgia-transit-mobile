package com.denis.georgiatransit.bff.analytics

import com.denis.georgiatransit.bff.api.KnownCityIds
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class ProductAnalyticsConfig private constructor(internal val apiKey: String, internal val endpoint: String) {
    companion object {
        fun fromEnvironment(environment: Map<String, String>): ProductAnalyticsConfig? {
            val key = environment["BFF_AMPLITUDE_API_KEY"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val host = when (environment["BFF_AMPLITUDE_REGION"]?.trim()?.lowercase() ?: "us") {
                "us" -> "api2.amplitude.com"
                "eu" -> "api.eu.amplitude.com"
                else -> throw IllegalArgumentException("BFF_AMPLITUDE_REGION must be us or eu")
            }
            return ProductAnalyticsConfig(key, "https://$host/2/httpapi")
        }
    }
}

@Serializable
internal data class AnalyticsRequest(
    val client: String,
    val deviceId: String,
    val sessionId: Long,
    val time: Long,
    val zoomType: String? = null,
    val routesSelection: RoutesSelection? = null,
    val recentRoutesUsed: Boolean = false,
    val routeSearchUsed: Boolean = false,
    val journeyPlanning: JourneyPlanning? = null,
    val stopOpening: StopOpening? = null,
    val vehicleTapId: String? = null,
) {
    @Serializable
    data class RoutesSelection(val cityId: String, val routeCount: Int, val eventId: String) {
        fun isValid(): Boolean = cityId in AnalyticsCities &&
            routeCount in 0..MaximumSelectedRoutes && UuidPattern.matches(eventId)
    }

    @Serializable
    data class JourneyPlanning(val source: String, val eventId: String) {
        fun isValid(): Boolean = source in JourneyPlanningSources && UuidPattern.matches(eventId)
    }

    @Serializable
    data class StopOpening(val cityId: String, val eventId: String) {
        fun isValid(): Boolean = cityId in AnalyticsCities && UuidPattern.matches(eventId)
    }

    fun isValid(): Boolean {
        val eventCount = listOf(
            zoomType != null, routesSelection != null, recentRoutesUsed,
            routeSearchUsed, journeyPlanning != null, stopOpening != null, vehicleTapId != null,
        ).count { it }
        return client in Clients && UuidPattern.matches(deviceId) && sessionId > 0 && time >= sessionId &&
            eventCount <= 1 && (zoomType == null || zoomType in ZoomTypes) &&
            routesSelection?.isValid() != false && journeyPlanning?.isValid() != false &&
            stopOpening?.isValid() != false && (vehicleTapId == null || UuidPattern.matches(vehicleTapId))
    }

    fun toAmplitudeEvent(): JsonObject = buildJsonObject {
        val eventType = when {
            recentRoutesUsed -> "recent_routes_used"
            routeSearchUsed -> "route_search_used"
            journeyPlanning != null -> "journey_planning_started"
            stopOpening != null -> "stop_opened"
            vehicleTapId != null -> "vehicle_tapped"
            routesSelection != null -> "routes_selected"
            zoomType != null -> "zoom_started"
            else -> "\$identify"
        }
        put("device_id", deviceId)
        put("event_type", eventType)
        put("session_id", sessionId)
        put("time", time)
        val interactionId = routesSelection?.eventId ?: journeyPlanning?.eventId ?: stopOpening?.eventId ?: vehicleTapId
        val eventKey = when {
            interactionId != null -> "$eventType:$interactionId"
            zoomType != null -> zoomType
            eventType == "\$identify" -> "identify"
            else -> eventType
        }
        put("insert_id", "$client:$sessionId:$eventKey")
        putJsonObject("user_properties") {
            if (eventType == "\$identify") putJsonObject("\$set") { put("client", client) }
            else put("client", client)
        }
        when {
            routesSelection != null -> putJsonObject("event_properties") {
                put("city", routesSelection.cityId)
                put("route_count", routesSelection.routeCount)
            }
            journeyPlanning != null -> putJsonObject("event_properties") { put("source", journeyPlanning.source) }
            stopOpening != null -> putJsonObject("event_properties") { put("city", stopOpening.cityId) }
            zoomType != null -> putJsonObject("event_properties") { put("type", zoomType) }
        }
    }
}

private val Clients = setOf("android", "ios", "web", "telegram_mini_app")
private val ZoomTypes = setOf("finger_double_tap", "finger_pinch", "button")
private val JourneyPlanningSources = setOf("map_button", "long_press")
private val AnalyticsCities = setOf(
    KnownCityIds.Batumi, KnownCityIds.Demo, KnownCityIds.Kutaisi, KnownCityIds.Tbilisi, KnownCityIds.Zugdidi,
)
private const val MaximumSelectedRoutes = 10
private val UuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal class ProductAnalyticsPublisher(private val config: ProductAnalyticsConfig) : AutoCloseable {
    private val slots = Semaphore(4)
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 1_000
            requestTimeoutMillis = 3_000
        }
    }

    suspend fun publish(event: AnalyticsRequest): HttpStatusCode {
        if (!slots.tryAcquire()) return HttpStatusCode.TooManyRequests
        try {
            val response = http.post(config.endpoint) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("api_key", config.apiKey)
                    putJsonArray("events") { add(event.toAmplitudeEvent()) }
                }.toString())
            }
            response.bodyAsChannel().cancel(null)
            return if (response.status.isSuccess()) HttpStatusCode.NoContent else HttpStatusCode.BadGateway
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return HttpStatusCode.BadGateway
        } finally {
            slots.release()
        }
    }

    override fun close() = http.close()
}

internal fun Route.productAnalyticsRoute(publish: (suspend (AnalyticsRequest) -> HttpStatusCode)?) {
    post("/v1/analytics") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        if (publish == null) return@post call.respond(HttpStatusCode.NoContent)
        val bytes = call.receiveChannel().readRemaining(513).readByteArray()
        if (bytes.size > 512) return@post call.respond(HttpStatusCode.PayloadTooLarge)
        val event = try {
            Json.decodeFromString<AnalyticsRequest>(bytes.decodeToString())
        } catch (_: SerializationException) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        if (!event.isValid()) return@post call.respond(HttpStatusCode.BadRequest)
        call.respond(publish(event))
    }
}
