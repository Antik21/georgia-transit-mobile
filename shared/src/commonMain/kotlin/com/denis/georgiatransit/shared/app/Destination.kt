package com.denis.georgiatransit.shared.app

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
}
