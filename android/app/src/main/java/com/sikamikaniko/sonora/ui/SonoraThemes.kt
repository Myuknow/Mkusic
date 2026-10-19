package com.sikamikaniko.sonora.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Aurora design system (v1.0.0).
 *
 * Every colour the app draws flows from [MaterialTheme.colorScheme]; signature
 * gradient accents flow from [LocalBrandBrush]. Screens never hard-code hues.
 *
 * Public API (relied on by the restyle agents):
 *  - [AppTheme]          — the selectable palettes.
 *  - [SonoraTheme]       — builds a ColorScheme + Typography + brand brush.
 *  - [LocalBrandBrush]   — the theme's 2-stop hero/CTA gradient.
 *  - [themeSwatch]       — representative colours for the Settings picker.
 */
enum class AppTheme(val label: String) {
    MIDNIGHT("Midnight"),
    AURORA("Aurora"),
    SUNSET("Sunset"),
    OCEAN("Ocean"),
    FOREST("Forest"),
    ROSE("Rosé"),
    AMOLED("Pure Black"),
    LIGHT("Daylight"),
    DYNAMIC("Dynamic")
}

/** The signature 2-stop gradient for hero surfaces, CTAs and now-playing accents. */
val LocalBrandBrush: ProvidableCompositionLocal<Brush> = staticCompositionLocalOf {
    Brush.linearGradient(listOf(Color(0xFF8A6CF2), Color(0xFF6C4CF1)))
}

// ---------------------------------------------------------------------------
// Per-theme accent pairs (used both for the ColorScheme and the brand brush)
// ---------------------------------------------------------------------------

private data class Accents(val a: Color, val b: Color, val c: Color = b)

private fun accentsFor(theme: AppTheme): Accents = when (theme) {
    AppTheme.MIDNIGHT -> Accents(Color(0xFF9A82FF), Color(0xFF6C4CF1), Color(0xFF5B8CFF))
    AppTheme.AURORA   -> Accents(Color(0xFF34E0C4), Color(0xFF3FA9F5), Color(0xFF6BE38A))
    AppTheme.SUNSET   -> Accents(Color(0xFFFF9E4A), Color(0xFFFF5F8D), Color(0xFFD65DB1))
    AppTheme.OCEAN    -> Accents(Color(0xFF35D0E8), Color(0xFF3B72F6), Color(0xFF5CE0FF))
    AppTheme.FOREST   -> Accents(Color(0xFF54D98C), Color(0xFF1FA871), Color(0xFF9BE86B))
    AppTheme.ROSE     -> Accents(Color(0xFFFF8FB8), Color(0xFFF15E8E), Color(0xFFFFA9C7))
    AppTheme.AMOLED   -> Accents(Color(0xFF4CE6C0), Color(0xFF2FBFA0), Color(0xFF4CE6C0))
    AppTheme.LIGHT    -> Accents(Color(0xFF6C4CF1), Color(0xFF8A6CF2), Color(0xFF3B72F6))
    AppTheme.DYNAMIC  -> Accents(Color(0xFF9A82FF), Color(0xFF6C4CF1), Color(0xFF5B8CFF))
}

// ---------------------------------------------------------------------------
// Colour schemes
// ---------------------------------------------------------------------------

private fun darkScheme(
    accent: Color,
    secondary: Color,
    background: Color,
    surface: Color,
    surfaceVariant: Color,
    outline: Color,
    onSurfaceVariant: Color = Color(0xFFAAB0C2),
    onAccent: Color = Color.White,
    text: Color = Color(0xFFECEDF3)
): ColorScheme = darkColorScheme(
    primary = accent,
    onPrimary = onAccent,
    primaryContainer = surfaceVariant,
    onPrimaryContainer = text,
    secondary = secondary,
    onSecondary = onAccent,
    tertiary = accent,
    onTertiary = onAccent,
    background = background,
    onBackground = text,
    surface = surface,
    onSurface = text,
    surfaceVariant = surfaceVariant,
    onSurfaceVariant = onSurfaceVariant,
    surfaceContainerLowest = background,
    surfaceContainerLow = surface,
    surfaceContainer = surfaceVariant,
    surfaceContainerHigh = surfaceVariant,
    surfaceContainerHighest = surfaceVariant,
    inverseSurface = text,
    inverseOnSurface = background,
    outline = outline,
    outlineVariant = outline,
    error = Color(0xFFFF6B6B),
    onError = Color.White
)

