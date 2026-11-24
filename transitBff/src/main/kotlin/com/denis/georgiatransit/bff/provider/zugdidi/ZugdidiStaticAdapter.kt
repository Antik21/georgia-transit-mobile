package com.denis.georgiatransit.bff.provider.zugdidi

import com.denis.georgiatransit.bff.api.AttributionLink
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.Direction
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.Journey
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.Route
import com.denis.georgiatransit.bff.api.Shape
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.TransitModeValues
import com.denis.georgiatransit.bff.api.formatPublicId
import com.denis.georgiatransit.bff.geo.encodePolyline
import com.denis.georgiatransit.bff.map.MapArea
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import com.denis.georgiatransit.bff.provider.ProviderRouteNotFound
import com.denis.georgiatransit.bff.provider.RealtimeArrivals
import com.denis.georgiatransit.bff.provider.RealtimeVehicles
import java.nio.file.Files
import java.nio.file.Path

private val routeColors = listOf("#2853A0", "#AC3C26", "#147666", "#764595", "#AA2961", "#374B76", "#9A4C20")

/** Static catalog loaded once from an operator-provisioned ordered-stop file. */
internal class ZugdidiStaticAdapter(csv: Path, production: Boolean) : CityTransitProviderAdapter {
    private val namespace = if (production) "static" else "fixture"
    override val telemetryProvider = if (production) TelemetryProvider.ZUGDIDI_STATIC else TelemetryProvider.FIXTURE
    override val city = City(
        id = KnownCityIds.Zugdidi,
        name = ZugdidiNames.city,
        center = GeoPoint(42.5088, 41.8662),
        defaultZoom = 13.5,
        capabilities = CityCapabilities(
            routes = true,
            stops = true,
            routeGeometry = true,
            vehiclePositions = false,
            officialArrivals = false,
            tripPlanning = false,
        ),
        availability = if (production) {
            CityAvailability(CityReadiness.PRODUCTION_READY, CitySource.REVIEWED_ADAPTER)
        } else {
            CityAvailability(CityReadiness.DEVELOPMENT_FIXTURE, CitySource.FIXTURE)
        },
        mapBounds = MapArea.Zugdidi.bounds,
        attribution = listOf(
            AttributionLink("yandex-maps", ZugdidiNames.attribution, "https://yandex.com/maps/122069/zugdidi/transport/"),
        ),
    )

    private val input = readZugdidiDirections(csv).also { directions ->
        val bounds = MapArea.Zugdidi.bounds
        require(directions.all { direction ->
            direction.stops.all { stop ->
                stop.position.latitude in bounds.south..bounds.north &&
                    stop.position.longitude in bounds.west..bounds.east
            }
        }) { "Zugdidi stop is outside the configured basemap area" }
    }
    private val updatedAt = Files.getLastModifiedTime(csv).toInstant().toString()
    private val stopsById = input
        .flatMap { direction -> direction.stops.distinctBy { it.id }.map { it to direction.routeId } }
        .groupBy(Pair<ZugdidiStop, String>::first, Pair<ZugdidiStop, String>::second)
        .map { (stop, routes) ->
            stop.id to Stop(
                id = publicId(PublicEntityType.Stop, stop.id),
                providerId = stop.id,
                code = stop.id,
                name = ZugdidiNames.label(stop.sourceName),
                position = stop.position,
                routeIds = routes.distinct(),
                mode = TransitModeValues.Bus,
            )
        }.toMap()

    private val directionsById = input.associateBy { it.id }
    private val routesById = input.groupBy { it.routeId }.mapValues { (id, directions) ->
        val number = directions.first().route
        val localizedDirections = directions.map { direction ->
            val origin = stopsById.getValue(direction.stops.first().id).name
            val destination = stopsById.getValue(direction.stops.last().id).name
            val label = if (number == 2 && direction.isLoop) {
                ZugdidiNames.loop
            } else {
                ZugdidiNames.direction(origin, destination)
            }
            Direction(id = direction.id, name = label, headsign = destination)
        }
        Route(
            id = id,
            providerId = number.toString(),
            shortName = number.toString(),
            longName = localizedDirections.first().name,
            color = routeColors[(number - 1) % routeColors.size],
            textColor = "#FFFFFF",
            mode = TransitModeValues.Bus,
            directions = localizedDirections,
        )
    }

    override suspend fun routes(locale: String, mode: String?): List<Route> =
        routesById.values.filter { mode == null || mode == it.mode }

    override suspend fun route(routeId: String, locale: String): Route =
        routesById[routeId] ?: throw ProviderRouteNotFound("Route was not found")

    override suspend fun directionStops(routeId: String, directionId: String, locale: String): List<Stop> {
        val direction = direction(routeId, directionId)
        // The BFF stop directory has unique stop IDs. The return to the first stop stays in the shape.
        val stops = if (direction.isLoop) direction.stops.dropLast(1) else direction.stops
        return stops.map { stopsById.getValue(it.id) }
    }

    override suspend fun shape(routeId: String, directionId: String): Shape = Shape(
        precision = 5,
        value = encodePolyline(direction(routeId, directionId).stops.map(ZugdidiStop::position)),
        updatedAt = updatedAt,
    )

    override suspend fun stopDirectory(locale: String): List<Stop> = stopsById.values.toList()

    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles =
        throw ProviderCapabilityUnavailable("Vehicle positions are unavailable for Zugdidi")

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals =
        throw ProviderCapabilityUnavailable("Arrivals are unavailable for Zugdidi")

    override suspend fun journeys(query: JourneyQuery): List<Journey> =
        throw ProviderCapabilityUnavailable("Journey planning is unavailable for Zugdidi")

    private fun direction(routeId: String, directionId: String): ZugdidiDirection =
        directionsById[directionId]?.takeIf { it.routeId == routeId }
            ?: throw ProviderRouteNotFound("Direction was not found for this route")

    private val ZugdidiDirection.routeId get() = publicId(PublicEntityType.Route, route.toString())
    private val ZugdidiDirection.id get() = publicId(PublicEntityType.Direction, "$route-$direction")

    private fun publicId(type: PublicEntityType, suffix: String): String =
        formatPublicId(KnownCityIds.Zugdidi, namespace, type, suffix)
}
