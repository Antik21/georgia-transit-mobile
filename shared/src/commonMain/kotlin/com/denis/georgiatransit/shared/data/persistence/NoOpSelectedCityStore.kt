package com.denis.georgiatransit.shared.data.persistence

import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.repository.CachedCitySnapshot
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore

/** Test-only/default fallback for direct shared construction outside a native application host. */
object NoOpSelectedCityStore : SelectedCityStore {
    override fun read(): CachedCitySnapshot? = null

    override fun save(city: TransitCity) = Unit

    override fun clear() = Unit
}
