package com.bonevane.bridge.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3 with Material You: on Android 12+ the palette is derived from the
 * user's wallpaper, so Bridge looks like it belongs to the phone rather than
 * bringing its own brand colours. Older versions fall back to a fixed scheme.
 */
private val FallbackLight = lightColorScheme(
    primary = Color(0xFF00658E),
    secondary = Color(0xFF4E616D),
    tertiary = Color(0xFF615A7C),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF83CFFF),
    secondary = Color(0xFFB5C9D7),
    tertiary = Color(0xFFCBC1E9),
)

/**
 * "Expressive" typography: the headline sizes are pushed up and the weights made
 * heavier than the defaults, which is what gives Material 3 Expressive its
 * confident, large-type feel.
 */
private val ExpressiveType = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    )
}

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colors, typography = ExpressiveType, content = content)
}
