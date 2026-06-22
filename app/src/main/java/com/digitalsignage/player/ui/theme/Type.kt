package com.digitalsignage.player.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

val OutfitFamily = FontFamily.SansSerif
val GeistFamily = FontFamily.Monospace

val MsrTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 68.sp,
        letterSpacing = (-0.01).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 52.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 44.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 36.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OutfitFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 32.sp
    ),
    labelMedium = TextStyle(
        fontFamily = GeistFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 1.6.sp
    )
)

val CodeLgStyle = TextStyle(
    fontFamily = GeistFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 48.sp,
    lineHeight = 56.sp,
    letterSpacing = 6.sp
)

/** Single character in pairing tile */
val CodeTileStyle = TextStyle(
    fontFamily = GeistFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 40.sp,
    letterSpacing = 0.sp,
    textAlign = TextAlign.Center
)
