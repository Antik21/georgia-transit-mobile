package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.withLink
import com.denis.georgiatransit.shared.domain.model.LocalizedText
import com.denis.georgiatransit.shared.domain.model.TransitAttribution
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitShapes
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitSpacing
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_attribution_title
import georgiatransit.shared.generated.resources.map_openstreetmap_attribution
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OpenStreetMapAttribution(modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.map_openstreetmap_attribution)
    Surface(
        modifier = modifier.testTag(AutomationId.MapOpenStreetMapAttribution),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        shape = TransitShapes.Small,
    ) {
        Text(
            text = buildAnnotatedString {
                withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright")) {
                    append(label)
                }
            },
            modifier = Modifier.padding(
                horizontal = TransitSpacing.Small,
                vertical = TransitSpacing.ExtraSmall,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Link annotations retain accessible link semantics on Android and iOS Compose hosts. */
@Composable
internal fun CityAttribution(attribution: List<TransitAttribution>) {
    if (attribution.isEmpty()) return
    Column(
        modifier = Modifier.fillMaxWidth().testTag(AutomationId.MapAttribution),
        verticalArrangement = Arrangement.spacedBy(TransitSpacing.ExtraSmall),
    ) {
        Text(
            stringResource(Res.string.map_attribution_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        attribution.forEach { item ->
            Text(
                text = buildAnnotatedString {
                    withLink(LinkAnnotation.Url(item.url)) {
                        append(item.label.mapAttributionDisplayName(Locale.current.language))
                    }
                },
                modifier = Modifier.testTag(AutomationId.mapAttributionLink(item.id)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun LocalizedText.mapAttributionDisplayName(languageTag: String): String = when (languageTag) {
    "ka" -> ka
    "ru" -> ru
    else -> en
}.ifBlank { en.ifBlank { ru.ifBlank { ka } } }
