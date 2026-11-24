package com.denis.georgiatransit.bff.service

import com.denis.georgiatransit.bff.api.ArrivalPage
import com.denis.georgiatransit.bff.api.ArrivalSource
import com.denis.georgiatransit.bff.control.EffectiveCity
import com.denis.georgiatransit.bff.observability.TelemetryProvider
import com.denis.georgiatransit.bff.provider.RealtimeArrivals

internal fun RealtimeArrivals.toPage(effective: EffectiveCity): ArrivalPage {
    val page = ArrivalPage(items, source, observedAt.toString(), stale)
    val downgradeOfficial = effective.adapter.telemetryProvider == TelemetryProvider.KUTAISI_AZRY &&
        !effective.city.capabilities.officialArrivals
    if (!downgradeOfficial) return page
    return page.copy(
        source = page.source.withoutOfficialLabel(),
        items = page.items.map { item -> item.copy(source = item.source.withoutOfficialLabel()) },
    )
}

private fun ArrivalSource.withoutOfficialLabel(): ArrivalSource =
    if (this == ArrivalSource.OFFICIAL_REALTIME) ArrivalSource.AGGREGATOR_REALTIME else this
