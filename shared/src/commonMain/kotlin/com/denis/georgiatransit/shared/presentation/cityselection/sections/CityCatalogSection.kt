package com.denis.georgiatransit.shared.presentation.cityselection.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.withLink
import com.denis.georgiatransit.shared.domain.model.CityId
import com.denis.georgiatransit.shared.domain.repository.TransitFreshness
import com.denis.georgiatransit.shared.presentation.cityselection.CityCatalogState
import com.denis.georgiatransit.shared.presentation.cityselection.CityItemUiModel
import com.denis.georgiatransit.shared.presentation.cityselection.cityAttributionAutomationId
import com.denis.georgiatransit.shared.presentation.cityselection.cityAttributionDisplayName
import com.denis.georgiatransit.shared.presentation.cityselection.cityAttributionLinkAutomationId
import com.denis.georgiatransit.shared.presentation.cityselection.citySelectionAutomationId
import com.denis.georgiatransit.shared.presentation.cityselection.citySelectionDisplayName
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.component.EmptyState
import com.denis.georgiatransit.shared.presentation.ui.component.ErrorState
import com.denis.georgiatransit.shared.presentation.ui.component.LoadingState
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.city_attribution_title
import georgiatransit.shared.generated.resources.city_catalog_empty
import georgiatransit.shared.generated.resources.city_catalog_error
import georgiatransit.shared.generated.resources.city_catalog_loading
import georgiatransit.shared.generated.resources.city_catalog_offline
import georgiatransit.shared.generated.resources.city_catalog_retry
import georgiatransit.shared.generated.resources.city_experimental
import georgiatransit.shared.generated.resources.city_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CityCatalogSection(
    catalog: CityCatalogState,
    cities: List<CityItemUiModel>,
    selectedCityId: CityId?,
    onCityClick: (CityId) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (catalog) {
        CityCatalogState.Loading -> LoadingState(
            message = stringResource(Res.string.city_catalog_loading),
            modifier = modifier.testTag(AutomationId.CityLoading),
        )

        is CityCatalogState.Populated -> CityList(
            cities = cities,
            selectedCityId = selectedCityId,
            isStaleOffline = catalog.freshness == TransitFreshness.StaleOffline,
            onCityClick = onCityClick,
            modifier = modifier,
        )

        is CityCatalogState.Empty -> EmptyState(
            title = stringResource(Res.string.city_catalog_empty),
            message = stringResource(Res.string.city_catalog_offline)
                .takeIf { catalog.freshness == TransitFreshness.StaleOffline },
            actionLabel = stringResource(Res.string.city_catalog_retry),
            onAction = onRetry,
            actionModifier = Modifier.testTag(AutomationId.CityRetry),
            modifier = modifier.testTag(AutomationId.CityEmpty),
        )

        is CityCatalogState.RetryableError -> ErrorState(
            title = stringResource(Res.string.city_catalog_error),
            retryLabel = stringResource(Res.string.city_catalog_retry),
            onRetry = onRetry,
            retryModifier = Modifier.testTag(AutomationId.CityRetry),
            modifier = modifier.testTag(AutomationId.CityError),
        )
    }
}

@Composable
private fun CityList(
    cities: List<CityItemUiModel>,
    selectedCityId: CityId?,
    isStaleOffline: Boolean,
    onCityClick: (CityId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (isStaleOffline) {
            Text(
                text = stringResource(Res.string.city_catalog_offline),
                modifier = Modifier
                    .testTag(AutomationId.CityStaleOffline)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(TransitSpacing.Small),
        ) {
            items(cities, key = { it.id.value }) { city ->
                CityRow(
                    city = city,
                    selected = selectedCityId == city.id,
                    onClick = { onCityClick(city.id) },
                )
            }
        }
    }
}

@Composable
private fun CityRow(
    city: CityItemUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val automationModifier = city.id.citySelectionAutomationId()?.let { modifier.testTag(it) } ?: modifier
    Card(
        modifier = automationModifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = selected,
                        enabled = city.isEnabled,
                        role = Role.RadioButton,
                        onClick = onClick,
                    )
                    .padding(TransitSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, enabled = city.isEnabled, onClick = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = city.localizedName.citySelectionDisplayName(Locale.current.language, city.name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (city.isExperimental) {
                        Text(stringResource(Res.string.city_experimental), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!city.isEnabled) {
                    Text(stringResource(Res.string.city_unavailable), style = MaterialTheme.typography.labelLarge)
                }
            }
            CityAttribution(city)
        }
    }
}

/** Credits are not nested in the disabled city selector, so their links remain available. */
@Composable
private fun CityAttribution(
    city: CityItemUiModel,
    modifier: Modifier = Modifier,
) {
    if (city.attribution.isEmpty()) return
    val automationModifier = city.id.cityAttributionAutomationId()?.let { modifier.testTag(it) } ?: modifier
    Column(
        modifier = automationModifier.fillMaxWidth().padding(
            start = TransitSpacing.Medium,
            end = TransitSpacing.Medium,
            bottom = TransitSpacing.Medium,
        ),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
    ) {
        Text(
            stringResource(Res.string.city_attribution_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        city.attribution.forEach { item ->
            val linkModifier = city.id.cityAttributionLinkAutomationId(item.id)?.let(Modifier::testTag) ?: Modifier
            Text(
                text = buildAnnotatedString {
                    withLink(LinkAnnotation.Url(item.url)) {
                        append(item.label.cityAttributionDisplayName(Locale.current.language))
                    }
                },
                modifier = linkModifier,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
