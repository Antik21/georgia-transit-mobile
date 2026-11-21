package com.denis.georgiatransit.shared.presentation.ui.component

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import georgiatransit.shared.generated.resources.Res
import georgiatransit.shared.generated.resources.close_action
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TransitTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    closeButtonModifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onClose != null) {
                TransitCloseButton(onClick = onClose, modifier = closeButtonModifier)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
internal fun TransitCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
) {
    val colors = if (tonal) {
        IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    } else {
        IconButtonDefaults.iconButtonColors()
    }
    IconButton(onClick = onClick, modifier = modifier, colors = colors) {
        Icon(CloseIcon, contentDescription = stringResource(Res.string.close_action))
    }
}

/** Material Symbols "close" geometry, used under the Apache-2.0 license. */
internal val CloseIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Close",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        addPath(
            fill = SolidColor(Color.Black),
            pathData = PathParser().parsePathString(
                "M18.3 5.71 16.89 4.29 12 9.17 7.11 4.29 5.7 5.71 10.59 10.59 5.7 15.48 7.11 16.9 12 12 16.89 16.9 18.3 15.48 13.41 10.59z",
            ).toNodes(),
        )
    }.build()
}
