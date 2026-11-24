package com.denis.georgiatransit.bff.provider.kutaisi

import com.denis.georgiatransit.bff.api.GeoPoint
import com.denis.georgiatransit.bff.api.KnownCityIds
import com.denis.georgiatransit.bff.provider.StopNameDirectory

internal object KutaisiStopNames {
    private val directory = StopNameDirectory(
        resourcePath = "/transit/kutaisi/stop-names.json",
        cityId = KnownCityIds.Kutaisi,
        provider = "azry",
        latitudeRange = 42.17..42.35,
        longitudeRange = 42.57..42.77,
    )

    fun find(id: String, code: String, position: GeoPoint) = directory.find(id, code, position)

    fun findForTimetable(id: String, providerLabel: String) = directory.findForTimetable(id, providerLabel)
}
