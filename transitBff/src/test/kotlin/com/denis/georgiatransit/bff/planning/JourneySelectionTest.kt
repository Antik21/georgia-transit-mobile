package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneyWaitBasis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JourneySelectionTest {
    private val point = GeoPoint(41.64, 41.6)

    /** One access walk followed by back-to-back rides that end exactly at [arrival]. */
    private fun option(arrival: Long, walkMeters: Double, vararg routes: String): PlannedItinerary {
        val walkEnd = walkingSeconds(walkMeters)
        val rideSpan = (arrival - walkEnd) / routes.size
        val legs = mutableListOf<PlannedLeg>(WalkLeg(null, 0, point, point, walkMeters, 0, walkEnd))
        routes.forEachIndexed { index, _ ->
            val start = walkEnd + index * rideSpan
            val end = if (index == routes.lastIndex) arrival else start + rideSpan
            legs += RideLeg(index, 0, 1, start, start, end, JourneyWaitBasis.LIVE_VEHICLE, "v")
        }
        return PlannedItinerary(0, legs, routes.toList())
    }

    private fun walk(meters: Double) = PlannedItinerary(
        departure = 0,
        legs = listOf(WalkLeg(null, null, point, point, meters, 0, walkingSeconds(meters))),
        routeSequence = emptyList(),
    )

    private val farWalk = walk(5_000.0)

    @Test
    fun `transit that neither arrives earlier nor walks less than walking directly is dropped`() {
        val shortWalk = walk(434.0)
        val dominated = option(shortWalk.arrival + 600, 1_100.0, "r1")
        val lessWalking = option(shortWalk.arrival + 300, 200.0, "r2")

        val selected = JourneySelection.select(listOf(dominated, lessWalking), shortWalk, JourneyPlanMode.FASTEST)

        assertEquals(listOf(shortWalk, lessWalking), selected)
    }

    @Test
    fun `the slack window also counts from an offerable direct walk`() {
        val shortWalk = walk(434.0)
        val tooLate = option(shortWalk.arrival + 901, 400.0, "r1")

        assertEquals(listOf(shortWalk), JourneySelection.select(listOf(tooLate), shortWalk, JourneyPlanMode.FASTEST))
    }

    @Test
    fun `options with the same route sequence are duplicates`() {
        val early = option(1_000, 300.0, "r1")
        val late = option(1_100, 100.0, "r1")

        assertEquals(listOf(early), JourneySelection.select(listOf(late, early), farWalk, JourneyPlanMode.FASTEST))
        assertEquals(listOf(late), JourneySelection.select(listOf(late, early), farWalk, JourneyPlanMode.LESS_WALKING))
    }

    @Test
    fun `options arriving more than max(15 min, 50 percent) after the earliest are dropped`() {
        val earliest = option(1_000, 500.0, "r1")
        val withinSlack = option(1_000 + 900, 100.0, "r2")
        val tooLate = option(1_000 + 901, 50.0, "r3")

        val selected = JourneySelection.select(listOf(tooLate, withinSlack, earliest), farWalk, JourneyPlanMode.FASTEST)

        assertEquals(listOf(earliest, withinSlack), selected)
    }

    @Test
    fun `a transfer option must save at least five minutes over fewer transfers`() {
        val direct = option(2_000, 200.0, "r1")
        val marginal = option(1_800, 200.0, "r2", "r3")
        val worthIt = option(1_700, 200.0, "r4", "r5")

        val selected = JourneySelection.select(listOf(direct, marginal, worthIt), farWalk, JourneyPlanMode.FASTEST)

        assertEquals(listOf(worthIt, direct), selected)
    }

    @Test
    fun `role picks are earliest, fewer transfers, then least walking, at most three`() {
        val earliest = option(1_000, 900.0, "r1", "r2")
        val direct = option(1_400, 200.0, "r3")
        val leastWalking = option(1_500, 100.0, "r4")
        val extra = option(1_450, 700.0, "r5")

        val selected = JourneySelection.select(listOf(extra, leastWalking, direct, earliest), farWalk, JourneyPlanMode.FASTEST)

        assertEquals(listOf(earliest, direct, leastWalking), selected)
    }

    @Test
    fun `walk-only is added up to 2 km or when it beats every transit option`() {
        val transit = option(1_000, 300.0, "r1")
        val shortWalk = walk(1_900.0)
        val fastLongWalk = PlannedItinerary(0, listOf(WalkLeg(null, null, point, point, 2_100.0, 0, 900)), emptyList())

        assertTrue(shortWalk in JourneySelection.select(listOf(transit), shortWalk, JourneyPlanMode.FASTEST))
        assertEquals(fastLongWalk, JourneySelection.select(listOf(transit), fastLongWalk, JourneyPlanMode.FASTEST).first())
        assertEquals(listOf(transit), JourneySelection.select(listOf(transit), farWalk, JourneyPlanMode.FASTEST))
        assertTrue(JourneySelection.select(emptyList(), farWalk, JourneyPlanMode.FASTEST).isEmpty())
    }

    @Test
    fun `walk-only replaces the third transit pick and modes reorder the same set`() {
        val earliest = option(1_000, 900.0, "r1", "r2")
        val direct = option(1_400, 200.0, "r3")
        val leastWalking = option(1_500, 100.0, "r4")
        val shortWalk = walk(1_500.0)
        val options = listOf(earliest, direct, leastWalking)

        val fastest = JourneySelection.select(options, shortWalk, JourneyPlanMode.FASTEST)
        assertEquals(listOf(earliest, shortWalk, direct), fastest)
        assertEquals(listOf(direct, earliest, shortWalk), JourneySelection.select(options, shortWalk, JourneyPlanMode.LESS_WALKING))
        assertEquals(listOf(direct, shortWalk, earliest), JourneySelection.select(options, shortWalk, JourneyPlanMode.FEWER_TRANSFERS))
    }
}
