package com.denis.georgiatransit.shared.presentation.map.sections

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import com.denis.georgiatransit.shared.presentation.ui.automation.AutomationId
import com.denis.georgiatransit.shared.presentation.ui.theme.TransitColors
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.map_openstreetmap_attribution
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OpenStreetMapAttribution(modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.map_openstreetmap_attribution)
    // PRODUCT REQUIREMENT: compact linked credit without a background fill. OSM explicitly
    // accepts "© OpenStreetMap"; keep it visible and readable while using public OSM tiles.
    // https://osmfoundation.org/wiki/Licence/Attribution_Guidelines#Attribution_text
    Text(
        text = buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://www.openstreetmap.org/copyright")) {
                append(label)
            }
        },
        modifier = modifier.testTag(AutomationId.MapOpenStreetMapAttribution),
        // The vector basemap stays light in both app themes. A text halo retains contrast
        // over map details without introducing a filled attribution container.
        style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(color = TransitColors.Background, blurRadius = 3f),
        ),
        color = MaterialTheme.colorScheme.primary,
    )
}
