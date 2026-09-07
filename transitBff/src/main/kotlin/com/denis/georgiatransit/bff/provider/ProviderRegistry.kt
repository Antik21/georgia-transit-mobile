package com.denis.georgiatransit.bff.provider

import com.denis.georgiatransit.bff.api.CapabilityNotAvailable
import com.denis.georgiatransit.bff.api.City
import com.denis.georgiatransit.bff.api.CityNotFound

class ProviderRegistry(adapters: Collection<CityTransitProviderAdapter>) {
    private val adaptersByCity = adapters
        .onEach { NormalizedResponseValidator.city(it.city) }
        .associateBy { it.city.id }

    init {
        require(adaptersByCity.size == adapters.size) { "Only one provider adapter may own a city" }
    }

    val isReady: Boolean get() = adaptersByCity.isNotEmpty()

    fun cities(): List<City> = adaptersByCity.values.map { it.city }.sortedBy { it.id }.also {
        NormalizedResponseValidator.cities(it)
    }

    fun adapterFor(cityId: String): CityTransitProviderAdapter =
        adaptersByCity[cityId]
            ?: throw CityNotFound("No configured transit provider serves city '$cityId'")

    fun requireCapability(cityId: String, supported: Boolean, capability: String) {
        if (!supported) {
            throw CapabilityNotAvailable("$capability is not available for city '$cityId'")
        }
    }
}
