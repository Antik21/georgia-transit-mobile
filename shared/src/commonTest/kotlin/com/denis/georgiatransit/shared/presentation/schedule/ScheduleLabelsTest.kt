package com.denis.georgiatransit.shared.presentation.schedule

import com.denis.georgiatransit.shared.domain.model.DirectionId
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitDirection
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import kotlin.test.Test
import kotlin.test.assertEquals

class ScheduleLabelsTest {
    @Test
    fun emptyAndNumberOnlyHeadsignsUseTheLocalizedCatalogueName() {
        val name = LocalizedText(ru = "Станция → Парк", en = "Station → Park", ka = "")
        val locale = TransitLocale.Russian

        assertEquals("Станция → Парк", direction(" ", name).scheduleDirectionLabel(locale, "137"))
        assertEquals("Станция → Парк", direction(" 137 ", name).scheduleDirectionLabel(locale, "137"))
        assertEquals("Станция → Парк", direction("137a", name).scheduleDirectionLabel(locale, " 137A "))
    }

    @Test
    fun informativeHeadsignsAndAvailableCatalogueFallbackArePreserved() {
        val name = LocalizedText(ru = "", en = "Station → Park", ka = "")
        val locale = TransitLocale.Russian

        assertEquals("Парк", direction("Парк", name).scheduleDirectionLabel(locale, "137"))
        assertEquals("137 · Парк", direction("137 · Парк", name).scheduleDirectionLabel(locale, "137"))
        assertEquals("Station → Park", direction("137", name).scheduleDirectionLabel(locale, "137"))
        assertEquals("137", direction("137", LocalizedText.fromLegacy("")).scheduleDirectionLabel(locale, "137"))
    }

    private fun direction(headsign: String, name: LocalizedText) =
        TransitDirection(DirectionId("137-outbound"), name, LocalizedText.fromLegacy(headsign))
}
