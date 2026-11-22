package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneySegmentMode
import com.denis.georgiatransit.bff.geo.greatCircleMeters
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class UntimedJourneyPlannerTest {
    @Test
    fun `walking beats the nine stop loop between nearby Kutaisi stops`() {
        val route5 = listOf(
            GeoPoint(42.270156, 42.704700),
            GeoPoint(42.266528, 42.704382),
            GeoPoint(42.264312, 42.705663),
            GeoPoint(42.261798, 42.707634),
            GeoPoint(42.259003, 42.708991),
            GeoPoint(42.261394, 42.712499),
            GeoPoint(42.262615, 42.711124),
            GeoPoint(42.264473, 42.708924),
            GeoPoint(42.268282, 42.706376),
            GeoPoint(42.271422, 42.704973),
        )
        val plan = planner(mapOf("5" to route5)).planBetween(GeoPoint(42.2700, 42.7043), GeoPoint(42.2718, 42.7050))

        val walk = plan.items.single()
        assertEquals(JourneySegmentMode.WALK, walk.segments.single().mode)
        assertNotNull(walk.durationSeconds)
    }

    @Test
    fun `shorter rides with a modest walk rank above a door to door detour`() {
        val from = point(0)
        val to = point(5_000)
        val routes = linkedMapOf("detour" to listOf(from, point(2_500, 4_000), to))
        for (i in 1..4) {
            routes["direct-$i"] = listOf(point(50 * i), point(4_950))
        }

        val plan = planner(routes).planBetween(from, to)

        assertEquals(listOf("direct-1", "direct-2", "direct-3"), plan.items.map { option ->
            option.segments.single { it.mode == JourneySegmentMode.TRANSIT }.routeId?.substringAfterLast(':')
        })
    }

    @Test
    fun `a transfer on the same corridor must beat the direct ride`() {
        val from = point(0)
        val middle = point(2_500)
        val to = point(5_000)
        val plan = planner(
            mapOf("direct" to listOf(from, to), "first" to listOf(from, middle), "second" to listOf(middle, to)),
        ).planBetween(from, to)

        assertEquals("test:fixture:route:direct", plan.items.single().segments.single().routeId)
    }

    private fun planner(routes: Map<String, List<GeoPoint>>): UntimedJourneyPlanner {
        val points = routes.values.flatten().distinct()
        val index = points.withIndex().associate { it.value to it.index }
        val stops = points.mapIndexed { i, point -> TransitStop("test:fixture:stop:$i", point) }
        val patterns = routes.map { (id, route) ->
            TransitPattern(
                routeId = "test:fixture:route:$id",
                directionId = "test:fixture:direction:$id",
                stops = route.map(index::getValue).toIntArray(),
                segmentGeometry = route.zipWithNext { from, to -> listOf(from, to) },
                segmentApproximate = BooleanArray(route.size - 1),
                segmentMeters = route.zipWithNext(::greatCircleMeters).toDoubleArray(),
            )
        }
        return UntimedJourneyPlanner(TransitNetworkGraph(stops, patterns), "test", "fixture")
    }

    private fun UntimedJourneyPlanner.planBetween(from: GeoPoint, to: GeoPoint) = plan(
        JourneyPlanQuery(from, to, Instant.EPOCH, maxTransfers = 2, transferBufferSeconds = 60, locale = "en"),
        observedAt = Instant.EPOCH,
        stale = false,
        budget = PlanningBudget.unbounded(),
    )

    private fun point(eastMeters: Int, northMeters: Int = 0) =
        GeoPoint(northMeters / MetersPerDegree, eastMeters / MetersPerDegree)

    private companion object {
        const val MetersPerDegree = 111_195.0
    }
}
