package com.denis.georgiatransit.shared.di

import com.denis.georgiatransit.shared.core.telemetry.AppTelemetry
import com.denis.georgiatransit.shared.core.telemetry.NoOpAppTelemetry
import com.denis.georgiatransit.shared.core.telemetry.SafeAppTelemetry
import com.denis.georgiatransit.shared.data.cache.TransitCache
import com.denis.georgiatransit.shared.data.cache.TransitCacheStore
import com.denis.georgiatransit.shared.data.config.BffEndpointConfiguration
import com.denis.georgiatransit.shared.data.network.TransitBffClient
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.analytics.AnalyticsClient
import com.denis.georgiatransit.shared.domain.analytics.AnalyticsRecord
import com.denis.georgiatransit.shared.domain.analytics.NativeAnalyticsSink
import com.denis.georgiatransit.shared.domain.analytics.ProductAnalytics
import com.denis.georgiatransit.shared.domain.analytics.toNativeEvent
import com.denis.georgiatransit.shared.domain.repository.ConnectionErrorNotices
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
import com.denis.georgiatransit.shared.presentation.languageselection.AppLanguageSettings
import com.denis.georgiatransit.shared.presentation.languageselection.LanguageSelectionViewModel
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.map.RouteGeometryCoordinator
import com.denis.georgiatransit.shared.presentation.map.DefaultVehicleRealtimeTickerPolicy
import com.denis.georgiatransit.shared.presentation.map.SystemVehicleRealtimeClock
import com.denis.georgiatransit.shared.presentation.map.VehicleRealtimeClock
import com.denis.georgiatransit.shared.presentation.map.VehicleRealtimeTickerPolicy
import com.denis.georgiatransit.shared.presentation.routes.RoutesViewModel
import com.denis.georgiatransit.shared.presentation.schedule.ScheduleViewModel
import com.denis.georgiatransit.shared.presentation.splash.SplashViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.dsl.module

internal fun appModule(
    selectedCityStore: SelectedCityStore,
    runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
    transitCacheStore: TransitCacheStore,
    bffEndpointConfiguration: BffEndpointConfiguration?,
    appTelemetry: AppTelemetry = NoOpAppTelemetry,
    analyticsClient: AnalyticsClient? = null,
    analyticsDeviceId: String? = null,
    analyticsDelivery: (suspend (AnalyticsRecord) -> Unit)? = null,
) = module {
    single<AppTelemetry> { SafeAppTelemetry(appTelemetry) }
    single<SelectedCityStore> { selectedCityStore }
    single<RuntimeBootstrapConfigurationSource> { runtimeConfigurationSource }
    single<TransitCacheStore> { transitCacheStore }
    single { TransitCache(get(), telemetry = get()) }
    single<RecentRouteHistory> { StoredRecentRouteHistory(get()) }
    single { createTransitHttpClient() }
    single { ConnectionErrorNotices() }
    single {
        TransitBffClient(
            httpClient = get(),
            endpoint = bffEndpointConfiguration,
            connectionNotices = get(),
            telemetry = get(),
        )
    }
    if (analyticsClient != null && analyticsDeviceId != null) {
        single {
            val bff = get<TransitBffClient>()
            ProductAnalytics(
                analyticsClient, analyticsDeviceId,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                deliver = analyticsDelivery ?: bff::sendAnalytics,
            )
        }
    }
    single<TransitRepository> { BffTransitRepository(client = get(), cache = get()) }
    single<JourneyPlanRepository> { BffJourneyPlanRepository(client = get()) }
    single<TransitSession> { RuntimeTransitSession(selectedCityStore = get(), telemetry = get()) }
    single<LocationSession> { RuntimeLocationSession() }
    single<VehicleRealtimeClock> { SystemVehicleRealtimeClock() }
    single<VehicleRealtimeTickerPolicy> { DefaultVehicleRealtimeTickerPolicy }
    factory {
        BootstrapTransitSession(
            repository = get(),
            session = get(),
            selectedCityStore = get(),
            runtimeConfigurationSource = get(),
            telemetry = get(),
        )
    }
    factory { RouteGeometryCoordinator(repository = get()) }
    factory { SplashViewModel(bootstrapTransitSession = get()) }
    factory { CitySelectionViewModel(repository = get(), session = get(), locationSession = get()) }
    factory { (picker: AppLanguageSettings.Picker) -> LanguageSelectionViewModel(picker) }
    factory {
        MapViewModel(
            repository = get(),
            session = get(),
            locationSession = get(),
            realtimeClock = get(),
            realtimeTickerPolicy = get(),
            routeGeometryCoordinator = get(),
            journeyPlanRepository = get(),
            connectionNotices = get(),
            analytics = getOrNull(),
            bffStyleUrl = bffEndpointConfiguration?.mapStyleUrlOrNull(),
        )
    }
    factory { RoutesViewModel(repository = get(), session = get(), recentHistory = get(), analytics = getOrNull()) }
    factory { (cityId: CityId) -> ScheduleViewModel(cityId, repository = get(), session = get()) }
}

fun initGeorgiaTransitKoin(
    selectedCityStore: SelectedCityStore,
    runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
    transitCacheStore: TransitCacheStore,
    bffEndpointConfiguration: BffEndpointConfiguration?,
    appTelemetry: AppTelemetry,
    analyticsClient: AnalyticsClient,
    analyticsDeviceId: String,
    nativeAnalytics: NativeAnalyticsSink?,
) {
    startKoin {
        modules(
            appModule(
                selectedCityStore = selectedCityStore,
                runtimeConfigurationSource = runtimeConfigurationSource,
                transitCacheStore = transitCacheStore,
                bffEndpointConfiguration = bffEndpointConfiguration,
                appTelemetry = appTelemetry,
                analyticsClient = analyticsClient,
                analyticsDeviceId = analyticsDeviceId,
                analyticsDelivery = { record ->
                    runCatching { nativeAnalytics?.send(record.toNativeEvent()) }
                },
            ),
        )
    }
}
