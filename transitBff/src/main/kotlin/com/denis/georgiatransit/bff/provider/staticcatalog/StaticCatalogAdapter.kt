package com.denis.georgiatransit.bff.provider.staticcatalog

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

internal enum class StaticCatalog(
    val id: String,
    val center: GeoPoint,
    val area: MapArea,
    val provider: TelemetryProvider,
    val sourceUrl: String,
) {
    Zugdidi(
        KnownCityIds.Zugdidi, GeoPoint(42.5088, 41.8662), MapArea.Zugdidi, TelemetryProvider.ZUGDIDI_STATIC,
        "https://yandex.com/maps/122069/zugdidi/transport/",
    ),
    Borjomi(
        KnownCityIds.Borjomi, GeoPoint(41.8404, 43.3820), MapArea.Borjomi, TelemetryProvider.BORJOMI_STATIC,
        "https://yandex.com/maps/155447/borjomi/transport/",
    ),
}

/** Static catalog loaded once from an operator-provisioned ordered-stop file. */
internal class StaticCatalogAdapter(
    csv: Path,
    production: Boolean,
    private val catalog: StaticCatalog,
) : CityTransitProviderAdapter {
    private val names = CatalogNames(catalog.id)
    private val namespace = if (production) "static" else "fixture"
    override val telemetryProvider = if (production) catalog.provider else TelemetryProvider.FIXTURE
    override val city = City(
        id = catalog.id,
        name = names.city,
        center = catalog.center,
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
        mapBounds = catalog.area.bounds,
        attribution = listOf(
            AttributionLink("yandex-maps", names.attribution, catalog.sourceUrl),
        ),
    )

    private val input = readCatalogDirections(csv).also { directions ->
        val bounds = catalog.area.bounds
        require(directions.all { direction ->
            direction.stops.all { stop ->
                stop.position.latitude in bounds.south..bounds.north &&
                    stop.position.longitude in bounds.west..bounds.east
            }
        }) { "${catalog.id} stop is outside the configured basemap area" }
    }
    private val updatedAt = Files.getLastModifiedTime(csv).toInstant().toString()
    private val stopsById = input
        .flatMap { direction -> direction.stops.distinctBy { it.id }.map { it to direction.routeId } }
        .groupBy(Pair<CatalogStop, String>::first, Pair<CatalogStop, String>::second)
        .map { (stop, routes) ->
            stop.id to Stop(
                id = publicId(PublicEntityType.Stop, stop.id),
                providerId = stop.id,
                code = stop.id,
                name = names.label(stop.sourceName),
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
            val label = if (catalog == StaticCatalog.Zugdidi && number == 2 && direction.isLoop) {
                names.loop
            } else {
                names.direction(origin, destination)
            }
            Direction(id = direction.id, name = label, headsign = destination)
        }
        Route(
            id = id,
            providerId = number.toString(),
            shortName = if (catalog == StaticCatalog.Borjomi && number == 5) localizedDirections.first().name.ka else number.toString(),
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
        value = encodePolyline(direction(routeId, directionId).stops.map(CatalogStop::position)),
        updatedAt = updatedAt,
    )

    override suspend fun stopDirectory(locale: String): List<Stop> = stopsById.values.toList()

    override suspend fun vehicles(routeId: String, directionId: String?): RealtimeVehicles =
        throw ProviderCapabilityUnavailable("Vehicle positions are unavailable for ${catalog.id}")

    override suspend fun arrivals(stopId: String, limit: Int, locale: String): RealtimeArrivals =
        throw ProviderCapabilityUnavailable("Arrivals are unavailable for ${catalog.id}")

    override suspend fun journeys(query: JourneyQuery): List<Journey> =
        throw ProviderCapabilityUnavailable("Journey planning is unavailable for ${catalog.id}")

    private fun direction(routeId: String, directionId: String): CatalogDirection =
        directionsById[directionId]?.takeIf { it.routeId == routeId }
            ?: throw ProviderRouteNotFound("Direction was not found for this route")

    private val CatalogDirection.routeId get() = publicId(PublicEntityType.Route, route.toString())
    private val CatalogDirection.id get() = publicId(PublicEntityType.Direction, "$route-$direction")

    private fun publicId(type: PublicEntityType, suffix: String): String =
        formatPublicId(catalog.id, namespace, type, suffix)
}
