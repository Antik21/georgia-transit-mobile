package com.denis.georgiatransit.shared.presentation.ui

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteSelectionProjectorTest {
    @Test
    fun canonicalCatalogOrderUsesDeterministicFallbacksAndContrastSafeText() {
        val first = route("z-provider", "Z", color = 0xFF0057B8, textColor = 0xFFFFFFFF)
        val duplicateProviderColor = route("a-provider", "A", color = 0xFF0057B8, textColor = 0xFF0057B8)
        val transparentProviderColor = route("transparent", "T", color = 0x7F0057B8, textColor = 0x00777777)
        val lowContrastProviderColor = route("light", "L", color = 0xFFF5F5F5, textColor = 0xFF777777)
        val catalog = listOf(first, duplicateProviderColor, transparentProviderColor, lowContrastProviderColor)
        val selected = catalog.mapTo(linkedSetOf(), TransitRoute::id)

        val firstProjection = RouteSelectionProjector.resolve(catalog, selected)
        val secondProjection = RouteSelectionProjector.resolve(catalog, selected)

        assertEquals(catalog.map(TransitRoute::id), firstProjection.routes.map(ResolvedRouteSelection::routeId))
        assertEquals(firstProjection, secondProjection, "Fallback assignment must be stable across renderers.")
        assertEquals(first.colorArgb, firstProjection.routes.first().backgroundArgb)
        assertNotEquals(
            duplicateProviderColor.colorArgb,
            firstProjection.byId.getValue(duplicateProviderColor.id).backgroundArgb,
            "A duplicate provider color must not create ambiguous route identity.",
        )
        assertNotEquals(
            transparentProviderColor.colorArgb,
            firstProjection.byId.getValue(transparentProviderColor.id).backgroundArgb,
        )
        assertNotEquals(
            lowContrastProviderColor.colorArgb,
            firstProjection.byId.getValue(lowContrastProviderColor.id).backgroundArgb,
        )
        assertEquals(
            firstProjection.routes.size,
            firstProjection.routes.map(ResolvedRouteSelection::backgroundArgb).toSet().size,
        )
        assertTrue(firstProjection.routes.all { it.colorAvailability == RouteColorAvailability.Assigned })
        assertTrue(firstProjection.routes.all { route -> contrastRatio(route.backgroundArgb, route.textArgb) >= 4.5 })
    }

    @Test
    fun duplicateCatalogIdsFailClosedWithoutChangingTheUniqueRoutePalette() {
        val duplicateId = RouteId("provider:duplicate")
        val firstDuplicate = route(duplicateId.value, "first", color = 0xFF0057B8)
        val unique = route("provider:unique", "unique", color = 0xFF0057B8)
        val secondDuplicate = firstDuplicate.copy(shortName = "second", name = "Second duplicate")
        val catalog = listOf(firstDuplicate, unique, secondDuplicate)

        val withDuplicates = RouteSelectionProjector.resolve(catalog, setOf(duplicateId, unique.id))
        val uniqueOnly = RouteSelectionProjector.resolve(listOf(unique), setOf(unique.id))

        assertEquals(listOf(unique.id), withDuplicates.routes.map(ResolvedRouteSelection::routeId))
        assertEquals(uniqueOnly.byId.getValue(unique.id), withDuplicates.byId.getValue(unique.id))
        assertFalse(duplicateId in withDuplicates.byId)
    }

    private fun route(
        id: String,
        shortName: String,
        color: Long,
        textColor: Long = 0xFFFFFFFF,
    ) = TransitRoute(
        id = RouteId(id),
        cityId = CityId("projector-city"),
        shortName = shortName,
        name = "Route $shortName",
        colorArgb = color,
        textColorArgb = textColor,
    )

    private fun contrastRatio(background: Long, text: Long): Double =
        (maxOf(background.luminance(), text.luminance()) + 0.05) /
            (minOf(background.luminance(), text.luminance()) + 0.05)

    private fun Long.luminance(): Double {
        fun channel(shift: Int): Double {
            val value = ((this shr shift) and 0xFF).toDouble() / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
