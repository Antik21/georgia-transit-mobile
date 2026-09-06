package com.denis.georgiatransit.shared.presentation.map

import com.denis.georgiatransit.shared.data.repository.PreviewTransitRepository
import com.denis.georgiatransit.shared.data.repository.RuntimeTransitSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.orbitmvi.orbit.test.test
import kotlin.test.Test

class MapViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun selectedCityCenterDrivesViewportAndUpdatesWhenCityChanges() = runTest {
        val repository = PreviewTransitRepository()
        val session = RuntimeTransitSession()
        val tbilisi = repository.cities().first { it.id.value == "tbilisi" }
        val batumi = repository.cities().first { it.id.value == "batumi" }
        session.selectCity(tbilisi)

        MapViewModel(repository, session).test(this) {
            runOnCreate()
            this@runTest.runCurrent()
            expectState(
                ViewState(
                    cityName = tbilisi.name,
                    viewport = MapViewport(center = tbilisi.center),
                ),
            )

            session.selectCity(batumi)
            this@runTest.runCurrent()

            expectState(
                ViewState(
                    cityName = batumi.name,
                    viewport = MapViewport(center = batumi.center),
                ),
            )
            cancelAndIgnoreRemainingItems()
        }
    }
}
