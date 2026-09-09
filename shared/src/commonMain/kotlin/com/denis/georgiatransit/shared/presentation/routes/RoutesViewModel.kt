package com.denis.georgiatransit.shared.presentation.routes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitCity
import com.denis.georgiatransit.shared.domain.model.TransitLocale
import com.denis.georgiatransit.shared.domain.model.TransitRoute
import com.denis.georgiatransit.shared.domain.repository.RouteListRequest
import com.denis.georgiatransit.shared.domain.repository.TransitFailure
import com.denis.georgiatransit.shared.domain.repository.TransitLoadResult
import com.denis.georgiatransit.shared.domain.repository.TransitRepository
import com.denis.georgiatransit.shared.domain.repository.TransitSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.container

class RoutesViewModel(
    private val repository: TransitRepository,
    private val session: TransitSession,
) : ViewModel(), ContainerHost<ViewState, SideEffect> {
    private var activeCity: TransitCity? = null
    private var catalogRoutes: List<TransitRoute> = emptyList()
    private var currentLocale = TransitLocale.English
    private var catalogGeneration = 0L
    private var refreshJob: Job? = null
    private var hasObservedCity = false
    /** This entry's uncommitted selection; session updates never overwrite it after initialization. */
    private var draftSelectedIds: Set<RouteId> = emptySet()

    override val container: Container<ViewState, SideEffect> = viewModelScope.container(
        initialState = ViewState(),
        onCreate = {
            combine(session.selectedCity, session.selectedRouteIds) { city, selectedIds ->
                city to selectedIds
            }.collect { (city, selectedIds) ->
                val previousCity = activeCity
                val cityChanged = city?.id != previousCity?.id
                val routesCapabilityChanged = !cityChanged &&
                    city?.capabilities?.routes != previousCity?.capabilities?.routes

                if (!hasObservedCity || cityChanged || routesCapabilityChanged) {
                    hasObservedCity = true
                    activeCity = city
                    draftSelectedIds = selectedIds.takeIf { city?.capabilities?.routes == true }
                        ?.let(RouteSelectionPolicy::sanitized)
                        .orEmpty()
                    catalogGeneration += 1
                    refreshJob?.cancel()
                    catalogRoutes = emptyList()
                    presentNewCity(city, catalogGeneration)
                } else {
                    activeCity = city
                    reduce {
                        state.copy(
                            cityName = city?.displayName(currentLocale).orEmpty(),
                        )
                    }
                }
            }
        },
    )

    fun dispatchAction(action: Action) {
        when (action) {
            is Action.RouteToggled -> onRouteToggled(action.routeId)
            is Action.SearchChanged -> onSearchChanged(action.query)
            is Action.LocaleChanged -> onLocaleChanged(action.languageTag)
            Action.RetryClicked -> onRetryClicked()
            Action.CancelClicked -> onCancelClicked()
            Action.ConfirmClicked -> onConfirmClicked()
        }
    }

    private fun presentNewCity(
        city: TransitCity?,
        generation: Long,
    ) = intent {
        when {
            city == null -> reduce {
                ViewState(
                    selectedIds = draftSelectedIds,
                    catalog = CatalogState.Unavailable,
                )
            }

            !city.capabilities.routes -> {
                draftSelectedIds = emptySet()
                session.selectRoutes(city.id, emptySet())
                reduce {
                    ViewState(
                        cityId = city.id,
                        cityName = city.displayName(currentLocale),
                        selectedIds = emptySet(),
                        catalog = CatalogState.Unavailable,
                    )
                }
            }

            else -> {
                reduce {
                    ViewState(
                        cityId = city.id,
                        cityName = city.displayName(currentLocale),
                        selectedIds = draftSelectedIds,
                        catalog = CatalogState.Loading,
                    )
                }
                requestCatalogRefresh(city, generation)
            }
        }
    }

    private fun requestCatalogRefresh(city: TransitCity, generation: Long) {
        refreshJob = viewModelScope.launch {
            // mode is deliberately null: this is the complete normalized city catalogue.
            val result = try {
                repository.refreshRoutes(RouteListRequest(cityId = city.id, mode = null))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                TransitLoadResult.Failure(TransitFailure.Transport("Route catalogue request failed"))
            }
            acceptCatalogResult(city, generation, result)
        }
    }

    private fun acceptCatalogResult(
        city: TransitCity,
        generation: Long,
        result: TransitLoadResult<List<TransitRoute>>,
    ) = intent {
        if (
            generation != catalogGeneration ||
            activeCity?.id != city.id ||
            activeCity?.capabilities?.routes != true
        ) {
            return@intent
        }
        when (result) {
            is TransitLoadResult.Data -> {
                if (result.value.any { it.cityId != city.id }) {
                    catalogRoutes = emptyList()
                    reduce {
                        state.copy(
                            routes = emptyList(),
                            visibleRoutes = emptyList(),
                            catalog = TransitFailure.InvalidResponse(
                                detail = "Route catalogue contains another city",
                            ).toCatalogState(),
                        )
                    }
                    return@intent
                }
                catalogRoutes = result.value.sortedWith(RouteCatalogComparator)
                val visibleIds = catalogRoutes.mapTo(mutableSetOf(), TransitRoute::id)
                val reconciledDraft = draftSelectedIds.intersect(visibleIds)
                draftSelectedIds = reconciledDraft
                val committedSelection = session.selectedRouteIds.value
                val reconciledCommittedSelection = committedSelection.intersect(visibleIds)
                if (
                    session.selectedCity.value?.id == city.id &&
                    reconciledCommittedSelection != committedSelection
                ) {
                    session.selectRoutes(city.id, reconciledCommittedSelection)
                }
                val routes = catalogRoutes.toRouteItems(currentLocale)
                reduce {
                    state.copy(
                        routes = routes,
                        visibleRoutes = routes.filterFor(state.searchQuery),
                        selectedIds = reconciledDraft,
                        catalog = CatalogState.Available(result.freshness),
                    )
                }
            }

            is TransitLoadResult.Empty -> {
                catalogRoutes = emptyList()
                draftSelectedIds = emptySet()
                if (session.selectedCity.value?.id == city.id && session.selectedRouteIds.value.isNotEmpty()) {
                    session.selectRoutes(city.id, emptySet())
                }
                reduce {
                    state.copy(
                        routes = emptyList(),
                        visibleRoutes = emptyList(),
                        selectedIds = emptySet(),
                        catalog = CatalogState.Empty(result.freshness),
                    )
                }
            }

            is TransitLoadResult.Failure -> reduce {
                state.copy(
                    routes = emptyList(),
                    visibleRoutes = emptyList(),
                    catalog = result.error.toCatalogState(),
                )
            }
        }
    }

    private fun onRouteToggled(routeId: RouteId) = intent {
        if (state.isConfirming || state.catalog !is CatalogState.Available || state.routes.none { it.id == routeId }) {
            return@intent
        }
        val updated = draftSelectedIds.toMutableSet().apply {
            if (!add(routeId)) {
                remove(routeId)
            } else if (size > RouteSelectionPolicy.MaximumSelectedRoutes) {
                remove(routeId)
            }
        }.toSet()
        draftSelectedIds = updated
        reduce { state.copy(selectedIds = updated) }
    }

    private fun onSearchChanged(query: String) = intent {
        reduce { state.copy(searchQuery = query, visibleRoutes = state.routes.filterFor(query)) }
    }

    private fun onLocaleChanged(languageTag: String) = intent {
        val locale = languageTag.toTransitLocale()
        if (locale == currentLocale) return@intent
        currentLocale = locale
        val routes = catalogRoutes.toRouteItems(locale)
        reduce {
            state.copy(
                cityName = activeCity?.displayName(locale).orEmpty(),
                routes = routes,
                visibleRoutes = routes.filterFor(state.searchQuery),
            )
        }
    }

    private fun onRetryClicked() = intent {
        val catalog = state.catalog
        val canRetry = when (catalog) {
            is CatalogState.Error -> catalog.canRetry
            is CatalogState.OfflineNoCache -> catalog.canRetry
            else -> false
        }
        val city = activeCity?.takeIf { it.capabilities.routes } ?: return@intent
        if (!canRetry || state.isConfirming || refreshJob?.isActive == true) return@intent

        catalogGeneration += 1
        reduce { state.copy(catalog = CatalogState.Loading) }
        requestCatalogRefresh(city, catalogGeneration)
    }

    private fun onConfirmClicked() = intent {
        val city = activeCity ?: return@intent
        val catalogIds = catalogRoutes.mapTo(mutableSetOf(), TransitRoute::id)
        if (
            !state.canConfirm ||
            state.cityId != city.id ||
            session.selectedCity.value?.id != city.id ||
            !city.capabilities.routes ||
            !RouteSelectionPolicy.isValid(draftSelectedIds) ||
            !catalogIds.containsAll(draftSelectedIds)
        ) {
            return@intent
        }

        reduce { state.copy(isConfirming = true) }
        if (!session.selectRoutes(city.id, draftSelectedIds)) {
            reduce { state.copy(isConfirming = false) }
            return@intent
        }
        postSideEffect(NavigationEffect.Confirmed)
    }

    private fun onCancelClicked() = intent {
        postSideEffect(NavigationEffect.Dismissed)
    }

    private fun TransitFailure.toCatalogState(): CatalogState {
        val canRetry = canRetryCatalogRefresh()
        return if (this is TransitFailure.Transport || this is TransitFailure.Timeout) {
            CatalogState.OfflineNoCache(this, canRetry)
        } else {
            CatalogState.Error(this, canRetry)
        }
    }

    private fun TransitFailure.canRetryCatalogRefresh(): Boolean = when (this) {
        is TransitFailure.Transport,
        is TransitFailure.Timeout,
        is TransitFailure.RateLimited,
        is TransitFailure.UpstreamBadResponse,
        is TransitFailure.UpstreamUnavailable,
        is TransitFailure.UpstreamTimeout,
        is TransitFailure.Internal,
        -> true

        else -> false
    }
}

