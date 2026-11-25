package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.TransitLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapStyleUrlTest {
    @Test
    fun localeSelectsLabelsForTheBffStyle() {
        val endpoint = "https://bff.example/v1/map/style.json"
        for (locale in listOf(TransitLocale.Georgian, TransitLocale.Russian, TransitLocale.English, TransitLocale.Thai)) {
            assertEquals("$endpoint?lang=" + locale.languageTag, localizedMapStyleUrl(endpoint, locale))
        }
        assertEquals("$endpoint?lang=ka", localizedMapStyleUrl(endpoint, TransitLocale.French))
        assertNull(localizedMapStyleUrl(null, TransitLocale.Russian))
    }
}
