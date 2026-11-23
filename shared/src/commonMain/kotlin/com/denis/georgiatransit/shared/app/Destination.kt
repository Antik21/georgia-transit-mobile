package com.denis.georgiatransit.shared.app

import com.denis.georgiatransit.shared.domain.model.CityId
import kotlinx.serialization.Serializable

expect interface PlatformNavigationKey

@Serializable
sealed interface Destination : PlatformNavigationKey {
    @Serializable
    data object CitySelection : Destination

    @Serializable
    data object Map : Destination

    @Serializable
    data object Routes : Destination

    @Serializable
    data object LanguageSelection : Destination

    @Serializable
    data class Schedules(val cityId: CityId) : Destination
}
