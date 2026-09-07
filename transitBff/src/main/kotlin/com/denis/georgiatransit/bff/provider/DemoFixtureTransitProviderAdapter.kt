package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.Arrival
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.JourneyLeg
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PositionKind
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.Vehicle
import java.time.Instant

/**
 * Explicitly synthetic local data. This adapter is constructed only by a development runtime with
 * BFF_FIXTURES_ENABLED=true and must never be registered by production startup.
 */
class DemoFixtureTransitProviderAdapter : CityTransitProviderAdapter {
    override val city = City(
        id = "demo",
        name = LocalizedText(ru = "Демо-город", en = "Demo City", ka = "დემო ქალაქი"),
        center = GeoPoint(latitude = 41.715_137, longitude = 44.827_096),
        defaultZoom = 13.0,
        capabilities = CityCapabilities(
            routes = true,
            stops = true,
            routeGeometry = true,
            vehiclePositions = true,
            officialArrivals = true,
            tripPlanning = true,
        ),
    )

    private val outbound = Direction(
        id = "demo:fixture:direction:blue-outbound",
        name = LocalizedText(ru = "В направлении из центра", en = "Outbound", ka = "გასვლა"),
        headsign = LocalizedText(ru = "Демо-парк", en = "Demo Park", ka = "დემო პარკი"),
    )
    private val inbound = Direction(
        id = "demo:fixture:direction:blue-inbound",
        name = LocalizedText(ru = "В центр", en = "Inbound", ka = "დაბრუნება"),
        headsign = LocalizedText(ru = "Демо-центр", en = "Demo Center", ka = "დემო ცენტრი"),
    )
    private val blueRoute = Route(
        id = "demo:fixture:route:blue",
        providerId = "blue",
        shortName = "D1",
        longName = LocalizedText(ru = "Демо-синяя линия", en = "Demo Blue Line", ka = "დემო ლურჯი ხაზი"),
        color = "#0057B8",
        textColor = "#FFFFFF",
        mode = "bus",
        directions = listOf(outbound, inbound),
    )
    private val stops = listOf(
        Stop(
            id = "demo:fixture:stop:center",
            providerId = "center",
            code = "D001",
            name = LocalizedText(ru = "Демо-центр", en = "Demo Center", ka = "დემო ცენტრი"),
            position = GeoPoint(latitude = 41.715_137, longitude = 44.827_096),
            routeIds = listOf(blueRoute.id),
            mode = "bus",
        ),
        Stop(
            id = "demo:fixture:stop:library",
            providerId = "library",
            code = "D002",
            name = LocalizedText(ru = "Демо-библиотека", en = "Demo Library", ka = "დემო ბიბლიოთეკა"),
            position = GeoPoint(latitude = 41.716_123, longitude = 44.829_221),
            routeIds = listOf(blueRoute.id),
            mode = "bus",
        ),
        Stop(
            id = "demo:fixture:stop:park",
            providerId = "park",
            code = "D003",
            name = LocalizedText(ru = "Демо-парк", en = "Demo Park", ka = "დემო პარკი"),
            position = GeoPoint(latitude = 41.718_310, longitude = 44.833_719),
            routeIds = listOf(blueRoute.id),
            mode = "bus",
        ),
    )

    override suspend fun routes(locale: String, mode: String?): List<Route> =
        listOf(blueRoute).filter { mode == null || it.mode == mode }

    override suspend fun route(routeId: String, locale: String): Route =
        blueRoute.takeIf { it.id == routeId } ?: throw ProviderRouteNotFound("Route was not found")

    override suspend fun directionStops(routeId: String, directionId: String, locale: String): List<Stop> {
        route(routeId, locale)
        return when (directionId) {
            outbound.id -> stops
            inbound.id -> stops.reversed()
            else -> throw ProviderRouteNotFound("Direction was not found for this route")
        }
    }

    override suspend fun shape(routeId: String, directionId: String): Shape {
        directionStops(routeId, directionId, "en")
        return Shape(
            precision = 5,
            value = "_p~iF~ps|U_ulLnnqC_mqNvxq`@",
            updatedAt = "2030-01-01T00:00:00Z",
        )
    }

    override suspend fun stopDirectory(locale: String): List<Stop> = stops

    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles {
        route(routeId, "en")
        if (directionId != null && directionId !in setOf(outbound.id, inbound.id)) {
            throw ProviderRouteNotFound("Direction was not found for this route")
        }
        val observedAt = Instant.now()
        val vehicle = Vehicle(
            id = "demo:fixture:vehicle:blue-01",
            routeId = blueRoute.id,
            directionId = outbound.id,
            position = GeoPoint(latitude = 41.716_123, longitude = 44.829_221),
            bearing = 62.0,
            nextStopId = stops[2].id,
            observedAt = observedAt.toString(),
            ageSeconds = 4,
            positionKind = PositionKind.GPS,
        )
        return RealtimeVehicles(
            items = listOf(vehicle).filter { directionId == null || it.directionId == directionId },
            observedAt = observedAt,
            maxAgeSeconds = 30,
            stale = false,
        )
    }

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals {
        if (stops.none { it.id == stopId }) throw ProviderStopNotFound("Stop was not found")
        val observedAt = Instant.now()
        val arrival = Arrival(
            stopId = stopId,
            routeId = blueRoute.id,
            tripId = "blue-trip-01",
            headsign = outbound.headsign,
            scheduledAt = observedAt.plusSeconds(300).toString(),
            expectedAt = observedAt.plusSeconds(240).toString(),
            expectedInMinutes = 4,
            realtime = true,
            cancelled = false,
            source = ArrivalSource.OFFICIAL_REALTIME,
        )
        return RealtimeArrivals(
            items = List(limit.coerceAtMost(1)) { arrival },
            source = ArrivalSource.OFFICIAL_REALTIME,
            observedAt = observedAt,
            stale = false,
        )
    }

    override suspend fun journeys(query: JourneyQuery): List<Journey> {
        val departure = query.departureAt
        return listOf(
            Journey(
                id = "demo:fixture:journey:blue-direct",
                departureAt = departure.toString(),
                arrivalAt = departure.plusSeconds(900).toString(),
                transfers = 0,
                legs = listOf(
                    JourneyLeg(
                        routeId = blueRoute.id,
                        directionId = outbound.id,
                        fromStopId = stops.first().id,
                        toStopId = stops.last().id,
                        departureAt = departure.toString(),
                        arrivalAt = departure.plusSeconds(900).toString(),
                    ),
                ),
            ),
        )
    }
}
