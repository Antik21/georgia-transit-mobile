package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import kotlin.test.Test
import kotlin.test.assertEquals

class MeasuredPolylineTest {
    private val start = GeoPoint(0.0, 0.0)
    private val middle = GeoPoint(0.0, 0.001)
    private val end = GeoPoint(0.0, 0.002)
    private val points = listOf(start, start, middle, middle, end, end)
    private val line = MeasuredPolyline(points)
    private val halfway = greatCircleMeters(start, middle)

    @Test
    fun `slice retains repeated interior points and excludes equal endpoint distances`() {
        assertEquals(listOf(start, middle, middle, end), line.slice(0.0, line.lengthMeters))
        assertEquals(listOf(middle, end), line.slice(halfway, line.lengthMeters))
        assertEquals(listOf(middle, middle), line.slice(halfway, halfway))
    }
}
