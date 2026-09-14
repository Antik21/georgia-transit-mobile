package com.denis.georgiatransit.bff.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransitWireContractTest {
    @Test
    fun `public ID formatter and parser preserve every entity type`() {
        PublicEntityType.entries.forEach { entityType ->
            val value = formatPublicId(KnownCityIds.Batumi, "provider", entityType, "opaque")

            assertEquals(
                ParsedPublicId(KnownCityIds.Batumi, "provider", entityType, "opaque"),
                parsePublicId(value, KnownCityIds.Batumi, entityType),
            )
        }
    }

    @Test
    fun `public ID parser rejects a different city or entity type`() {
        val routeId = formatPublicId(KnownCityIds.Batumi, "provider", PublicEntityType.Route, "opaque")

        assertNull(parsePublicId(routeId, KnownCityIds.Tbilisi, PublicEntityType.Route))
        assertNull(parsePublicId(routeId, KnownCityIds.Batumi, PublicEntityType.Stop))
    }
}
