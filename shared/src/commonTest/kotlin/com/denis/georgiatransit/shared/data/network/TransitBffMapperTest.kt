package com.denis.georgiatransit.shared.data.network

import com.denis.georgiatransit.shared.domain.model.ArrivalSource
import com.denis.georgiatransit.shared.domain.model.CityReadiness
import com.denis.georgiatransit.shared.domain.model.CitySource
import com.denis.georgiatransit.shared.domain.model.JourneySegmentMode
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.model.VehiclePositionKind
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class TransitBffMapperTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun cityDtoUsesPublishedWireNamesAndMapsCapabilitiesAndAvailability() {
        val encoded =
            """{"id":"demo","name":{"ru":"Демо","en":"Demo","ka":"დემო"},"center":{"latitude":41.715137,"longitude":44.827096},"defaultZoom":13.0,"capabilities":{"routes":true,"stops":true,"routeGeometry":true,"vehiclePositions":true,"officialArrivals":true,"tripPlanning":true,"arrivals":true},"availability":{"readiness":"DEVELOPMENT_FIXTURE","source":"FIXTURE"},"attribution":[]}"""

        val dto = json.decodeFromString<CityDto>(encoded)
        val city = dto.toDomain()

        assertEquals(encoded, json.encodeToString(dto))
        assertEquals("demo", city.id.value)
        assertEquals("Demo", city.name)
        assertEquals("დემო", city.localizedName.ka)
        assertEquals(41.715137, city.center.latitude)
        assertEquals(13.0, city.defaultZoom)
        assertEquals(true, city.capabilities.routes)
        assertEquals(true, city.capabilities.routeGeometry)
        assertEquals(true, city.capabilities.vehiclePositions)
        assertEquals(true, city.capabilities.officialArrivals)
        assertEquals(true, city.capabilities.arrivals)
        assertEquals(true, city.capabilities.tripPlanning)
        assertEquals(CityReadiness.DevelopmentFixture, city.availability.readiness)
        assertEquals(CitySource.Fixture, city.availability.source)
    }

    @Test
    fun routeStopShapeVehicleArrivalAndJourneyDtosMapWithoutRebuildingOpaqueIds() {
        val routeId = "demo:fixture:route:blue"
        val directionId = "demo:fixture:direction:blue-outbound"
        val stopId = "demo:fixture:stop:center"
        val route = json.decodeFromString<RouteDto>(
            """{"id":"$routeId","providerId":"blue","shortName":"D1","longName":{"ru":"Р","en":"Route","ka":"მ"},"color":"#0057B8","textColor":"#FFFFFF","mode":"bus","directions":[{"id":"$directionId","name":{"ru":"Туда","en":"Outbound","ka":"გასვლა"},"headsign":{"ru":"Парк","en":"Park","ka":"პარკი"}}]}""",
        ).toDomain(com.denis.georgiatransit.shared.domain.model.CityId("demo"))
        val stop = json.decodeFromString<StopDto>(
            """{"id":"$stopId","providerId":"center","code":"D001","name":{"ru":"Центр","en":"Center","ka":"ცენტრი"},"position":{"latitude":41.715137,"longitude":44.827096},"routeIds":["$routeId"],"mode":"metro"}""",
        ).toDomain()
        val shape = json.decodeFromString<ShapeDto>(
            """{"format":"encoded_polyline","precision":5,"value":"_p~iF~ps|U","updatedAt":"2030-01-01T00:00:00Z"}""",
        ).toDomain()
        val vehicles = json.decodeFromString<VehiclePageDto>(
            """{"items":[{"id":"demo:fixture:vehicle:blue-01","routeId":"$routeId","directionId":"$directionId","position":{"latitude":41.716123,"longitude":44.829221},"bearing":62.0,"nextStopId":"$stopId","observedAt":"2030-01-01T00:00:00Z","ageSeconds":4,"positionKind":"GPS"}],"observedAt":"2030-01-01T00:00:00Z","maxAgeSeconds":30,"stale":false}""",
        ).toDomain()
        val arrivals = json.decodeFromString<ArrivalPageDto>(
            """{"items":[{"stopId":"$stopId","routeId":"$routeId","tripId":"blue-trip-01","headsign":{"ru":"Парк","en":"Park","ka":"პარკი"},"scheduledAt":"2030-01-01T00:05:00Z","expectedAt":"2030-01-01T00:04:00Z","expectedInMinutes":4,"realtime":true,"cancelled":false,"source":"OFFICIAL_REALTIME"}],"source":"OFFICIAL_REALTIME","observedAt":"2030-01-01T00:00:00Z","stale":false}""",
        ).toDomain()
        val journeys = json.decodeFromString<JourneyPageDto>(
            """{"items":[{"id":"demo:fixture:journey:blue-direct","departureAt":"2030-01-01T00:00:00Z","arrivalAt":"2030-01-01T00:15:00Z","transfers":0,"legs":[{"routeId":"$routeId","directionId":"$directionId","fromStopId":"$stopId","toStopId":"demo:fixture:stop:park","departureAt":"2030-01-01T00:00:00Z","arrivalAt":"2030-01-01T00:15:00Z"}]}],"observedAt":"2030-01-01T00:00:00Z"}""",
        ).toDomain()

        assertEquals(routeId, route.id.value)
        assertEquals(routeId, stop.routeIds.single().value)
        assertEquals(TransitMode.Metro, stop.mode)
        assertEquals("_p~iF~ps|U", shape.encodedPolyline)
        assertEquals("2030-01-01T00:00:00Z", shape.updatedAt.toString())
        assertEquals(VehiclePositionKind.Gps, vehicles.items.single().positionKind)
        assertEquals(directionId, vehicles.items.single().directionId?.value)
        assertEquals(ArrivalSource.OfficialRealtime, arrivals.source)
        assertEquals("blue-trip-01", arrivals.items.single().tripId?.value)
        assertEquals(routeId, journeys.items.single().legs.single().routeId.value)
        assertEquals(listOf(JourneySegmentMode.Transit), journeys.items.single().segments.map { it.mode })
    }

    @Test
    fun cityAttributionAndCompleteJourneySegmentsMapThroughSharedDomainWithoutProviderTypes() {
        val city = json.decodeFromString<CityDto>(
            """{"id":"tbilisi","name":{"ru":"Тбилиси","en":"Tbilisi","ka":"თბილისი"},"center":{"latitude":41.715137,"longitude":44.827096},"defaultZoom":12.5,"capabilities":{"routes":false,"stops":false,"routeGeometry":false,"vehiclePositions":false,"officialArrivals":false,"tripPlanning":true,"arrivals":true},"availability":{"readiness":"PRODUCTION_READY","source":"REVIEWED_ADAPTER"},"attribution":[{"id":"transitous","label":{"ru":"Источники","en":"Sources","ka":"წყაროები"},"url":"https://transitous.org/sources/"}]}""",
        ).toDomain()
        val journey = json.decodeFromString<JourneyPageDto>(
            """{"items":[{"id":"tbilisi:transitous:journey:opaque","departureAt":"2030-01-01T00:00:00Z","arrivalAt":"2030-01-01T00:10:00Z","transfers":0,"legs":[{"routeId":"tbilisi:transitous:route:opaque","directionId":"tbilisi:transitous:direction:opaque","fromStopId":"tbilisi:transitous:stop:one","toStopId":"tbilisi:transitous:stop:two","departureAt":"2030-01-01T00:05:00Z","arrivalAt":"2030-01-01T00:10:00Z"}],"segments":[{"departureAt":"2030-01-01T00:00:00Z","arrivalAt":"2030-01-01T00:05:00Z","mode":"WALK","fromPosition":{"latitude":41.70,"longitude":44.80},"toPosition":{"latitude":41.71,"longitude":44.81}},{"departureAt":"2030-01-01T00:05:00Z","arrivalAt":"2030-01-01T00:10:00Z","mode":"TRANSIT","routeId":"tbilisi:transitous:route:opaque","directionId":"tbilisi:transitous:direction:opaque","fromStopId":"tbilisi:transitous:stop:one","toStopId":"tbilisi:transitous:stop:two"}]}],"observedAt":"2030-01-01T00:00:00Z","source":"AGGREGATOR_REALTIME","realtime":true,"stale":true}""",
        ).toDomain()

        assertEquals(true, city.capabilities.arrivals)
        assertEquals(false, city.capabilities.officialArrivals)
        assertEquals("https://transitous.org/sources/", city.attribution.single().url)
        assertEquals("Sources", city.attribution.single().label.en)
        assertEquals(ArrivalSource.AggregatorRealtime, journey.source)
        assertEquals(true, journey.realtime)
        assertEquals(true, journey.stale)
        assertEquals(listOf(JourneySegmentMode.Walk, JourneySegmentMode.Transit), journey.items.single().segments.map { it.mode })
        assertEquals("tbilisi:transitous:route:opaque", journey.items.single().legs.single().routeId.value)
    }

    @Test
    fun dtoDecoderRejectsMissingRequiredAndUnknownEnumValues() {
        assertFailsWith<SerializationException> {
            json.decodeFromString<CityDto>("""{"id":"demo"}""")
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<RouteDto>(
                """{"id":"demo:fixture:route:blue","providerId":"blue","shortName":"D1","longName":{"ru":"Р","en":"Route","ka":"მ"},"color":"#0057B8","textColor":"#FFFFFF","mode":"train","directions":[]}""",
            )
        }
        assertFailsWith<SerializationException> {
            json.decodeFromString<ErrorEnvelopeDto>("""{"error":{"code":"NEW_ERROR","message":"x","requestId":"req"}}""")
        }
    }

    @Test
    fun mapperRejectsMalformedOpaqueIdsCoordinatesColorsAndNonUtcTimestamps() {
        val text = LocalizedTextDto("ru", "en", "ka")
        val capabilities = CityCapabilitiesDto(true, true, true, true, true, true)
        val availability = CityAvailabilityDto(CityReadinessDto.DevelopmentFixture, CitySourceDto.Fixture)

        assertFails { CityDto("DEMO", text, GeoPointDto(0.0, 0.0), 13.0, capabilities, availability).toDomain() }
        assertFails {
            RouteDto("demo:fixture:route", "p", "1", text, "#0057B8", "#FFFFFF", TransportModeDto.Bus, emptyList())
                .toDomain(com.denis.georgiatransit.shared.domain.model.CityId("demo"))
        }
        assertFails { CityDto("demo", text, GeoPointDto(91.0, 0.0), 13.0, capabilities, availability).toDomain() }
        assertFails {
            RouteDto("demo:fixture:route:blue", "p", "1", text, "#GG57B8", "#FFFFFF", TransportModeDto.Bus, emptyList())
                .toDomain(com.denis.georgiatransit.shared.domain.model.CityId("demo"))
        }
        assertFails { ShapeDto("encoded_polyline", 5, "value", "2030-01-01T00:00:00+03:00").toDomain() }
        assertFails {
            VehiclePageDto(emptyList(), "not-a-time", 0, false).toDomain()
        }
        assertFails {
            CityDto(
                "demo",
                text,
                GeoPointDto(0.0, 0.0),
                13.0,
                CityCapabilitiesDto(true, true, true, true, true, true, arrivals = false),
                availability,
            ).toDomain()
        }
        assertFails {
            json.decodeFromString<JourneyPageDto>(
                """{"items":[],"observedAt":"2030-01-01T00:00:00Z","source":"OFFICIAL_REALTIME","realtime":true,"stale":false}""",
            ).toDomain()
        }
    }
}
