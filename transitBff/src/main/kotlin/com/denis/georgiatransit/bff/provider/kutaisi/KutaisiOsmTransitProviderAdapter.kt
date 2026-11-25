package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.AttributionLink
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityAvailability
import com.denis.georgiatransit.bff.api.CityCapabilities
import com.denis.georgiatransit.bff.api.CityReadiness
import com.denis.georgiatransit.bff.api.CitySource
import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.api.LocalizedText
import com.denis.georgiatransit.bff.api.PublicEntityType
import com.denis.georgiatransit.bff.api.Stop
import com.denis.georgiatransit.bff.api.TransitModeValues
import com.denis.georgiatransit.bff.api.formatPublicId
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.provider.CityTransitProviderAdapter
import com.denis.georgiatransit.bff.provider.JourneyQuery
import com.denis.georgiatransit.bff.provider.NormalizedResponseValidator
import com.denis.georgiatransit.bff.provider.ProviderCapabilityUnavailable
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Static, ODbL-licensed stop coordinates. The versioned extract is loaded once at registration. */
internal class KutaisiOsmTransitProviderAdapter : CityTransitProviderAdapter {
    override val telemetryProvider = TelemetryProvider.KUTAISI_OSM

    override val city = City(
        id = KnownCityIds.Kutaisi,
        name = LocalizedText(ru = "Кутаиси", en = "Kutaisi", ka = "ქუთაისი", th = "คูไตซี"),
        center = GeoPoint(latitude = 42.271608, longitude = 42.705448),
        defaultZoom = 15.25,
        capabilities = CityCapabilities(
            routes = false,
            stops = true,
            routeGeometry = false,
            vehiclePositions = false,
            officialArrivals = false,
            tripPlanning = false,
            arrivals = false,
        ),
        availability = CityAvailability(CityReadiness.PRODUCTION_READY, CitySource.REVIEWED_ADAPTER),
        attribution = listOf(
            AttributionLink(
                id = "openstreetmap",
                label = LocalizedText(
                    ru = "Остановки © OpenStreetMap",
                    en = "Stops © OpenStreetMap",
                    ka = "გაჩერებები © OpenStreetMap",
                ),
                url = "https://www.openstreetmap.org/copyright",
            ),
        ),
    )

    private val stops = loadStopSnapshot().stops.map(OsmStop::normalize).also {
        NormalizedResponseValidator.stops(city, it)
    }

    override suspend fun stopDirectory(locale: String): List<Stop> = stops

    override suspend fun routes(locale: String, mode: String?): Nothing = unavailable()
    override suspend fun route(routeId: String, locale: String): Nothing = unavailable()
    override suspend fun directionStops(routeId: String, directionId: String, locale: String): Nothing = unavailable()
    override suspend fun shape(routeId: String, directionId: String): Nothing = unavailable()
    override suspend fun vehicles(routeId: String, directionId: String?): Nothing = unavailable()
    override suspend fun arrivals(stopId: String, limit: Int, locale: String): Nothing = unavailable()
    override suspend fun journeys(query: JourneyQuery): Nothing = unavailable()

    private fun unavailable(): Nothing =
        throw ProviderCapabilityUnavailable("Only the static stop directory is available")
}

private val Sha256Pattern = Regex("[0-9a-f]{64}")

private fun loadStopSnapshot(): OsmStopSnapshot {
    val input = checkNotNull(KutaisiOsmTransitProviderAdapter::class.java.getResourceAsStream("/kutaisi-osm/stops.json")) {
        "The Kutaisi OSM stop snapshot is missing"
    }
    val snapshot = input.bufferedReader(Charsets.UTF_8).use { Json.decodeFromString<OsmStopSnapshot>(it.readText()) }
    Instant.parse(snapshot.sourceTimestamp)
    check(
        snapshot.boundaryRelation == 2024547L &&
            Sha256Pattern.matches(snapshot.sourcePbfSha256) &&
            snapshot.attribution.isNotBlank() &&
            snapshot.license == "https://opendatacommons.org/licenses/odbl/1-0/" &&
            snapshot.stops.isNotEmpty(),
    ) { "Invalid Kutaisi OSM stop snapshot" }
    return snapshot
}

@Serializable
private data class OsmStopSnapshot(
    val attribution: String,
    val license: String,
    val sourceTimestamp: String,
    val sourcePbfSha256: String,
    val boundaryRelation: Long,
    val stops: List<OsmStop>,
)

@Serializable
private data class OsmStop(
    val nodeId: Long,
    val latitude: Double,
    val longitude: Double,
    val ref: String?,
    val name: String?,
    val nameKa: String?,
    val nameEn: String?,
    val nameRu: String?,
) {
    fun normalize(): Stop {
        val providerId = nodeId.toString()
        val sourceName = name ?: nameKa ?: nameEn ?: nameRu
        return Stop(
            id = formatPublicId(KnownCityIds.Kutaisi, "osm", PublicEntityType.Stop, providerId),
            providerId = providerId,
            code = ref ?: providerId,
            name = LocalizedText(
                ru = nameRu ?: sourceName ?: "Автобусная остановка",
                en = nameEn ?: sourceName ?: "Bus stop",
                ka = nameKa ?: sourceName ?: "ავტობუსის გაჩერება",
            ),
            position = GeoPoint(latitude, longitude),
            routeIds = emptyList(),
            mode = TransitModeValues.Bus,
        )
    }
}
