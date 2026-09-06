package com.denis.georgiatransit.shared.presentation.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TransitColors {
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

private val TransitTypography = androidx.compose.material3.Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
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
        typography = TransitTypography,
        content = content,
    )
}

