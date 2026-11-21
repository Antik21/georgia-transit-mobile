package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapPointPickerTest {
    private val target = MapPickTarget(500, 1000, yFraction = 0.4, markerScale = 1f)

    @Test
    fun preservesTheNormalViewportCentreAndKeepsThePinClearInACompactViewport() {
        val normal = assertNotNull(
            mapPickTarget(1080, 2400, 220, 300, markerAboveTipPx = 147f, markerBelowTipPx = 26f),
        )
        assertEquals(1050.0, normal.yFraction * normal.mapHeightPx, absoluteTolerance = 0.001)
        assertEquals(1f, normal.markerScale)

        val compact = assertNotNull(
            mapPickTarget(840, 1680, 310, 1000, markerAboveTipPx = 147f, markerBelowTipPx = 26f),
        )
        val tipY = compact.yFraction * compact.mapHeightPx
        assertEquals(457.0, tipY, absoluteTolerance = 0.001)
        assertTrue(tipY + 26 <= 680)
        assertEquals(1f, compact.markerScale)
    }

    @Test
    fun scalesThePinToFitWhenTheClearSpaceIsShorterThanThePin() {
        val target = assertNotNull(
            mapPickTarget(840, 1680, 540, 1000, markerAboveTipPx = 147f, markerBelowTipPx = 26f),
        )
        val tipY = target.yFraction * target.mapHeightPx
        assertTrue(target.markerScale < 1f)
        assertTrue(tipY - 147 * target.markerScale >= 539.999)
        assertTrue(tipY + 26 * target.markerScale <= 680.001)
        assertNull(mapPickTarget(840, 1680, 680, 1000, markerAboveTipPx = 147f, markerBelowTipPx = 26f))
    }

    @Test
    fun resolvesPointAtMarkerAboveOverlay() {
        val picker = MapPointPicker()
        picker.start(JourneyPlanSide.Origin, target)
        val request = assertNotNull(picker.request)
        assertEquals(target.xFraction, request.xFraction)
        assertEquals(target.yFraction, request.yFraction)
        val chosen = GeoPoint(41.65, 41.63)
        assertEquals(chosen, picker.resolve(MapPlatformEvent.MapPointResolved(request.id, chosen), JourneyPlanSide.Origin, target))
        assertTrue(picker.canConfirm(target))
    }

    @Test
    fun rejectsLateResultsAndChangesInLayoutOrEndpoint() {
        val picker = MapPointPicker()
        val chosen = GeoPoint(41.65, 41.63)
        picker.start(JourneyPlanSide.Origin, target)
        val oldId = assertNotNull(picker.request).id
        picker.cancel()
        picker.start(JourneyPlanSide.Destination, target)
        val currentId = assertNotNull(picker.request).id
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(oldId, chosen), JourneyPlanSide.Destination, target))
        assertNotNull(picker.request)
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(currentId, chosen), JourneyPlanSide.Destination, target.copy(mapWidthPx = 600)))
        picker.start(JourneyPlanSide.Origin, target)
        val nextId = assertNotNull(picker.request).id
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(nextId, chosen), JourneyPlanSide.Destination, target))
    }

    @Test
    fun cameraMovementAndInvalidCoordinatesCannotConfirm() {
        val picker = MapPointPicker()
        picker.start(JourneyPlanSide.Origin, target)
        val id = assertNotNull(picker.request).id
        picker.motionStarted()
        assertFalse(picker.canConfirm(target))
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(id, GeoPoint(41.65, 41.63)), JourneyPlanSide.Origin, target))
        picker.motionSettled()
        picker.start(JourneyPlanSide.Origin, target)
        val nextId = assertNotNull(picker.request).id
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(nextId, GeoPoint(100.0, 41.63)), JourneyPlanSide.Origin, target))
        assertTrue(picker.canConfirm(target))
    }
}
