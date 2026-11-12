package com.denis.georgiatransit.shared.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class JourneyEndpointPolicyTest {
    @Test
    fun atMostOneSideMayBeTheCurrentLocation() {
        val both = JourneyRequest(JourneyEndpoint.CurrentLocation, JourneyEndpoint.CurrentLocation)

        assertFalse(JourneyEndpointPolicy.isValid(both))
        assertIs<JourneyResolution.Invalid>(JourneyEndpointPolicy.resolve(both, A, TransitLocale.English))
    }

    @Test
    fun bothPointsAreRoundedToFourDecimalsAndTheQueryCarriesNoOriginKind() {
        val request = JourneyRequest(JourneyEndpoint.CurrentLocation, JourneyEndpoint.MapPoint(GeoPoint(41.72954999, 44.84206)))

        val ready = assertIs<JourneyResolution.Ready>(
            JourneyEndpointPolicy.resolve(request, GeoPoint(41.715149, -44.827151), TransitLocale.Russian),
        )

        assertEquals(GeoPoint(41.7151, -44.8272), ready.query.from)
        assertEquals(GeoPoint(41.7295, 44.8421), ready.query.to)
        assertEquals(TransitLocale.Russian, ready.query.locale)
        assertEquals(60, ready.query.transferBufferSeconds)
        // The wire model has exactly the same shape for a fix and for a map point.
        val swapped = JourneyEndpointPolicy.resolve(
            JourneyRequest(JourneyEndpoint.MapPoint(GeoPoint(41.715149, -44.827151)), request.destination),
            null, TransitLocale.Russian,
        )
        assertEquals(ready, swapped)
    }

    @Test
    fun pointsCloserThan150MetresAreAlreadyThereWithAWalkEstimate() {
        val near = GeoPoint(A.latitude + 0.001, A.longitude)
        val far = GeoPoint(A.latitude + 0.0014, A.longitude)

        val there = assertIs<JourneyResolution.AlreadyThere>(
            JourneyEndpointPolicy.resolve(JourneyRequest(JourneyEndpoint.MapPoint(A), JourneyEndpoint.MapPoint(near)), null, TransitLocale.English),
        )
        assertEquals(145, there.walkMeters)
        assertEquals(2, there.walkMinutes)
        assertIs<JourneyResolution.Ready>(
            JourneyEndpointPolicy.resolve(JourneyRequest(JourneyEndpoint.MapPoint(A), JourneyEndpoint.MapPoint(far)), null, TransitLocale.English),
        )
    }

    @Test
    fun currentLocationWithoutAFixAndOutOfContractOptionsAreNotPlanned() {
        assertIs<JourneyResolution.NeedsCurrentLocation>(
            JourneyEndpointPolicy.resolve(JourneyRequest(JourneyEndpoint.CurrentLocation, JourneyEndpoint.MapPoint(A)), null, TransitLocale.English),
        )
        val tooManyTransfers = JourneyRequest(JourneyEndpoint.MapPoint(A), JourneyEndpoint.MapPoint(B), maxTransfers = 3)
        val longBuffer = JourneyRequest(JourneyEndpoint.MapPoint(A), JourneyEndpoint.MapPoint(B), transferBuffer = 601.seconds)
        assertFalse(JourneyEndpointPolicy.isValid(tooManyTransfers))
        assertFalse(JourneyEndpointPolicy.isValid(longBuffer))
    }

    private companion object {
        val A = GeoPoint(41.6400, 41.6300)
        val B = GeoPoint(41.6500, 41.6400)
    }
}
