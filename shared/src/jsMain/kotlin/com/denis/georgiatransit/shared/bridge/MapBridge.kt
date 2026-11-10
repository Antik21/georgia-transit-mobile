@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denis.georgiatransit.shared.data.cache.BrowserTransitCacheStore
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.config.BrowserRuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.data.persistence.BrowserSelectedCityStore
import com.denis.georgiatransit.shared.di.appModule
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.StopId
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.cityselection.CitySelectionViewModel
import com.denis.georgiatransit.shared.presentation.cityselection.CityCatalogState
import com.denis.georgiatransit.shared.presentation.cityselection.Action as CityAction
import com.denis.georgiatransit.shared.presentation.cityselection.NavigationEffect as CityNavigationEffect
import com.denis.georgiatransit.shared.presentation.map.Action
import com.denis.georgiatransit.shared.presentation.map.MapContentState
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.map.NearbyStopUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalTimeUi
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetState
import com.denis.georgiatransit.shared.presentation.map.StopArrivalsSheetUi
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.dsl.koinApplication

/** A deliberately narrow stop/arrivals slice, not a complete map-rendering API. */
@JsExport
class StopItem(val id: String, val name: String, val sourceRevision: String)

@JsExport
class ArrivalItem(
    val route: String?,
    val headsign: String,
    val timeKind: String,
    val minutes: Int?,
    val source: String,
)

@JsExport
class ArrivalsState(
    val stopId: String,
    val stopName: String,
    val status: String,
    val stale: Boolean,
    val refreshing: Boolean,
    val rows: Array<ArrivalItem>,
)

@JsExport
class StopMapState(val cityName: String, val content: String, val stops: Array<StopItem>, val arrivals: ArrivalsState?)

private data class StopSlice(
    val city: String,
    val content: MapContentState,
    val stops: List<NearbyStopUi>,
    val sheet: StopArrivalsSheetUi?,
)

/** Owns the Map entry and its subscriptions; dispose when the host leaves the entry. */
@JsExport
class MapBridge internal constructor(
    private val viewModel: MapViewModel,
    private val store: ViewModelStore,
    private val onDisposed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var subscription: Job? = null
    private var disposed = false

    fun subscribe(onState: (StopMapState) -> Unit) {
        check(!disposed)
        subscription?.cancel()
        val newSubscription = scope.launch(start = CoroutineStart.LAZY) {
            viewModel.container.stateFlow
                .map { StopSlice(it.cityName, it.contentState, it.nearbyStops, it.stopArrivalsSheet) }
                .distinctUntilChanged()
                .collect { slice -> notifyHost { onState(slice.toJs()) } }
        }
        subscription = newSubscription
        newSubscription.start()
    }

    fun setVisible(visible: Boolean) { if (!disposed) viewModel.dispatchAction(Action.RealtimeVisibilityChanged(visible)) }
    fun selectStop(id: String, sourceRevision: String) {
        val revision = sourceRevision.toLongOrNull() ?: return
        if (!disposed) viewModel.dispatchAction(Action.StopSelected(StopId(id), revision))
    }
    fun dismissArrivals() { if (!disposed) viewModel.dispatchAction(Action.StopArrivalsDismissed) }
    fun retryArrivals() { if (!disposed) viewModel.dispatchAction(Action.RetryStopArrivals) }
    fun retryNearby() { if (!disposed) viewModel.dispatchAction(Action.RetryNearby) }
    fun unsubscribe() { subscription?.cancel(); subscription = null }
    fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        store.clear()
        onDisposed()
    }
}

private fun StopSlice.toJs(): StopMapState = StopMapState(city, when (content) {
    MapContentState.Loading -> "loading"
    MapContentState.Empty -> "empty"
    MapContentState.RetryableError -> "retryableError"
    MapContentState.Unavailable -> "unavailable"
    is MapContentState.Offline -> if (content.isStale) "offlineStale" else "offline"
    MapContentState.Ready -> "ready"
}, stops.map { StopItem(it.id.value, it.name, it.sourceRevision.toString()) }.toTypedArray(), sheet?.let { s ->
    ArrivalsState(s.stopId.value, s.stopName, when (s.state) {
        StopArrivalsSheetState.Loading -> "loading"
        StopArrivalsSheetState.NoArrivals -> "noArrivals"
        StopArrivalsSheetState.PartialData -> "partialData"
        StopArrivalsSheetState.Offline -> "offline"
        StopArrivalsSheetState.UpstreamError -> "upstreamError"
        StopArrivalsSheetState.Unavailable -> "unavailable"
        StopArrivalsSheetState.Ready -> "ready"
    }, s.isStale, s.isRefreshing, s.rows.map { row ->
        ArrivalItem(row.routeShortName, row.headsign, when (row.time) {
            StopArrivalTimeUi.Arriving -> "arriving"
            is StopArrivalTimeUi.Minutes -> "minutes"
            StopArrivalTimeUi.Unavailable -> "unavailable"
        }, (row.time as? StopArrivalTimeUi.Minutes)?.value, row.source.name)
    }.toTypedArray())
})

