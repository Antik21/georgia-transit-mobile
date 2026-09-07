package com.denis.georgiatransit.shared.di

import com.denis.georgiatransit.shared.domain.config.RuntimeBootstrapConfigurationSource
import com.denis.georgiatransit.shared.domain.interactor.BootstrapTransitSession
import com.denis.georgiatransit.shared.domain.repository.SelectedCityStore
import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.cityselection.CitySelectionViewModel
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.routes.RoutesViewModel
import com.denis.georgiatransit.shared.presentation.splash.SplashViewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

private fun appModule(
    selectedCityStore: SelectedCityStore,
    runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
) = module {
    single<SelectedCityStore> { selectedCityStore }
    single<RuntimeBootstrapConfigurationSource> { runtimeConfigurationSource }
    single<TransitRepository> { PreviewTransitRepository() }
    single<TransitSession> { RuntimeTransitSession(selectedCityStore = get()) }
    single<LocationSession> { RuntimeLocationSession() }
    factory {
        BootstrapTransitSession(
            repository = get(),
            session = get(),
            selectedCityStore = get(),
            runtimeConfigurationSource = get(),
        )
    }
    factory { SplashViewModel(bootstrapTransitSession = get()) }
    factory { CitySelectionViewModel(repository = get(), session = get(), locationSession = get()) }
    factory { MapViewModel(repository = get(), session = get(), locationSession = get()) }
    factory { RoutesViewModel(repository = get(), session = get()) }
}

fun initGeorgiaTransitKoin(
    selectedCityStore: SelectedCityStore,
    runtimeConfigurationSource: RuntimeBootstrapConfigurationSource,
) {
    startKoin { modules(appModule(selectedCityStore, runtimeConfigurationSource)) }
}