private fun schemeFor(theme: AppTheme): ColorScheme {
    val ac = accentsFor(theme)
    return when (theme) {
        AppTheme.MIDNIGHT -> darkScheme(
            accent = ac.a, secondary = ac.c,
            background = Color(0xFF0C0D12),
            surface = Color(0xFF15171F),
            surfaceVariant = Color(0xFF1F2230),
            outline = Color(0xFF2B2F3E),
            onSurfaceVariant = Color(0xFFA9AEC2)
        )
        AppTheme.AURORA -> darkScheme(
            accent = ac.a, secondary = ac.b,
            background = Color(0xFF07130F),
            surface = Color(0xFF0E1D18),
            surfaceVariant = Color(0xFF16302A),
            outline = Color(0xFF20463D),
            onSurfaceVariant = Color(0xFF9DC7BC),
            onAccent = Color(0xFF042019),
            text = Color(0xFFE6F4EF)
        )
        AppTheme.SUNSET -> darkScheme(
            accent = ac.a, secondary = ac.b,
            background = Color(0xFF160A10),
            surface = Color(0xFF23121B),
            surfaceVariant = Color(0xFF351B28),
            outline = Color(0xFF4A2537),
            onSurfaceVariant = Color(0xFFD3A6B8),
            onAccent = Color(0xFF2A1000),
            text = Color(0xFFF7E7EC)
        )
        AppTheme.OCEAN -> darkScheme(
            accent = ac.a, secondary = ac.b,
            background = Color(0xFF061019),
            surface = Color(0xFF0C1E2C),
            surfaceVariant = Color(0xFF12324A),
            outline = Color(0xFF1D4A66),
            onSurfaceVariant = Color(0xFF9BC2D9),
            onAccent = Color(0xFF00131F),
            text = Color(0xFFE2F1F8)
        )
        AppTheme.FOREST -> darkScheme(
            accent = ac.a, secondary = ac.b,
            background = Color(0xFF08120C),
            surface = Color(0xFF102019),
            surfaceVariant = Color(0xFF193227),
            outline = Color(0xFF244A39),
            onSurfaceVariant = Color(0xFF9DC4AC),
            onAccent = Color(0xFF042113),
            text = Color(0xFFE4F2E9)
        )
        AppTheme.ROSE -> darkScheme(
            accent = ac.a, secondary = ac.b,
            background = Color(0xFF150A10),
            surface = Color(0xFF23121A),
            surfaceVariant = Color(0xFF351C27),
            outline = Color(0xFF4C2735),
            onSurfaceVariant = Color(0xFFD6A8B8),
            onAccent = Color(0xFF2A0A16),
            text = Color(0xFFF7E6EC)
        )
        AppTheme.AMOLED -> darkScheme(
            accent = ac.a, secondary = ac.b,
            background = Color(0xFF000000),
            surface = Color(0xFF070707),
            surfaceVariant = Color(0xFF121212),
            outline = Color(0xFF242424),
            onSurfaceVariant = Color(0xFF9A9A9A),
            onAccent = Color(0xFF00120E),
            text = Color(0xFFF2F2F2)
        )
        AppTheme.LIGHT -> lightColorScheme(
            primary = ac.a,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFE9E2FF),
            onPrimaryContainer = Color(0xFF23105E),
            secondary = ac.c,
            onSecondary = Color.White,
            tertiary = ac.b,
            onTertiary = Color.White,
            background = Color(0xFFF7F6FB),
            onBackground = Color(0xFF16151C),
            surface = Color(0xFFFFFFFF),
            onSurface = Color(0xFF16151C),
            surfaceVariant = Color(0xFFEEECF4),
            onSurfaceVariant = Color(0xFF585765),
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = Color(0xFFF7F6FB),
            surfaceContainer = Color(0xFFF1EFF7),
            surfaceContainerHigh = Color(0xFFEBE9F3),
            surfaceContainerHighest = Color(0xFFE6E3F0),
            outline = Color(0xFFCBC8D6),
            outlineVariant = Color(0xFFDCD9E6),
            inverseSurface = Color(0xFF2A2A33),
            inverseOnSurface = Color(0xFFF3F1F8),
            error = Color(0xFFD3352B),
            onError = Color.White
        )
        AppTheme.DYNAMIC -> schemeFor(AppTheme.MIDNIGHT) // real dynamic resolved in composable
    }
}

// ---------------------------------------------------------------------------
// Typography — refined, tighter display sizes, comfortable body rhythm.
// ---------------------------------------------------------------------------

private val AuroraTypography: Typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = 21.sp)
    )
}

// ---------------------------------------------------------------------------
// Public composable + swatch helper
// ---------------------------------------------------------------------------

@Composable
fun SonoraTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val scheme: ColorScheme = when {
        theme == AppTheme.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        theme == AppTheme.DYNAMIC -> schemeFor(AppTheme.MIDNIGHT) // graceful fallback < API 31
        else -> schemeFor(theme)
    }

    val ac = accentsFor(theme)
    val brandBrush: Brush = if (theme == AppTheme.DYNAMIC) {
        Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
    } else {
        Brush.linearGradient(listOf(ac.a, ac.b))
    }

    CompositionLocalProvider(LocalBrandBrush provides brandBrush) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AuroraTypography,
            content = content
        )
    }
}

/** 2–3 representative colours per theme, for the Settings theme picker chips. */
fun themeSwatch(theme: AppTheme): List<Color> {
    val ac = accentsFor(theme)
    return when (theme) {
        AppTheme.AMOLED -> listOf(Color(0xFF000000), ac.a)
        AppTheme.LIGHT -> listOf(Color(0xFFFFFFFF), ac.a, ac.b)
        else -> listOf(ac.a, ac.b, ac.c)
    }
}
