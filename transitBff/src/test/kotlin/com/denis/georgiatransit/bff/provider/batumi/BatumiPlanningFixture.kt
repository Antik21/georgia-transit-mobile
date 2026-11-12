package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLabel
import com.denis.georgiatransit.bff.api.JourneyPage
import com.denis.georgiatransit.bff.api.JourneySegment
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.Vehicle
import com.denis.georgiatransit.bff.planning.JourneyPlanQuery
import com.denis.georgiatransit.bff.planning.PlanningBudget
import com.denis.georgiatransit.bff.provider.BatumiCatalog
import com.denis.georgiatransit.bff.provider.BatumiLiveVehicle
import com.denis.georgiatransit.bff.provider.BatumiThetaCatalogParser
import com.denis.georgiatransit.bff.provider.JourneyPlanResponseValidator
import com.denis.georgiatransit.bff.provider.RealtimeJourneys
import java.time.Duration
import java.time.Instant

internal val PlanNow: Instant = Instant.parse("2026-11-12T10:00:00Z")

/** Speed fallback of the ADR 0012 model for raw route IDs missing from the speed table. */
internal const val FallbackMetersPerSecond = 25.1 / 3.6

internal const val MetersPerLatitudeDegree = 111_320.0

internal data class FixtureStop(val rawId: String, val latitude: Double, val longitude: Double, val status: Int = 1) {
    val position: GeoPoint get() = GeoPoint(latitude, longitude)
}

internal data class FixtureRoute(
    val rawId: String,
    val stops: List<FixtureStop>,
    val circle: Boolean = false,
    /** Defaults to a shape through every stop (closed for circle routes). */
    val shape: List<GeoPoint>? = null,
)

/** Stops every [lonStep] degrees along a line of latitude, ids `${prefix}0..`. */
internal fun eastLine(prefix: String, latitude: Double, count: Int, startLon: Double = 41.600, lonStep: Double = 0.004, status: Int = 1) =
    List(count) { FixtureStop("$prefix$it", latitude, startLon + it * lonStep, status) }

internal fun northLine(prefix: String, longitude: Double, count: Int, startLat: Double, latStep: Double = 0.004, status: Int = 1) =
    List(count) { FixtureStop("$prefix$it", startLat + it * latStep, longitude, status) }

/** Builds a Theta `getDbData` payload; shared stops (same raw ID) join several routes. */
internal fun catalogJson(routes: List<FixtureRoute>): String {
    val memberships = linkedMapOf<String, Pair<FixtureStop, MutableList<String>>>()
    routes.forEach { route ->
        route.stops.forEachIndexed { order, stop ->
            val entry = memberships.getOrPut(stop.rawId) { stop to mutableListOf() }
            entry.second += "\"${route.rawId}\":{\"Status\":${stop.status},\"Order\":${order + 1}}"
        }
    }
    val routeNames = routes.mapIndexed { index, route ->
        "{\"RouteIdGeoGps\":\"${route.rawId}\",\"RouteNameGeoGps\":\"${route.rawId}\",\"RouteNameGeoGpsKA\":\"${route.rawId}\"," +
            "\"RouteSortOrder\":${index + 1},\"RouteIsCircle\":${route.circle}}"
    }
    val stops = memberships.values.map { (stop, routeEntries) ->
        "{\"BusStopIdGeoGps\":\"${stop.rawId}\",\"BusStopNumber\":\"${stop.rawId}\",\"BusStopNameGeoGps\":\"${stop.rawId}\"," +
            "\"Lat\":${stop.latitude},\"Lon\":${stop.longitude},\"routes\":{${routeEntries.joinToString(",")}}}"
    }
    val shapes = routes.map { route ->
        val points = (route.shape ?: (if (route.circle) route.stops + route.stops.first() else route.stops).map(FixtureStop::position))
            .joinToString(",") { "{\"lat\":${it.latitude},\"lon\":${it.longitude}}" }
        "\"${route.rawId}\":[$points]"
    }
    return "{\"data\":{\"routesNames\":[${routeNames.joinToString(",")}],\"busStops\":[${stops.joinToString(",")}]," +
        "\"routeCoordinatesGrouped\":{${shapes.joinToString(",")}}}}"
}

internal class PlanningFixture(routes: List<FixtureRoute>, publishedAt: Instant = PlanNow) {
    val catalog: BatumiCatalog = BatumiThetaCatalogParser.parse(catalogJson(routes), publishedAt)
    val planning = BatumiJourneyPlanning(
        cityId = "batumi",
        providerId = "theta",
        catalogMaximumAge = Duration.ofDays(7),
        liveMaximumAge = Duration.ofSeconds(60),
        liveFreshAge = Duration.ofSeconds(15),
    ).also { it.publish(catalog, publishedAt) }
    private val vehicles = mutableMapOf<String, MutableList<BatumiLiveVehicle>>()

    fun routeId(rawRouteId: String): String = catalog.routes.single { it.shortName == rawRouteId }.id

    fun stopId(rawStopId: String): String = catalog.stops.single { it.code == rawStopId }.id

    fun vehicle(rawRouteId: String, name: String, at: FixtureStop) {
        val routeId = routeId(rawRouteId)
        vehicles.getOrPut(routeId, ::mutableListOf) += BatumiLiveVehicle(
            vehicle = Vehicle(
                id = "batumi:theta:vehicle:$name",
                routeId = routeId,
                position = at.position,
                observedAt = PlanNow.toString(),
                positionKind = PositionKind.GPS,
            ),
            status = at.status,
        )
    }

    fun plan(
        from: GeoPoint,
        to: GeoPoint,
        departureAt: Instant = PlanNow,
        maxTransfers: Int = 2,
        bufferSeconds: Int = 60,
        liveObservedAt: Instant = PlanNow,
        budget: PlanningBudget = PlanningBudget.unbounded(),
    ): RealtimeJourneys {
        val query = JourneyPlanQuery(from, to, departureAt, maxTransfers, bufferSeconds, "en")
        return planning.plan(query, budget, vehicles, liveObservedAt, PlanNow).also { result ->
            JourneyPlanResponseValidator.page(
                "batumi",
                JourneyPage(result.items, result.observedAt.toString(), result.source, result.realtime, result.stale),
            )
        }
    }
}

/** A point [northMeters] north and [eastMeters] east of [stop]. */
internal fun near(stop: FixtureStop, northMeters: Double = 30.0, eastMeters: Double = 0.0): GeoPoint = GeoPoint(
    stop.latitude + northMeters / MetersPerLatitudeDegree,
    stop.longitude + eastMeters / (MetersPerLatitudeDegree * kotlin.math.cos(Math.toRadians(stop.latitude))),
)

/** The single option, or the one labelled FASTEST on a multi-option page. */
internal fun RealtimeJourneys.fastest(): Journey =
    items.singleOrNull() ?: items.single { JourneyLabel.FASTEST in it.labels.orEmpty() }

internal val Journey.transitSegments: List<JourneySegment>
    get() = segments.filter { it.mode == JourneySegmentMode.TRANSIT }

internal val Journey.modes: List<JourneySegmentMode> get() = segments.map(JourneySegment::mode)

internal fun JourneySegment.departureEpoch(): Long = Instant.parse(departureAt).epochSecond

internal fun JourneySegment.arrivalEpoch(): Long = Instant.parse(arrivalAt).epochSecond
