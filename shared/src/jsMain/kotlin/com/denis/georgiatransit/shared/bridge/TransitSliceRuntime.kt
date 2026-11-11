@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.denis.georgiatransit.shared.app.Destination
import com.denis.georgiatransit.shared.app.NavigationEvent
import com.denis.georgiatransit.shared.app.SessionNavigationEventApplier
import com.denis.georgiatransit.shared.data.cache.BrowserTransitCacheStore
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.config.BrowserRuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.data.persistence.BrowserSelectedCityStore
import com.denis.georgiatransit.shared.di.appModule
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.cityselection.Action as CityAction
import com.denis.georgiatransit.shared.presentation.cityselection.CityCatalogState
import com.denis.georgiatransit.shared.presentation.cityselection.CitySelectionViewModel
import com.denis.georgiatransit.shared.presentation.cityselection.NavigationEffect as CityNavigationEffect
import com.denis.georgiatransit.shared.presentation.cityselection.cityAttributionDisplayName
import com.denis.georgiatransit.shared.presentation.cityselection.citySelectionDisplayName
import com.denis.georgiatransit.shared.presentation.map.Action as MapAction
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.map.NavigationEffect as MapNavigationEffect
import com.denis.georgiatransit.shared.presentation.routes.Action as RoutesAction
import com.denis.georgiatransit.shared.presentation.routes.NavigationEffect as RoutesNavigationEffect
import com.denis.georgiatransit.shared.presentation.routes.RoutesViewModel
import io.ktor.client.HttpClient
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.dsl.koinApplication

@JsExport
class CityItem(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val experimental: Boolean,
    val attribution: Array<TransitCredit> = emptyArray(),
)

@JsExport
class CityState(val status: String, val cities: Array<CityItem>, val selectedId: String?, val canContinue: Boolean)

/** Owns browser bootstrap and navigation entry lifetimes. */
@JsExport
class TransitSliceRuntime(bffBaseUrl: String, languageTag: String? = null) {
    private val language = languageTag?.substringBefore('-')?.lowercase().orEmpty()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cityStore = ViewModelStore()
    private var city: CitySelectionViewModel? = null
    private var map: MapBridge? = null
    private var routes: RoutesBridge? = null
    private var stack = emptyList<Destination>()
    private var closed = false
    private var host: RuntimeHost? = null
    private var cityObserver: Job? = null
    private val application: org.koin.core.KoinApplication
    private val koin: org.koin.core.Koin
    private val httpClient: HttpClient
    private val navigation: SessionNavigationEventApplier

    init {
        val endpoint = browserBffEndpoint(bffBaseUrl, window.location.origin, window.location.hostname)
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
        navigation = SessionNavigationEventApplier(koin.get())
    }

    fun start(onDestination: (String) -> Unit, onCityState: (CityState) -> Unit) {
        check(!closed && host == null)
        host = RuntimeHost(onDestination, onCityState)
        scope.launch {
            when (koin.get<BootstrapTransitSession>().bootstrap()) {
                BootstrapTransitSession.Result.OpenCitySelection -> navigate(NavigationEvent.ChangeCity)
                is BootstrapTransitSession.Result.Error -> publishDestination("bootstrapError")
                BootstrapTransitSession.Result.OpenMap -> navigate(NavigationEvent.CityConfirmed)
            }
        }
    }

