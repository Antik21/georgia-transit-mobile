package com.denis.georgiatransit.shared.di

import com.denis.georgiatransit.shared.data.cache.TransitCache
import com.denis.georgiatransit.shared.data.cache.TransitCacheStore
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.network.TransitBffClient
import com.denis.georgiatransit.shared.data.network.createTransitHttpClient
import com.denis.georgiatransit.shared.data.repository.BffJourneyPlanRepository
import com.denis.georgiatransit.shared.data.repository.BffTransitRepository
import com.denis.georgiatransit.shared.data.repository.StoredRecentRouteHistory
import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession
import com.denis.georgiatransit.shared.domain.repository.JourneyPlanRepository
import com.denis.georgiatransit.shared.domain.repository.RecentRouteHistory
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.cityselection.CitySelectionViewModel
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryCoordinator
import com.denis.georgiatransit.shared.presentation.map.DefaultVehicleRealtimeTickerPolicy
import com.denis.georgiatransit.shared.presentation.map.SystemVehicleRealtimeClock
import com.denis.georgiatransit.shared.presentation.map.VehicleRealtimeClock
import com.denis.georgiatransit.shared.presentation.map.VehicleRealtimeTickerPolicy
import com.denis.georgiatransit.shared.presentation.routes.RoutesViewModel
import com.denis.georgiatransit.shared.presentation.splash.SplashViewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

internal fun appModule(
    selectedCityStore: SelectedCityStore,
    runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
    transitCacheStore: TransitCacheStore,
    bffEndpointConfiguration: BffEndpointConfiguration?,
) = module {
    single<SelectedCityStore> { selectedCityStore }
    single<RuntimeBootstrapConfigurationSource> { runtimeConfigurationSource }
    single<TransitCacheStore> { transitCacheStore }
    single { TransitCache(get()) }
    single<RecentRouteHistory> { StoredRecentRouteHistory(get()) }
    single { createTransitHttpClient() }
    single { TransitBffClient(httpClient = get(), endpoint = bffEndpointConfiguration) }
    single<TransitRepository> { BffTransitRepository(client = get(), cache = get()) }
    single<JourneyPlanRepository> { BffJourneyPlanRepository(client = get()) }
    single<TransitSession> { RuntimeTransitSession(selectedCityStore = get()) }
    single<LocationSession> { RuntimeLocationSession() }
    single<VehicleRealtimeClock> { SystemVehicleRealtimeClock() }
    single<VehicleRealtimeTickerPolicy> { DefaultVehicleRealtimeTickerPolicy }
    factory {
        BootstrapTransitSession(
            repository = get(),
            session = get(),
            selectedCityStore = get(),
            runtimeConfigurationSource = get(),
        )
    }
    factory { RouteGeometryCoordinator(repository = get()) }
    factory { SplashViewModel(bootstrapTransitSession = get()) }
    factory { CitySelectionViewModel(repository = get(), session = get(), locationSession = get()) }
    factory {
        MapViewModel(
            repository = get(),
            session = get(),
            locationSession = get(),
            realtimeClock = get(),
            realtimeTickerPolicy = get(),
            routeGeometryCoordinator = get(),
            journeyPlanRepository = get(),
            bffStyleUrl = bffEndpointConfiguration?.mapStyleUrlOrNull(),
        )
    }
    factory { RoutesViewModel(repository = get(), session = get(), recentHistory = get()) }
}

fun initGeorgiaTransitKoin(
    selectedCityStore: SelectedCityStore,
    runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
    transitCacheStore: TransitCacheStore,
    bffEndpointConfiguration: BffEndpointConfiguration?,
) {
    startKoin {
        modules(
            appModule(
                selectedCityStore = selectedCityStore,
                runtimeConfigurationSource = runtimeConfigurationSource,
                transitCacheStore = transitCacheStore,
                bffEndpointConfiguration = bffEndpointConfiguration,
            ),
        )
    }
}
