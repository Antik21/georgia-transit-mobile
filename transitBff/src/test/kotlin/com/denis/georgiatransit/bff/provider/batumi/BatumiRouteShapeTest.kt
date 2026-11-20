package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatumiRouteShapeTest {
    @Test
    fun `matches the ordered service run across the seam of a reversed closed shape`() {
        val stops = listOf(
            FixtureStop("A", 41.6200, 41.6000),
            FixtureStop("B", 41.6260, 41.6060),
            FixtureStop("C", 41.6320, 41.6000),
            FixtureStop("D", 41.6260, 41.5940),
        )
        val bendAfterA = GeoPoint(41.6200, 41.6060)
        val seam = GeoPoint(41.6320, 41.6060)
        val (a, b, c, d) = stops.map(FixtureStop::position)
        val shape = listOf(seam, c, d, a, bendAfterA, b, seam).asReversed()
        val fixture = PlanningFixture(listOf(FixtureRoute("10", stops, shape = shape)))

        val pattern = BatumiNetworkGraphBuilder.build(fixture.catalog, PlanNow).graph.patterns.single()

        assertFalse(pattern.segmentApproximate.any { it })
        assertTrue(bendAfterA in pattern.segmentGeometry[0])
        assertTrue(seam in pattern.segmentGeometry[1])
    }

    @Test
    fun `matches nearby stops on opposite sides of a long turnaround`() {
        val stops = listOf(
            FixtureStop("A", 41.6200, 41.6000),
            FixtureStop("B", 41.6260, 41.6000),
            FixtureStop("C", 41.6259, 41.6003),
            FixtureStop("D", 41.6200, 41.6030),
        )
        val (a, b, c, d) = stops.map(FixtureStop::position)
        val turnaround = GeoPoint(41.6400, 41.6060)
        val bendAfterC = GeoPoint(41.6200, 41.6003)
        val shape = listOf(a, b, GeoPoint(41.6400, 41.6000), turnaround, c, bendAfterC, d)
        val fixture = PlanningFixture(listOf(FixtureRoute("10A", stops, shape = shape)))

        val pattern = BatumiNetworkGraphBuilder.build(fixture.catalog, PlanNow).graph.patterns.single()

        assertFalse(pattern.segmentApproximate.any { it })
        assertTrue(turnaround in pattern.segmentGeometry[1])
        assertTrue(bendAfterC in pattern.segmentGeometry[2])
    }

    @Test
    fun `a misordered stop does not break later sections of the same service run`() {
        val stops = eastLine("A", latitude = 41.620, count = 8)
        val shape = stops.map(FixtureStop::position)
        val serviceOrder = listOf(stops[0], stops[1], stops[5], stops[2], stops[3], stops[4], stops[6], stops[7])
        val fixture = PlanningFixture(listOf(FixtureRoute("10A", serviceOrder, shape = shape)))

        val pattern = BatumiNetworkGraphBuilder.build(fixture.catalog, PlanNow).graph.patterns.single()

        assertContentEquals(booleanArrayOf(false, true, true, false, false, false, false), pattern.segmentApproximate)
    }
}