    private fun showCitySelection() {
        if (closed || city != null) return
        val factory = viewModelFactory { initializer { koin.get<CitySelectionViewModel>() } }
        val vm = ViewModelProvider.create(cityStore, factory)["citySelection", CitySelectionViewModel::class]
        city = vm
        publishDestination("citySelection")
        if (closed || city !== vm) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.container.sideEffectFlow.first { it is CityNavigationEffect.OpenMap }
            cityObserver?.cancel()
            cityObserver = null
            city = null
            cityStore.clear()
            navigate(NavigationEvent.CityConfirmed)
        }
        if (closed || city !== vm) return
        cityObserver = scope.launch {
            vm.container.stateFlow.collect { state ->
                val catalog = when (state.catalog) {
                    CityCatalogState.Loading -> "loading"
                    is CityCatalogState.Populated -> "populated"
                    is CityCatalogState.Empty -> "empty"
                    is CityCatalogState.RetryableError -> "retryableError"
                }
                val cities = state.cities.map { item ->
                    CityItem(
                        item.id.value,
                        item.localizedName.citySelectionDisplayName(language, item.name),
                        item.isEnabled,
                        item.isExperimental,
                        item.attribution.toCredits(language),
                    )
                }.toTypedArray()
                notifyHost { host?.onCityState?.invoke(CityState(catalog, cities, state.selectedCityId?.value, state.canContinue)) }
            }
        }
    }

    private fun navigate(event: NavigationEvent) {
        if (closed) return
        val nextStack = navigation.apply(stack, event)
        if (nextStack == stack) return
        stack = nextStack
        when (stack.last()) {
            Destination.CitySelection -> {
                routes?.dispose()
                map?.dispose()
                showCitySelection()
            }
            Destination.Map -> {
                routes?.dispose()
                publishDestination("map")
            }
            Destination.Routes -> publishDestination("routes")
        }
    }

    private fun publishDestination(destination: String) {
        notifyHost { host?.onDestination?.invoke(destination) }
    }

    fun selectCity(id: String) { city?.dispatchAction(CityAction.CityClicked(CityId(id))) }
    fun confirmCity() { city?.dispatchAction(CityAction.ContinueClicked) }
    fun retryCities() { city?.dispatchAction(CityAction.RetryClicked) }
    fun openCitySelection() {
        if (stack.lastOrNull() == Destination.Map) map?.requestCitySelection()
    }

    fun openRouteSelection() {
        if (stack.lastOrNull() == Destination.Map) map?.requestRouteSelection()
    }

    fun openMap(): MapBridge {
        check(!closed && stack.lastOrNull() == Destination.Map)
        val selectedCity = checkNotNull(koin.get<TransitSession>().selectedCity.value)
        check(map == null) { "Map entry already open" }
        val mapStore = ViewModelStore()
        val factory = viewModelFactory { initializer { koin.get<MapViewModel>() } }
        val vm = ViewModelProvider.create(mapStore, factory)["map", MapViewModel::class]
        val locale = when (language) {
            "ka" -> TransitLocale.Georgian
            "ru" -> TransitLocale.Russian
            else -> TransitLocale.English
        }
        if (locale != TransitLocale.English) vm.dispatchAction(MapAction.LocaleChanged(locale))
        return MapBridge(vm, mapStore, selectedCity, language, onNavigation = { effect ->
            if (stack.lastOrNull() == Destination.Map) {
                when (effect) {
                    MapNavigationEffect.OpenCitySelection -> navigate(NavigationEvent.ChangeCity)
                    MapNavigationEffect.OpenRoutes -> navigate(NavigationEvent.OpenRoutes)
                }
            }
        }) { map = null }.also { map = it }
    }

    fun openRoutes(): RoutesBridge {
        check(!closed && stack.lastOrNull() == Destination.Routes)
        check(map != null && koin.get<TransitSession>().selectedCity.value != null)
        check(routes == null) { "Routes entry already open" }
        val routesStore = ViewModelStore()
        val factory = viewModelFactory { initializer { koin.get<RoutesViewModel>() } }
        val vm = ViewModelProvider.create(routesStore, factory)["routes", RoutesViewModel::class]
        vm.dispatchAction(RoutesAction.LocaleChanged(language))
        return RoutesBridge(vm, routesStore, onNavigation = { effect ->
            when (effect) {
                RoutesNavigationEffect.Confirmed -> navigate(NavigationEvent.RoutesConfirmed)
                RoutesNavigationEffect.Dismissed -> navigate(NavigationEvent.RoutesDismissed)
            }
        }) { routes = null }.also { routes = it }
    }

    fun dispose() {
        if (closed) return
        closed = true
        host = null
        scope.cancel()
        routes?.dispose()
        map?.dispose()
        city = null
        cityStore.clear()
        httpClient.close()
        application.close()
    }
}

private data class RuntimeHost(val onDestination: (String) -> Unit, val onCityState: (CityState) -> Unit)

internal fun browserBffEndpoint(baseUrl: String, origin: String, hostname: String): BffEndpointConfiguration =
    BffEndpointConfiguration(
        baseUrl = baseUrl,
        allowInsecureDebugLoopback = baseUrl.trimEnd('/') == origin && hostname in BrowserLoopbackHosts,
        mapAssetsEnabled = true,
    )

private val BrowserLoopbackHosts = setOf("localhost", "127.0.0.1", "[::1]", "::1")

internal fun List<TransitAttribution>.toCredits(languageTag: String): Array<TransitCredit> = map { credit ->
    TransitCredit(credit.label.cityAttributionDisplayName(languageTag), credit.url)
}.toTypedArray()

/** A host callback must not terminate a state subscription or strand an entry. */
internal inline fun notifyHost(callback: () -> Unit) {
    try {
        callback()
    } catch (_: Throwable) {
        println("Transit slice callback failed")
    }
}
