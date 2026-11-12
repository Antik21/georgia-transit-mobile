package com.denis.georgiatransit.bff.planning

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.JourneyLabel.FASTEST
import com.denis.georgiatransit.bff.api.JourneyLabel.LEAST_WALKING
import com.denis.georgiatransit.bff.api.JourneyLabel.NO_TRANSFERS
import com.denis.georgiatransit.bff.api.JourneyLabel.OPTIMAL
import com.denis.georgiatransit.bff.api.JourneyLabel
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

    private fun select(transit: List<PlannedItinerary>, walk: PlannedItinerary = farWalk) =
        JourneySelection.select(transit, walk)

    private fun List<LabelledItinerary>.options() = map(LabelledItinerary::itinerary)

    private fun List<LabelledItinerary>.labelsOf(option: PlannedItinerary): List<JourneyLabel> =
        single { it.itinerary === option }.labels

    @Test
    fun `transit that neither arrives earlier nor walks less than walking directly is dropped`() {
        val shortWalk = walk(434.0)
        val dominated = option(shortWalk.arrival + 600, 1_100.0, "r1")
        val lessWalking = option(shortWalk.arrival + 300, 200.0, "r2")

        val selected = select(listOf(dominated, lessWalking), shortWalk)

        assertEquals(listOf(shortWalk, lessWalking), selected.options())
        assertEquals(listOf(OPTIMAL, FASTEST), selected.labelsOf(shortWalk))
        assertEquals(listOf(LEAST_WALKING), selected.labelsOf(lessWalking))
    }

    @Test
    fun `the slack window also counts from an offerable direct walk`() {
        val shortWalk = walk(434.0)
        val tooLate = option(shortWalk.arrival + 901, 400.0, "r1")

        assertEquals(listOf(shortWalk), select(listOf(tooLate), shortWalk).options())
    }

    @Test
    fun `duplicates by route sequence keep the lower generalized cost and a single option has no labels`() {
        val early = option(1_000, 900.0, "r1")
        val cheaper = option(1_100, 100.0, "r1")

        val selected = select(listOf(early, cheaper))

        assertEquals(listOf(cheaper), selected.options())
        assertEquals(emptyList(), selected.single().labels)
    }

    @Test
    fun `options arriving more than max(15 min, 50 percent) after the earliest are dropped`() {
        val earliest = option(1_000, 500.0, "r1")
        val withinSlack = option(1_000 + 900, 100.0, "r2")
        val tooLate = option(1_000 + 901, 50.0, "r3")

        val selected = select(listOf(tooLate, withinSlack, earliest))

        assertEquals(listOf(earliest, withinSlack), selected.options())
    }

    @Test
    fun `a transfer option must save at least five minutes over fewer transfers`() {
        val direct = option(2_000, 200.0, "r1")
        val marginal = option(1_800, 200.0, "r2", "r3")
        val worthIt = option(1_700, 200.0, "r4", "r5")

        val selected = select(listOf(direct, marginal, worthIt))

        assertEquals(listOf(worthIt, direct), selected.options())
        assertEquals(listOf(OPTIMAL, FASTEST, LEAST_WALKING), selected.labelsOf(worthIt))
        assertEquals(listOf(NO_TRANSFERS), selected.labelsOf(direct))
    }

    @Test
    fun `each label marks one option, an option may hold several, and OPTIMAL leads the page`() {
        val fastestWithTransfer = option(1_000, 900.0, "r1", "r2")
        val optimalDirect = option(1_300, 300.0, "r3")
        val leastWalking = option(1_500, 50.0, "r4")

        val selected = select(listOf(leastWalking, fastestWithTransfer, optimalDirect))

        assertEquals(listOf(optimalDirect, fastestWithTransfer, leastWalking), selected.options())
        assertEquals(listOf(OPTIMAL, NO_TRANSFERS), selected.labelsOf(optimalDirect))
        assertEquals(listOf(FASTEST), selected.labelsOf(fastestWithTransfer))
        assertEquals(listOf(LEAST_WALKING), selected.labelsOf(leastWalking))
    }

    @Test
    fun `walk-only can be OPTIMAL but never LEAST_WALKING and NO_TRANSFERS needs a transfer option`() {
        val shortWalk = walk(600.0)
        val transit = option(1_000, 200.0, "r1")

        val selected = select(listOf(transit), shortWalk)

        assertEquals(listOf(OPTIMAL, FASTEST), selected.labelsOf(shortWalk))
        assertEquals(listOf(LEAST_WALKING), selected.labelsOf(transit))
        assertTrue(selected.none { NO_TRANSFERS in it.labels })
    }

    @Test
    fun `walk-only is added up to 2 km or when it beats every transit option`() {
        val transit = option(1_000, 300.0, "r1")
        val shortWalk = walk(1_900.0)
        val fastLongWalk = PlannedItinerary(0, listOf(WalkLeg(null, null, point, point, 2_100.0, 0, 900)), emptyList())

        assertTrue(shortWalk in select(listOf(transit), shortWalk).options())
        assertTrue(fastLongWalk in select(listOf(transit), fastLongWalk).options())
        assertEquals(listOf(transit), select(listOf(transit)).options())
        assertTrue(select(emptyList()).isEmpty())
    }

    @Test
    fun `more than six options keep every labelled one first and fill the rest by generalized cost`() {
        val fastest = option(1_600, 1_900.0, "f")
        val cheap = List(6) { option(1_700L + 100 * it, 300.0, "o$it") }
        val leastWalking = option(2_400, 10.0, "lw")

        val selected = select(cheap.reversed() + leastWalking + fastest)

        assertEquals(PlannerParameters.MaximumOptions, selected.size)
        assertEquals(listOf(cheap[0], fastest, cheap[1], cheap[2], cheap[3], leastWalking), selected.options())
        assertEquals(listOf(OPTIMAL), selected.labelsOf(cheap[0]))
        assertEquals(listOf(FASTEST), selected.labelsOf(fastest))
        assertEquals(listOf(LEAST_WALKING), selected.labelsOf(leastWalking))
    }
}
