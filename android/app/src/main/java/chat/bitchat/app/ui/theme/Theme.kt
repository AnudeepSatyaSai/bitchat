// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * BitChat Material3 theme.
 * Always dark theme — mesh aesthetic.
 */

private val BitchatColorScheme = darkColorScheme(
    primary = BitchatColors.Primary,
    onPrimary = BitchatColors.TextPrimary,
    primaryContainer = BitchatColors.PrimaryVariant,
    onPrimaryContainer = BitchatColors.TextPrimary,
    secondary = BitchatColors.Accent,
    onSecondary = BitchatColors.Background,
    secondaryContainer = BitchatColors.AccentDark,
    onSecondaryContainer = BitchatColors.TextPrimary,
    background = BitchatColors.Background,
    onBackground = BitchatColors.TextPrimary,
    surface = BitchatColors.Surface,
    onSurface = BitchatColors.TextPrimary,
    surfaceVariant = BitchatColors.SurfaceLight,
    onSurfaceVariant = BitchatColors.TextSecondary,
    error = BitchatColors.Error,
    onError = BitchatColors.TextPrimary,
    outline = BitchatColors.GlassBorder,
)

/**
 * Typography — using system default (Roboto) with custom sizing.
 * Matches the iOS app's typography scale.
 */
val BitchatTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        letterSpacing = 0.sp,
        color = BitchatColors.TextPrimary
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 0.sp,
        color = BitchatColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = 0.sp,
        color = BitchatColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        letterSpacing = 0.sp,
        color = BitchatColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.15.sp,
        color = BitchatColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        letterSpacing = 0.5.sp,
        color = BitchatColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        letterSpacing = 0.25.sp,
        color = BitchatColors.TextSecondary
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
        color = BitchatColors.TextMuted
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
        color = BitchatColors.TextPrimary
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = BitchatColors.TextMuted
    ),
)

@Composable
fun BitchatTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BitchatColors.Background.toArgb()
            window.navigationBarColor = BitchatColors.Background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = BitchatColorScheme,
        typography = BitchatTypography,
        content = content
    )
}
