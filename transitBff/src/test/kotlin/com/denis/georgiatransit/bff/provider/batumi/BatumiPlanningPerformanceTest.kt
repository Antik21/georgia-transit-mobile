package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.planning.PlanningBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatumiPlanningPerformanceTest {
    /** A 15 × 15 grid of two-way lines: 30 routes, 60 patterns, 1 020 stops, 90 live buses. */
    private fun syntheticCity(): Pair<PlanningFixture, List<FixtureRoute>> {
        val routes = mutableListOf<FixtureRoute>()
        repeat(15) { line ->
            val latitude = 41.600 + 0.006 * line
            val out = List(17) { FixtureStop("H${line}o$it", latitude, 41.600 + 0.0065 * it, status = 1) }
            val back = List(17) { FixtureStop("H${line}r$it", latitude + 0.0003, 41.600 + 0.0065 * (16 - it), status = 2) }
            routes += FixtureRoute("H$line", out + back)
        }
        repeat(15) { line ->
            val longitude = 41.600 + 0.0075 * line
            val out = List(17) { FixtureStop("V${line}o$it", 41.600 + 0.0053 * it, longitude, status = 1) }
            val back = List(17) { FixtureStop("V${line}r$it", 41.600 + 0.0053 * (16 - it), longitude + 0.0004, status = 2) }
            routes += FixtureRoute("V$line", out + back)
        }
        val fixture = PlanningFixture(routes)
        routes.forEach { route ->
            listOf(0, 8, 21).forEach { index -> fixture.vehicle(route.rawId, "${route.rawId}-$index", route.stops[index]) }
        }
        return fixture to routes
    }

    @Test
    fun `a full-size synthetic graph plans within the 300 ms budget`() {
        val (fixture, routes) = syntheticCity()
        val graph = BatumiNetworkGraphBuilder.build(fixture.catalog, PlanNow).graph
        assertEquals(60, graph.patterns.size)
        assertEquals(1_020, graph.stops.size)
        assertEquals(30, routes.size)

        val from = GeoPoint(41.6012, 41.6012)
        val to = GeoPoint(41.6830, 41.7030)
        repeat(3) { fixture.plan(from, to) }
        val durationsMillis = List(5) {
            val startedAt = System.nanoTime()
            val page = fixture.plan(from, to, budget = PlanningBudget(System.nanoTime() + 300_000_000L))
            assertTrue(page.items.isNotEmpty())
            (System.nanoTime() - startedAt) / 1_000_000
        }
        assertTrue(durationsMillis.max() < 300, "planning took $durationsMillis ms")
    }
}
