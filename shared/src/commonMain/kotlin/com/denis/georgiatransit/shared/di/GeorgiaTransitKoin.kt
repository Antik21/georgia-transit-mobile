package com.denis.georgiatransit.shared.di

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import com.denis.georgiatransit.shared.presentation.cityselection.CitySelectionViewModel
import com.denis.georgiatransit.shared.presentation.location.LocationSession
import com.denis.georgiatransit.shared.presentation.location.RuntimeLocationSession
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.routes.RoutesViewModel
import org.koin.core.context.startKoin
import org.koin.dsl.module

private val appModule = module {
    single<TransitRepository> { PreviewTransitRepository() }
    single<TransitSession> { RuntimeTransitSession() }
    single<LocationSession> { RuntimeLocationSession() }
    factory { CitySelectionViewModel(repository = get(), session = get(), locationSession = get()) }
    factory { MapViewModel(repository = get(), session = get(), locationSession = get()) }
    factory { RoutesViewModel(repository = get(), session = get()) }
}

fun initGeorgiaTransitKoin() {
    startKoin { modules(appModule) }
}
