package com.denis.georgiatransit.shared.domain.model

internal fun List<TransitCity>.singleSelectableCity(): TransitCity? =
    singleOrNull()?.takeIf { it.capabilities.stops }
