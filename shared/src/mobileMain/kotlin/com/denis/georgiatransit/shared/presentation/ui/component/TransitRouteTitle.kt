package com.denis.georgiatransit.shared.presentation.ui.component

import androidx.compose.runtime.Composable
import com.denis.georgiatransit.shared.domain.model.TransitMode
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.routes_mode_bus
import georgiatransit.shared.generated.resources.routes_mode_ferry
import georgiatransit.shared.generated.resources.routes_mode_metro
import georgiatransit.shared.generated.resources.routes_mode_tram
import georgiatransit.shared.generated.resources.routes_title_bus
import georgiatransit.shared.generated.resources.routes_title_ferry
import georgiatransit.shared.generated.resources.routes_title_metro
import georgiatransit.shared.generated.resources.routes_title_tram
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TransitMode.routeTitle(number: String): String = stringResource(
    when (this) {
        TransitMode.Bus -> Res.string.routes_title_bus
        TransitMode.Metro -> Res.string.routes_title_metro
        TransitMode.Tram -> Res.string.routes_title_tram
        TransitMode.Ferry -> Res.string.routes_title_ferry
    },
    number,
)

@Composable
internal fun TransitMode.displayName(): String = stringResource(
    when (this) {
        TransitMode.Bus -> Res.string.routes_mode_bus
        TransitMode.Metro -> Res.string.routes_mode_metro
        TransitMode.Tram -> Res.string.routes_mode_tram
        TransitMode.Ferry -> Res.string.routes_mode_ferry
    },
)
