package com.denis.georgiatransit.bff.provider.batumi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.provider.StopNameDirectory

internal object BatumiStopNames {
    private val directory = StopNameDirectory(
        resourcePath = "/transit/batumi/stop-names.json",
        cityId = KnownCityIds.Batumi,
        provider = "theta",
        latitudeRange = 41.45..41.85,
        longitudeRange = 41.40..41.95,
    )

    fun find(id: String, code: String, position: GeoPoint) = directory.find(id, code, position)
}