@JsExport
class CityItem(val id: String, val name: String, val enabled: Boolean, val experimental: Boolean)
@JsExport
class CityState(val status: String, val cities: Array<CityItem>, val selectedId: String?, val canContinue: Boolean)

/** Owns bootstrap and entry lifetimes. This is only the city-to-stop/ETA slice. */
@JsExport
class TransitSliceRuntime(bffBaseUrl: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cityStore = ViewModelStore()
    private var city: CitySelectionViewModel? = null
    private var map: MapBridge? = null
    private var closed = false
    private var started = false
    private var cityObserver: Job? = null
    private val application: org.koin.core.KoinApplication
    private val koin: org.koin.core.Koin
    private val httpClient: HttpClient

    init {
        val endpoint = BffEndpointConfiguration(bffBaseUrl)
        require(endpoint.validationFailure() == null) { "Invalid BFF endpoint" }
        application = koinApplication {
            modules(
                appModule(
                    BrowserSelectedCityStore(),
                    BrowserRuntimeBootstrapConfigurationSource(),
                    BrowserTransitCacheStore(),
                    endpoint,
                ),
            )
        }
        koin = application.koin
        httpClient = koin.get()
    }

    fun start(onDestination: (String) -> Unit, onCityState: (CityState) -> Unit) {
        check(!closed && !started)
        started = true
        scope.launch {
            when (koin.get<BootstrapTransitSession>().bootstrap()) {
                BootstrapTransitSession.Result.OpenCitySelection -> {
                    val factory = viewModelFactory { initializer { koin.get<CitySelectionViewModel>() } }
                    val vm = ViewModelProvider.create(cityStore, factory)[CitySelectionViewModel::class]
                    city = vm
                    notifyHost { onDestination("citySelection") }
                    cityObserver = launch {
                        vm.container.stateFlow.collect { state -> notifyHost { onCityState(CityState(when (state.catalog) {
                            CityCatalogState.Loading -> "loading"
                            is CityCatalogState.Populated -> "populated"
                            is CityCatalogState.Empty -> "empty"
                            is CityCatalogState.RetryableError -> "retryableError"
                        }, state.cities.map { CityItem(it.id.value, it.name, it.isEnabled, it.isExperimental) }.toTypedArray(),
                            state.selectedCityId?.value, state.canContinue)) } }
                    }
                    launch {
                        vm.container.sideEffectFlow.first { it is CityNavigationEffect.OpenMap }
                        cityObserver?.cancel()
                        city = null
                        cityStore.clear()
                        notifyHost { onDestination("map") }
                    }
                }
                is BootstrapTransitSession.Result.Error -> notifyHost { onDestination("bootstrapError") }
                BootstrapTransitSession.Result.OpenMap -> notifyHost { onDestination("map") }
            }
        }
    }

    fun selectCity(id: String) { city?.dispatchAction(CityAction.CityClicked(CityId(id))) }
    fun confirmCity() { city?.dispatchAction(CityAction.ContinueClicked) }
    fun retryCities() { city?.dispatchAction(CityAction.RetryClicked) }

    fun openMap(): MapBridge {
        check(!closed && koin.get<TransitSession>().selectedCity.value != null)
        check(map == null) { "Map entry already open" }
        val mapStore = ViewModelStore()
        val factory = viewModelFactory { initializer { koin.get<MapViewModel>() } }
        val vm = ViewModelProvider.create(mapStore, factory)[MapViewModel::class]
        return MapBridge(vm, mapStore) { map = null }.also { map = it }
    }

    fun dispose() {
        if (closed) return
        closed = true
        scope.cancel()
        map?.dispose()
        city = null
        cityStore.clear()
        httpClient.close()
        application.close()
    }
}

/** A host callback must not terminate a state subscription or strand an entry. */
private inline fun notifyHost(callback: () -> Unit) {
    try {
        callback()
    } catch (_: Throwable) {
        println("Transit slice callback failed")
    }
}