/** Natural ordering makes numeric route names useful without relying on provider sort metadata. */
private object RouteCatalogComparator : Comparator<TransitRoute> {
    override fun compare(a: TransitRoute, b: TransitRoute): Int =
        naturalCompare(a.shortName, b.shortName)
            .takeUnless { it == 0 }
            ?: a.shortName.compareTo(b.shortName, ignoreCase = true)
                .takeUnless { it == 0 }
            ?: a.shortName.compareTo(b.shortName)
                .takeUnless { it == 0 }
            ?: a.longName.en.compareTo(b.longName.en)
                .takeUnless { it == 0 }
            ?: a.longName.ru.compareTo(b.longName.ru)
                .takeUnless { it == 0 }
            ?: a.longName.ka.compareTo(b.longName.ka)
                .takeUnless { it == 0 }
            ?: a.mode.name.compareTo(b.mode.name)
                .takeUnless { it == 0 }
            // Kotlin's list sort is stable, so routes with identical public fields retain the
            // authoritative BFF source order instead of exposing opaque provider IDs here.
            ?: 0
}

private fun naturalCompare(first: String, second: String): Int {
    var firstIndex = 0
    var secondIndex = 0
    while (firstIndex < first.length && secondIndex < second.length) {
        val firstChar = first[firstIndex]
        val secondChar = second[secondIndex]
        if (firstChar.isDigit() && secondChar.isDigit()) {
            val firstEnd = firstIndex.consumeDigits(first)
            val secondEnd = secondIndex.consumeDigits(second)
            val firstSignificant = firstIndex.skipLeadingZeroes(first, firstEnd)
            val secondSignificant = secondIndex.skipLeadingZeroes(second, secondEnd)
            val firstDigits = firstEnd - firstSignificant
            val secondDigits = secondEnd - secondSignificant
            if (firstDigits != secondDigits) return firstDigits.compareTo(secondDigits)
            for (index in 0 until firstDigits) {
                val comparison = first[firstSignificant + index].compareTo(second[secondSignificant + index])
                if (comparison != 0) return comparison
            }
            firstIndex = firstEnd
            secondIndex = secondEnd
        } else {
            val comparison = firstChar.lowercaseChar().compareTo(secondChar.lowercaseChar())
            if (comparison != 0) return comparison
            firstIndex += 1
            secondIndex += 1
        }
    }
    return (first.length - firstIndex).compareTo(second.length - secondIndex)
}

