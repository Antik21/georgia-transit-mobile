package com.denis.georgiatransit.shared.presentation.cityselection

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test
import kotlin.test.Test
import kotlin.test.assertEquals

class CitySelectionViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun continueUpdatesSessionBeforeOpenMapEffectIsObserved() = runTest {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val cities = repository.cities().map { city ->
            CityItemUiModel(
                id = city.id,
                name = city.name,
                isEnabled = city.capabilities.stops,
                isExperimental = city.capabilities.experimental,
            )
        }
        val viewModel = CitySelectionViewModel(repository, session)

        viewModel.test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(ViewState(cities = cities))

            viewModel.dispatchAction(Action.CityClicked(tbilisi.id))
            this@runTest.runCurrent()
            expectState(ViewState(cities = cities, selectedCityId = tbilisi.id))

            viewModel.dispatchAction(Action.ContinueClicked)
            this@runTest.runCurrent()

            expectSideEffect(NavigationEffect.OpenMap)
            assertEquals(tbilisi, session.selectedCity.value)
            cancelAndIgnoreRemainingItems()
        }
    }
}
