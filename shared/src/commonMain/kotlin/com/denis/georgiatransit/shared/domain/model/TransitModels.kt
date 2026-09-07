package com.denis.georgiatransit.shared.domain.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/** Identifiers received from the Transit BFF are opaque values. Never split or rebuild them. */
@Serializable @JvmInline value class CityId(val value: String)
@Serializable @JvmInline value class RouteId(val value: String)
@Serializable @JvmInline value class DirectionId(val value: String)
@Serializable @JvmInline value class StopId(val value: String)
@Serializable @JvmInline value class VehicleId(val value: String)
@Serializable @JvmInline value class JourneyId(val value: String)
@Serializable @JvmInline value class ProviderId(val value: String)
@Serializable @JvmInline value class TripId(val value: String)

/** WGS84 coordinate. Values are validated at the BFF mapping boundary. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

@Serializable
data class LocalizedText(val ru: String, val en: String, val ka: String) {
    fun forLocale(locale: TransitLocale): String = when (locale) {
        TransitLocale.Russian -> ru
        TransitLocale.English -> en
        TransitLocale.Georgian -> ka
    }

    companion object { fun fromLegacy(value: String) = LocalizedText(value, value, value) }
}

@Serializable enum class TransitLocale { Russian, English, Georgian }
@Serializable enum class TransitMode { Bus, Metro, Tram, Ferry }
@Serializable enum class CityReadiness { DevelopmentFixture, ProductionReady, Unreviewed }
@Serializable enum class CitySource { Fixture, ReviewedAdapter, UnreviewedAdapter }

@Serializable
data class CityAvailability(val readiness: CityReadiness, val source: CitySource)

/**
 * The first five arguments retain the shell's public shape. New BFF names are explicit
 * accessors, keeping legacy UI and preview constructors free from DTO terminology.
 */
@Serializable
data class CityCapabilities(
    val stops: Boolean,
    val vehicles: Boolean,
    val arrivals: Boolean,
    val routeShapes: Boolean,
    val journeyPlanning: Boolean,
    val experimental: Boolean = false,
    val routes: Boolean = true,
    /** True only when arrivals come from a city's official provider. */
    val officialArrivals: Boolean = arrivals,
) {
    val routeGeometry: Boolean get() = routeShapes
    val vehiclePositions: Boolean get() = vehicles
    val tripPlanning: Boolean get() = journeyPlanning
}

/** A normalized attribution link shown by common UI without provider-specific types. */
@Serializable
data class TransitAttribution(
    val id: String,
    val label: LocalizedText,
    val url: String,
)

/** [name] remains the shell display adapter; normalized values are retained alongside it. */
@Serializable
data class TransitCity(
    val id: CityId,
    val name: String,
    val countryCode: String = "GE",
    val center: GeoPoint,
    val capabilities: CityCapabilities,
    val localizedName: LocalizedText = LocalizedText.fromLegacy(name),
    val defaultZoom: Double = 13.0,
    val availability: CityAvailability = CityAvailability(CityReadiness.Unreviewed, CitySource.UnreviewedAdapter),
    val attribution: List<TransitAttribution> = emptyList(),
)

@Serializable
data class TransitDirection(val id: DirectionId, val name: LocalizedText, val headsign: LocalizedText)

/** [name] and [colorArgb] remain adapters for current route UI. */
@Serializable
data class TransitRoute(
    val id: RouteId,
    val cityId: CityId,
    val shortName: String,
    val name: String,
    val colorArgb: Long,
    val providerId: ProviderId = ProviderId(""),
    val longName: LocalizedText = LocalizedText.fromLegacy(name),
    val textColorArgb: Long = 0xFFFFFFFF,
    val mode: TransitMode = TransitMode.Bus,
    val directions: List<TransitDirection> = emptyList(),
)

@Serializable
data class TransitStop(
    val id: StopId,
    val providerId: ProviderId,
    val code: String,
    val name: LocalizedText,
    val position: GeoPoint,
    val routeIds: List<RouteId>,
    val mode: TransitMode,
)

@Serializable data class TransitShape(val encodedPolyline: String, val precision: Int, val updatedAt: Instant)
@Serializable enum class VehiclePositionKind { Gps, Estimated, Unknown }

@Serializable
data class TransitVehicle(
    val id: VehicleId,
    val routeId: RouteId,
    val directionId: DirectionId?,
    val position: GeoPoint,
    val bearing: Double?,
    val nextStopId: StopId?,
    val observedAt: Instant?,
    val ageSeconds: Int?,
    val positionKind: VehiclePositionKind,
)

@Serializable enum class ArrivalSource { OfficialRealtime, AggregatorRealtime, Schedule, ClientEstimate }

@Serializable
data class TransitArrival(
    val stopId: StopId,
    val routeId: RouteId,
    val tripId: TripId?,
    val headsign: LocalizedText,
    val scheduledAt: Instant?,
    val expectedAt: Instant?,
    val expectedInMinutes: Int?,
    val realtime: Boolean,
    val cancelled: Boolean,
    val source: ArrivalSource,
)

@Serializable
data class TransitJourneyLeg(
    val routeId: RouteId,
    val directionId: DirectionId,
    val fromStopId: StopId,
    val toStopId: StopId,
    val departureAt: Instant,
    val arrivalAt: Instant,
)

/** App-owned category for an ordered journey segment; never exposes a provider mode. */
@Serializable
enum class JourneySegmentMode {
    Transit,
    Walk,
    Bicycle,
    Car,
    Other,
}

/** Full ordered itinerary; route/direction are absent when the segment is non-transit. */
@Serializable
data class TransitJourneySegment(
    val departureAt: Instant,
    val arrivalAt: Instant,
    val mode: JourneySegmentMode,
    val routeId: RouteId? = null,
    val directionId: DirectionId? = null,
    val fromStopId: StopId? = null,
    val toStopId: StopId? = null,
    val fromPosition: GeoPoint? = null,
    val toPosition: GeoPoint? = null,
)

@Serializable
data class TransitJourney(
    val id: JourneyId,
    val departureAt: Instant,
    val arrivalAt: Instant,
    val transfers: Int,
    val legs: List<TransitJourneyLeg>,
    val segments: List<TransitJourneySegment> = emptyList(),
)

@Serializable data class VehiclePage(val items: List<TransitVehicle>, val observedAt: Instant, val maxAgeSeconds: Int, val stale: Boolean)
@Serializable data class ArrivalPage(val items: List<TransitArrival>, val source: ArrivalSource, val observedAt: Instant, val stale: Boolean)
@Serializable
data class JourneyPage(
    val items: List<TransitJourney>,
    val observedAt: Instant,
    val source: ArrivalSource = ArrivalSource.Schedule,
    val realtime: Boolean = false,
    val stale: Boolean = false,
)
