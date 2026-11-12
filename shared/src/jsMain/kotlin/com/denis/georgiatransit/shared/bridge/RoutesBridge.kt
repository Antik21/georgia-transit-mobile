@file:OptIn(ExperimentalJsExport::class)

package com.denis.georgiatransit.shared.bridge

import androidx.lifecycle.ViewModelStore
import com.denis.georgiatransit.shared.domain.model.RouteId
import com.denis.georgiatransit.shared.domain.model.RouteSelectionPolicy
import com.denis.georgiatransit.shared.domain.model.TransitMode
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.routes.Action
import com.denis.georgiatransit.shared.presentation.routes.CatalogState
import com.denis.georgiatransit.shared.presentation.routes.NavigationEffect
import com.denis.georgiatransit.shared.presentation.routes.RouteItemUiModel
import com.denis.georgiatransit.shared.presentation.routes.RoutesViewModel
import com.denis.georgiatransit.shared.presentation.routes.ViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@JsExport
class RouteChoice(
    val id: String,
    val shortName: String,
    val name: String,
    val direction: String,
    val color: String,
    val textColor: String,
    val selected: Boolean,
    val mode: String = "bus",
)

@JsExport
class RoutesState(
    val cityId: String?,
    val cityName: String,
    val status: String,
    val freshness: String?,
    val canRetry: Boolean,
    val searchQuery: String,
    val selectedCount: Int,
    val selectionLimit: Int,
    val canConfirm: Boolean,
    val isConfirming: Boolean,
    val routes: Array<RouteChoice>,
    val recentRoutes: Array<RouteChoice>,
)

/** Owns the browser Routes entry. The draft and its commit remain in RoutesViewModel. */
@JsExport
class RoutesBridge internal constructor(
    private val viewModel: RoutesViewModel,
    private val store: ViewModelStore,
    onNavigation: (NavigationEffect) -> Unit,
    private val onDisposed: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var subscription: Job? = null
    private var disposed = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            onNavigation(viewModel.container.sideEffectFlow.filterIsInstance<NavigationEffect>().first())
        }
    }

    fun subscribe(onState: (RoutesState) -> Unit) {
        check(!disposed)
        subscription?.cancel()
        val observer = scope.launch(start = CoroutineStart.LAZY) {
            viewModel.container.stateFlow.collect { state -> notifyHost { onState(state.toJs()) } }
        }
        subscription = observer
        observer.start()
    }

    fun setSearch(query: String) = dispatch(Action.SearchChanged(query))
    fun toggleRoute(id: String) = dispatch(Action.RouteToggled(RouteId(id)))
    fun confirm() = dispatch(Action.ConfirmClicked)
    fun cancel() = dispatch(Action.CancelClicked)
    fun retry() = dispatch(Action.RetryClicked)

    fun dispose() {
        if (disposed) return
        disposed = true
        scope.cancel()
        store.clear()
        onDisposed()
    }

    private fun dispatch(action: Action) {
        if (!disposed) viewModel.dispatchAction(action)
    }
}

private fun ViewState.toJs() = RoutesState(
    cityId = cityId?.value,
    cityName = cityName,
    status = when (catalog) {
        CatalogState.Loading -> "loading"
        is CatalogState.Available -> "available"
        is CatalogState.Empty -> "empty"
        is CatalogState.OfflineNoCache -> "offlineNoCache"
        CatalogState.Unavailable -> "unavailable"
        is CatalogState.Error -> "error"
    },
    freshness = when (catalog) {
        is CatalogState.Available -> catalog.freshness.toJs()
        is CatalogState.Empty -> catalog.freshness.toJs()
        else -> null
    },
    canRetry = when (catalog) {
        is CatalogState.OfflineNoCache -> catalog.canRetry
        is CatalogState.Error -> catalog.canRetry
        else -> false
    },
    searchQuery = searchQuery,
    selectedCount = selectedIds.size,
    selectionLimit = RouteSelectionPolicy.MaximumSelectedRoutes,
    canConfirm = canConfirm,
    isConfirming = isConfirming,
    routes = visibleRoutes.map { it.toJs(selectedIds) }.toTypedArray(),
    recentRoutes = recentRoutes.map { it.toJs(selectedIds) }.toTypedArray(),
)

private fun RouteItemUiModel.toJs(selectedIds: Set<RouteId>) = RouteChoice(
    id = id.value,
    shortName = shortName,
    name = name,
    direction = direction,
    color = colorArgb.toCssColor(),
    textColor = textColorArgb.toCssColor(),
    selected = id in selectedIds,
    mode = when (mode) {
        TransitMode.Bus -> "bus"
        TransitMode.Metro -> "metro"
        TransitMode.Tram -> "tram"
        TransitMode.Ferry -> "ferry"
    },
)

private fun TransitFreshness.toJs(): String = when (this) {
    TransitFreshness.Network -> "network"
    TransitFreshness.CacheValid -> "cacheValid"
    TransitFreshness.NetworkValidated -> "networkValidated"
    TransitFreshness.StaleOffline -> "staleOffline"
}

internal fun Long.toCssColor(): String = "#" + (this and 0xffffffL).toString(16).padStart(6, '0')
