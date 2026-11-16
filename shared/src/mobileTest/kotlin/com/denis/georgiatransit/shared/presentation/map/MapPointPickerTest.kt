package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapPointPickerTest {
    @Test
    fun resolvesPointAtMarkerAboveOverlay() {
        val picker = MapPointPicker()
        picker.start(JourneyPlanSide.Origin, mapHeight = 1000, panelHeight = 200)
        val request = assertNotNull(picker.request)
        assertEquals(0.5, request.xFraction)
        assertEquals(0.4, request.yFraction)
        val chosen = GeoPoint(41.65, 41.63)
        assertEquals(chosen, picker.resolve(MapPlatformEvent.MapPointResolved(request.id, chosen), JourneyPlanSide.Origin, 1000, 200))
        assertTrue(picker.canConfirm(1000, 200))
    }

    @Test
    fun rejectsLateResultsAndChangesInLayoutOrEndpoint() {
        val picker = MapPointPicker()
        val chosen = GeoPoint(41.65, 41.63)
        picker.start(JourneyPlanSide.Origin, 1000, 200)
        val oldId = assertNotNull(picker.request).id
        picker.cancel()
        picker.start(JourneyPlanSide.Destination, 1000, 200)
        val currentId = assertNotNull(picker.request).id
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(oldId, chosen), JourneyPlanSide.Destination, 1000, 200))
        assertNotNull(picker.request)
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(currentId, chosen), JourneyPlanSide.Destination, 900, 200))
        picker.start(JourneyPlanSide.Origin, 1000, 200)
        val nextId = assertNotNull(picker.request).id
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(nextId, chosen), JourneyPlanSide.Destination, 1000, 200))
    }

    @Test
    fun cameraMovementAndInvalidCoordinatesCannotConfirm() {
        val picker = MapPointPicker()
        picker.start(JourneyPlanSide.Origin, 1000, 200)
        val id = assertNotNull(picker.request).id
        picker.motionStarted()
        assertFalse(picker.canConfirm(1000, 200))
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(id, GeoPoint(41.65, 41.63)), JourneyPlanSide.Origin, 1000, 200))
        picker.motionSettled()
        picker.start(JourneyPlanSide.Origin, 1000, 200)
        val nextId = assertNotNull(picker.request).id
        assertNull(picker.resolve(MapPlatformEvent.MapPointResolved(nextId, GeoPoint(100.0, 41.63)), JourneyPlanSide.Origin, 1000, 200))
        assertTrue(picker.canConfirm(1000, 200))
    }
}
