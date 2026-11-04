package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.presentation.ui.RouteSelectionProjection
import kotlinx.coroutines.CoroutineScope

/** Joins route polling with frame projection; screen state is still published by MapViewModel. */
internal class MapVehicleFeature(
    repository: TransitRepository,
    clock: VehicleRealtimeClock,
    tickerPolicy: VehicleRealtimeTickerPolicy,
) {
    val presentation = MapVehiclePresentation(clock, tickerPolicy)
    private val poller = MapVehiclePoller(repository, tickerPolicy)
    private var city: TransitCity? = null
    private var routes: List<TransitRoute> = emptyList()
    private var visible = false

    fun updateInputs(
        city: TransitCity?,
        routes: List<TransitRoute>,
        projection: RouteSelectionProjection,
        visible: Boolean,
        geometry: RouteGeometrySnapshot,
    ) {
        this.city = city
        this.routes = routes
        this.visible = visible
        presentation.updateInputs(city, routes, projection, visible, geometry)
    }

    fun reset() {
        poller.reset()
        presentation.reset()
    }

    fun isCurrent(generation: Long, cityId: CityId): Boolean =
        generation == poller.generation && visible && city?.id == cityId &&
            city?.capabilities?.vehiclePositions == true && routes.isNotEmpty()

    fun start(scope: CoroutineScope, onChanged: (VehiclePollKey?) -> Unit) {
        val selectedCity = city ?: return
        if (!visible || !selectedCity.capabilities.vehiclePositions || routes.isEmpty()) return
        poller.start(
            scope = scope,
            cityId = selectedCity.id,
            routes = routes,
            canContinue = { isCurrent(it.generation, it.cityId) },
            routeActive = { routeId -> routes.any { it.id == routeId } &&
                routeId !in presentation.vehicleRealtimeState.pausedRoutes },
            frameDelayMillis = presentation::vehicleFrameDelayMillis,
            onEvent = eventHandler@{ event ->
                if (!isCurrent(event.key.generation, event.key.cityId)) return@eventHandler
                when (event) {
                    is VehiclePollEvent.Loading -> {
                        presentation.markLoading(event.routeId)
                        onChanged(null)
                    }
                    is VehiclePollEvent.Result -> {
                        if (routes.none { it.id == event.routeId }) return@eventHandler
                        presentation.acceptResult(event.routeId, event.page)
                        presentation.updateVehicleFrames(forceSourceCheck = true)
                        onChanged(event.key)
                    }
                    is VehiclePollEvent.Frame -> {
                        if (presentation.updateVehicleFrames().shouldPublish) onChanged(event.key)
                    }
                }
            },
        )
    }
}