private fun Int.consumeDigits(value: String): Int {
    var index = this
    while (index < value.length && value[index].isDigit()) index += 1
    return index
}

private fun Int.skipLeadingZeroes(value: String, endExclusive: Int): Int {
    var index = this
    while (index < endExclusive - 1 && value[index] == '0') index += 1
    return index
}

private fun List<TransitRoute>.toRouteItems(locale: TransitLocale): List<RouteItemUiModel> = map { route ->
    RouteItemUiModel(
        id = route.id,
        shortName = route.shortName.trim().ifBlank { "?" },
        name = route.longName.localizedDisplayName(locale, route.name.trim().ifBlank { "?" }),
        direction = route.directions.asSequence()
            .map { direction ->
                direction.headsign.localizedDisplayName(
                    locale = locale,
                    fallback = direction.name.localizedDisplayName(locale, fallback = ""),
                )
            }
            .firstOrNull(String::isNotBlank)
            .orEmpty(),
        colorArgb = route.colorArgb,
        textColorArgb = route.textColorArgb,
        mode = route.mode,
    )
}

private fun TransitCity.displayName(locale: TransitLocale): String =
    localizedName.localizedDisplayName(locale, name)

private fun LocalizedText.localizedDisplayName(
    locale: TransitLocale,
    fallback: String,
): String = forLocale(locale).trim()
    .ifBlank { en.trim() }
    .ifBlank { ru.trim() }
    .ifBlank { ka.trim() }
    .ifBlank { fallback.trim() }

private fun List<RouteItemUiModel>.filterFor(query: String): List<RouteItemUiModel> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return this
    return filter { route ->
        route.shortName.contains(normalizedQuery, ignoreCase = true) ||
            route.name.contains(normalizedQuery, ignoreCase = true)
    }
}

private fun String.toTransitLocale(): TransitLocale = when (this) {
    "ka" -> TransitLocale.Georgian
    "ru" -> TransitLocale.Russian
    else -> TransitLocale.English
}
