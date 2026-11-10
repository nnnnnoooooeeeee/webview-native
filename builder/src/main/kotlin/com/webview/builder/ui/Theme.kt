package com.webview.builder.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.webview.builder.data.Settings
import com.webview.builder.data.ThemeMode

//========== COLORS ==========
// Tonal palette from the seed #4F55B8 (indigo), with a green "success" and an
// amber "warning" pair used for build results and key status.

private val Light = lightColorScheme(
    primary = Color(0xFF4F55B8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E0FF), onPrimaryContainer = Color(0xFF2F3593),
    secondary = Color(0xFF5C5D72), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E0F9), onSecondaryContainer = Color(0xFF1A1A2C),
    tertiary = Color(0xFF7A4F7A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E8), onTertiaryContainer = Color(0xFF5C1140),
    error = Color(0xFFBA1A1A), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFCF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFCF8FF), onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EA), onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2FC),
    surfaceContainer = Color(0xFFF0ECF6), surfaceContainerHigh = Color(0xFFEAE6F0),
    surfaceContainerHighest = Color(0xFFE4E1EA),
    outline = Color(0xFF777680), outlineVariant = Color(0xFFC7C5D0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFBFC2FF), onPrimary = Color(0xFF1E2578),
    primaryContainer = Color(0xFF363C9E), onPrimaryContainer = Color(0xFFE0E0FF),
    secondary = Color(0xFFC5C4DD), onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF44445E), onSecondaryContainer = Color(0xFFE2E0F9),
    tertiary = Color(0xFFE9B5E6), onTertiary = Color(0xFF47204A),
    tertiaryContainer = Color(0xFF7B2958), onTertiaryContainer = Color(0xFFFFD8E8),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318), onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceContainerLowest = Color(0xFF0E0E13), surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25), surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
    outline = Color(0xFF918F9A), outlineVariant = Color(0xFF46464F),
)

/** Pure black surfaces; containers stay a little lighter so cards remain visible. */
private fun ColorScheme.amoled() = copy(
    background = Color.Black, surface = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0B0B0E),
    surfaceContainer = Color(0xFF111115), surfaceContainerHigh = Color(0xFF18181D),
    surfaceContainerHighest = Color(0xFF202026),
)

/** Success and warning colors, which Material's ColorScheme has no slots for. */
class StatusColors(
    val success: Color, val onSuccess: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warningContainer: Color, val onWarningContainer: Color,
)

private val LightStatus = StatusColors(
    Color(0xFF2B6A46), Color.White, Color(0xFFB3F1C6), Color(0xFF00391E), Color(0xFFFFDDB4), Color(0xFF2A1700),
)
private val DarkStatus = StatusColors(
    Color(0xFF98D5AB), Color(0xFF00391E), Color(0xFF0F5130), Color(0xFFB3F1C6), Color(0xFF653E00), Color(0xFFFFDDB4),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

//========== TYPOGRAPHY ==========
// Expressive: heavier display and headline weights than the Material defaults.

private val Base = Typography()
private val ExpressiveType = Base.copy(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.ExtraBold),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

//========== THEME ==========

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuilderTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= 31
    var scheme = when {
        dynamic && dark -> dynamicDarkColorScheme(context)
        dynamic -> dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    if (dark && settings.amoled) scheme = scheme.amoled()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = ExpressiveType,
            content = content,
        )
    }
}

/** Whether the current theme is dark, for the system bar icons. */
@Composable
fun isDarkTheme(settings: Settings) = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
