package com.denis.georgiatransit.shared.app

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.denis.georgiatransit.shared.presentation.cityselection.CitySelectionScreen
import com.denis.georgiatransit.shared.presentation.cityselection.CitySelectionViewModel
import com.denis.georgiatransit.shared.presentation.map.MapScreen
import com.denis.georgiatransit.shared.presentation.map.MapViewModel
import com.denis.georgiatransit.shared.presentation.routes.RoutesScreen
import com.denis.georgiatransit.shared.presentation.routes.RoutesViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Composable
internal fun Navigation3AppHost() {
    val backStack = rememberNavBackStack(
        navigationSavedStateConfiguration,
        Destination.CitySelection,
    )
    val entries = entryProvider<NavKey> {
        entry<Destination.CitySelection> {
            val viewModel: CitySelectionViewModel = navigationEntryViewModel("city-selection")
            CitySelectionScreen(viewModel) { effect ->
                when (effect) {
                    com.denis.georgiatransit.shared.presentation.cityselection.NavigationEffect.OpenMap -> {
                        backStack.clear()
                        backStack += Destination.Map
                    }
                }
            }
        }
        entry<Destination.Map> {
            val viewModel: MapViewModel = navigationEntryViewModel("map")
            MapScreen(viewModel) { effect ->
                when (effect) {
                    com.denis.georgiatransit.shared.presentation.map.NavigationEffect.OpenRoutes ->
                        backStack += Destination.Routes
                    com.denis.georgiatransit.shared.presentation.map.NavigationEffect.OpenCitySelection -> {
                        backStack.clear()
                        backStack += Destination.CitySelection
                    }
                }
            }
        }
        entry<Destination.Routes> {
            val viewModel: RoutesViewModel = navigationEntryViewModel("routes")
            RoutesScreen(viewModel) { effect ->
                when (effect) {
                    com.denis.georgiatransit.shared.presentation.routes.NavigationEffect.BackToMap -> {
                        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                    }
                }
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = listOf(
            androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
            rememberViewModelStoreNavEntryDecorator<NavKey>(),
        ),
        sceneStrategies = listOf(SinglePaneSceneStrategy()),
        entryProvider = entries,
    )
}

@Serializable
sealed interface Destination : NavKey {
    @Serializable
    data object CitySelection : Destination

    @Serializable
    data object Map : Destination

    @Serializable
    data object Routes : Destination
}

private val navigationSavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Destination.CitySelection::class, Destination.CitySelection.serializer())
            subclass(Destination.Map::class, Destination.Map.serializer())
            subclass(Destination.Routes::class, Destination.Routes.serializer())
        }
    }
}

