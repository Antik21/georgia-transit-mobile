package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.GeoPoint
import com.denis.georgiatransit.shared.domain.model.StopId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapClusteringTest {
    @Test
    fun denseAndSuburbanInputsUseStableDensityTiersWhileHighZoomShowsIndividuals() {
        val suburban = markers(3)
        val dense = markers(80)

        val suburbanResult = clusterStops(suburban, zoom = 12.9)
        val denseResult = clusterStops(dense, zoom = 12.9)
        val highZoom = clusterStops(dense, zoom = 17.0)

        assertEquals(1, suburbanResult.clusters.size)
        assertTrue(suburbanResult.clusters.single().stableId.startsWith("cluster:12:56:"))
        assertEquals(1, denseResult.clusters.size)
        assertTrue(denseResult.clusters.single().stableId.startsWith("cluster:12:80:"))
        assertEquals(80, highZoom.stops.size)
        assertTrue(highZoom.clusters.isEmpty())
    }

    @Test
    fun selectedStopAlwaysRemainsIndividualAndClusterIdsAreOrderIndependent() {
        val input = markers(8).mapIndexed { index, marker -> marker.copy(isSelected = index == 3) }

        val first = clusterStops(input, zoom = 11.0)
        val reordered = clusterStops(input.reversed(), zoom = 11.0)

        assertEquals(listOf("stop-0003"), first.stops.map { it.stableId })
        assertEquals(7, first.clusters.sumOf { it.stopCount })
        assertEquals(first.stops, reordered.stops)
        assertEquals(first.clusters, reordered.clusters)
    }

    @Test
    fun invalidMarkersAreRejectedAndStableStopIdsRemainTypedAndSorted() {
        val validB = marker("b", 41.7, 44.8)
        val validA = marker("a", 41.8, 44.9)
        val input = listOf(
            validB,
            marker("", 41.7, 44.8),
            marker("nan", Double.NaN, 44.8),
            marker("latitude", 91.0, 44.8),
            marker("longitude", 41.7, 181.0),
            validA,
        )

        val result = clusterStops(input, zoom = 17.0)

        assertEquals(listOf(StopId("a"), StopId("b")), result.stops.map { it.id })
        assertTrue(result.clusters.isEmpty())
    }

    @Test
    fun thousandsOfMarkersProduceABoundedDeterministicPartition() {
        val input = List(10_000) { index ->
            marker(
                id = "bulk-${index.toString().padStart(5, '0')}",
                latitude = 41.0 + (index % 100) * 0.0001,
                longitude = 44.0 + (index / 100) * 0.0001,
            )
        }

        val first = clusterStops(input, zoom = 13.0)
        val second = clusterStops(input, zoom = 13.0)

        assertEquals(input.size, first.stops.size + first.clusters.sumOf { it.stopCount })
        assertTrue(first.stops.size + first.clusters.size <= input.size)
        assertEquals(first, second)
    }

    private fun markers(count: Int): List<MapStopMarker> = List(count) { index ->
        marker("stop-${index.toString().padStart(4, '0')}", 41.7, 44.8)
    }

    private fun marker(id: String, latitude: Double, longitude: Double) = MapStopMarker(
        id = StopId(id),
        position = GeoPoint(latitude, longitude),
        accessibilityLabel = id,
    )
}
