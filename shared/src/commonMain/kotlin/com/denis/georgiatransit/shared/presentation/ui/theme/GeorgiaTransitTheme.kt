package com.denis.georgiatransit.shared.presentation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TransitColors {
    val Primary = Color(0xFF8E1B2D)
    val Accent = Color(0xFFFF6B5F)
    val Background = Color(0xFFF7F7F5)
    val Dark = Color(0xFF18191B)
    val SecondaryGray = Color(0xFFA8ADB4)
    val Success = Color(0xFF27AE7A)

    /** Matches the native launch background so launch handoff has no visible seam. */
    val LaunchBackground = Background

    // Map-specific colors are functional tokens rather than part of the brand palette.
    val MapWater = Color(0xFFB9DFE7)
    val MapLand = Color(0xFFE7F1EB)
    val RouteBlue = Color(0xFF457B9D)
}

object TransitSpacing {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 16.dp
    val Large = 24.dp
    val ExtraLarge = 32.dp
}

object TransitTypography {
    val HeadlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    val TitleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val TitleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val BodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val BodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val LabelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
}

object TransitShapes {
    val ExtraSmall = RoundedCornerShape(4.dp)
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)
    val Full = CircleShape
}

private val TransitMaterialTypography = Typography(
    headlineSmall = TransitTypography.HeadlineSmall,
    titleLarge = TransitTypography.TitleLarge,
    titleMedium = TransitTypography.TitleMedium,
    bodyLarge = TransitTypography.BodyLarge,
    bodyMedium = TransitTypography.BodyMedium,
    labelLarge = TransitTypography.LabelLarge,
)

private val TransitMaterialShapes = Shapes(
    extraSmall = TransitShapes.ExtraSmall,
    small = TransitShapes.Small,
    medium = TransitShapes.Medium,
    large = TransitShapes.Large,
)

private val LightColors = lightColorScheme(
    primary = TransitColors.Primary,
    onPrimary = TransitColors.Background,
    primaryContainer = TransitColors.Accent,
    onPrimaryContainer = TransitColors.Dark,
    secondary = TransitColors.Accent,
    onSecondary = TransitColors.Dark,
    tertiary = TransitColors.Success,
    onTertiary = TransitColors.Dark,
    background = TransitColors.Background,
    onBackground = TransitColors.Dark,
    surface = TransitColors.Background,
    onSurface = TransitColors.Dark,
    surfaceVariant = TransitColors.SecondaryGray,
    onSurfaceVariant = TransitColors.Dark,
    outline = TransitColors.SecondaryGray,
)

private val DarkColors = darkColorScheme(
    primary = TransitColors.Accent,
    onPrimary = TransitColors.Dark,
    primaryContainer = TransitColors.Primary,
    onPrimaryContainer = TransitColors.Background,
    secondary = TransitColors.Success,
    onSecondary = TransitColors.Dark,
    tertiary = TransitColors.Primary,
    onTertiary = TransitColors.Background,
    background = TransitColors.Dark,
    onBackground = TransitColors.Background,
    surface = TransitColors.Dark,
    onSurface = TransitColors.Background,
    surfaceVariant = TransitColors.Dark,
    onSurfaceVariant = TransitColors.SecondaryGray,
    outline = TransitColors.SecondaryGray,
)

@Composable
fun GeorgiaTransitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = TransitMaterialTypography,
        shapes = TransitMaterialShapes,
        content = content,
    )
}
