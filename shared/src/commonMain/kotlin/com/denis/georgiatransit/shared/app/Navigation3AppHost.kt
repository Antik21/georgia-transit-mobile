package com.denis.georgiatransit.shared.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import com.denis.georgiatransit.shared.domain.repository.TransitSession
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
import org.koin.compose.koinInject

@Composable
internal fun Navigation3AppHost(initialDestination: Destination) {
    // App composition is the boundary where the shell obtains the shared selection session.
    val session: TransitSession = koinInject()
    val navigationEventApplier = remember(session) { SessionNavigationEventApplier(session) }
    val selectedCity by session.selectedCity.collectAsState()
    val selectedCityId = selectedCity?.id
    val validatedInitialStack = NavigationTransitionPolicy.restore(
        restoredStack = listOf(initialDestination),
        selectedCityId = selectedCityId,
    )
    val backStack = rememberNavBackStack(
        navigationSavedStateConfiguration,
        validatedInitialStack.single(),
    )
    val rawStack = backStack.toList()
    val currentStack = rawStack.filterIsInstance<Destination>()
    val restoredStack = NavigationTransitionPolicy.restore(currentStack, selectedCityId)

    // Navigation 3 can restore a saved stack before the runtime session is available. Do not
    // render that stack until it has been reconciled with the session prerequisite.
    LaunchedEffect(rawStack, restoredStack) {
        if (rawStack != restoredStack) {
            backStack.clear()
            backStack.addAll(restoredStack)
        }
    }

    fun applyNavigation(event: NavigationEvent) {
        val nextStack = navigationEventApplier.apply(
            currentStack = backStack.toList().filterIsInstance<Destination>(),
            event = event,
        )
        if (backStack.toList() != nextStack) {
            backStack.clear()
            backStack.addAll(nextStack)
        }
    }

    val entries = entryProvider<NavKey> {
        entry<Destination.CitySelection> {
            val viewModel: CitySelectionViewModel = navigationEntryViewModel("city-selection")
            CitySelectionScreen(viewModel) { effect ->
                when (effect) {
                    com.denis.georgiatransit.shared.presentation.cityselection.NavigationEffect.OpenMap -> {
                        applyNavigation(NavigationEvent.CityConfirmed)
                    }
                }
            }
        }
        entry<Destination.Map> {
            val viewModel: MapViewModel = navigationEntryViewModel("map")
            MapScreen(viewModel) { effect ->
                when (effect) {
                    com.denis.georgiatransit.shared.presentation.map.NavigationEffect.OpenRoutes ->
                        applyNavigation(NavigationEvent.OpenRoutes)
                    com.denis.georgiatransit.shared.presentation.map.NavigationEffect.OpenCitySelection ->
                        applyNavigation(NavigationEvent.ChangeCity)
                }
            }
        }
        entry<Destination.Routes> {
            val viewModel: RoutesViewModel = navigationEntryViewModel("routes")
            RoutesScreen(viewModel) { effect ->
                when (effect) {
                    com.denis.georgiatransit.shared.presentation.routes.NavigationEffect.BackToMap ->
                        applyNavigation(NavigationEvent.RoutesConfirmed)
                }
            }
        }
    }

    if (rawStack == restoredStack) {
        NavDisplay(
            backStack = backStack,
            onBack = { applyNavigation(NavigationEvent.Back) },
            entryDecorators = listOf(
                androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator<NavKey>(),
            ),
            sceneStrategies = listOf(SinglePaneSceneStrategy()),
            entryProvider = entries,
        )
    }
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
