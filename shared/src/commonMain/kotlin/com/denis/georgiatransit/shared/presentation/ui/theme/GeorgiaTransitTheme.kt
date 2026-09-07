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
    /** Matches the native launch background so launch handoff has no visible seam. */
    val LaunchBackground = Color(0xFFF7FAF9)
    val Brand = Color(0xFF2A9D8F)
    val BrandDark = Color(0xFF0B625A)
    val Accent = Color(0xFFE76F51)
    val MapWater = Color(0xFFB9DFE7)
    val MapLand = Color(0xFFE9EFE7)
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
    primary = TransitColors.BrandDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC0F0E8),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = TransitColors.Accent,
    background = Color(0xFFF7FAF9),
    surface = Color(0xFFF7FAF9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF76D6C8),
    secondary = Color(0xFFFFB59F),
    background = Color(0xFF101514),
    surface = Color(0xFF101514),
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
