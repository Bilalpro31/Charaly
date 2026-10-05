package dev.charaly.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import dev.charaly.runtime.presentation.ResolvedTheme

/**
 * Charaly's brand palette.
 *
 * Dark-first, because a story app is used at night and because artwork reads far
 * better on a deep background. The palette is warm-neutral rather than blue-black,
 * so a colourful pack cover never looks like it is pasted onto the screen.
 *
 * The single rule: Charaly owns the *structure* (backgrounds, surfaces, text) and
 * a pack owns only the *accent*. There is no per-pack dark mode.
 */
object CharalyColors {

    // Brand accents (used when a story has no accent of its own)
    val BrandViolet = Color(0xFF8B7BF0)
    val BrandRose = Color(0xFFE0659B)
    val BrandAmber = Color(0xFFF2B25C)

    // Dark surfaces, from deepest to most raised
    val NightBase = Color(0xFF0E0D12)
    val NightSurface = Color(0xFF16151C)
    val NightSurfaceRaised = Color(0xFF1E1C25)
    val NightSurfaceHigh = Color(0xFF272430)
    val NightOutline = Color(0xFF322F3C)
    val NightInk = Color(0xFFF3F1F8)
    val NightInkMuted = Color(0xFFA6A2B4)
    val NightInkFaint = Color(0xFF6F6B7D)

    // Light surfaces
    val PaperBase = Color(0xFFFBFAFD)
    val PaperSurface = Color(0xFFFFFFFF)
    val PaperSurfaceRaised = Color(0xFFF4F2F8)
    val PaperOutline = Color(0xFFE3E0EC)
    val PaperInk = Color(0xFF1A1822)
    val PaperInkMuted = Color(0xFF5C586B)
    val PaperInkFaint = Color(0xFF8B8798)

    // Semantic
    val Ready = Color(0xFF5BD6A4)
    val ReadyMuted = Color(0xFF1E3A31)
    val Busy = Color(0xFFF2C14E)
    val BusyMuted = Color(0xFF3A2E14)
    val Trouble = Color(0xFFF2735F)
    val TroubleMuted = Color(0xFF3A1F1B)
}

private val CharalyDarkColors = darkColorScheme(
    primary = CharalyColors.BrandViolet,
    onPrimary = Color(0xFF17131F),
    primaryContainer = Color(0xFF2A2340),
    onPrimaryContainer = CharalyColors.NightInk,
    secondary = CharalyColors.BrandRose,
    onSecondary = Color(0xFF241019),
    secondaryContainer = Color(0xFF3A1F2C),
    onSecondaryContainer = CharalyColors.NightInk,
    tertiary = CharalyColors.BrandAmber,
    onTertiary = Color(0xFF241A0C),
    tertiaryContainer = Color(0xFF3A2C17),
    onTertiaryContainer = CharalyColors.NightInk,
    background = CharalyColors.NightBase,
    onBackground = CharalyColors.NightInk,
    surface = CharalyColors.NightSurface,
    onSurface = CharalyColors.NightInk,
    surfaceVariant = CharalyColors.NightSurfaceRaised,
    onSurfaceVariant = CharalyColors.NightInkMuted,
    surfaceContainer = CharalyColors.NightSurfaceRaised,
    surfaceContainerHigh = CharalyColors.NightSurfaceHigh,
    surfaceContainerHighest = Color(0xFF2E2B38),
    outline = CharalyColors.NightOutline,
    outlineVariant = Color(0xFF272430),
    error = CharalyColors.Trouble,
    onError = Color(0xFF2A1310),
    errorContainer = CharalyColors.TroubleMuted,
    onErrorContainer = Color(0xFFFFDAD4),
    scrim = Color(0xCC050409),
)

private val CharalyLightColors = lightColorScheme(
    primary = Color(0xFF5B4BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E3FF),
    onPrimaryContainer = Color(0xFF1E1636),
    secondary = Color(0xFFB33A72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1EE),
    onSecondaryContainer = Color(0xFF3B0A22),
    tertiary = Color(0xFF9A6412),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE9C7),
    onTertiaryContainer = Color(0xFF2E1D02),
    background = CharalyColors.PaperBase,
    onBackground = CharalyColors.PaperInk,
    surface = CharalyColors.PaperSurface,
    onSurface = CharalyColors.PaperInk,
    surfaceVariant = CharalyColors.PaperSurfaceRaised,
    onSurfaceVariant = CharalyColors.PaperInkMuted,
    surfaceContainer = CharalyColors.PaperSurfaceRaised,
    surfaceContainerHigh = Color(0xFFEFEDF5),
    surfaceContainerHighest = Color(0xFFE8E5F0),
    outline = CharalyColors.PaperOutline,
    outlineVariant = Color(0xFFEDEBF3),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0x9910121A),
)

/**
 * Colours that Material's scheme has no slot for.
 *
 * Pack accents and status colours live here rather than being hardcoded in
 * screens, so "which green means ready" has exactly one answer.
 */
@Immutable
data class CharalyExtendedColors(
    val success: Color,
    val successSurface: Color,
    val warning: Color,
    val warningSurface: Color,
    val danger: Color,
    val dangerSurface: Color,
    val scrimTop: Color,
    val artworkScrim: Color,
    val hairline: Color,
    val glass: Color,
    val glassStrong: Color,
    val narration: Color,
) {
    companion object {
        val Dark = CharalyExtendedColors(
            success = CharalyColors.Ready,
            successSurface = Color(0xFF16261F),
            warning = CharalyColors.Busy,
            warningSurface = Color(0xFF2A2213),
            danger = CharalyColors.Trouble,
            dangerSurface = CharalyColors.TroubleMuted,
            scrimTop = Color(0xFF050409),
            artworkScrim = Color(0xCC0B0A10),
            hairline = Color(0x1AFFFFFF),
            glass = Color(0x14FFFFFF),
            glassStrong = Color(0x24FFFFFF),
            narration = Color(0xFFCFCBDC),
        )

        val Light = CharalyExtendedColors(
            success = Color(0xFF1F8A5B),
            successSurface = Color(0xFFE3F5EC),
            warning = Color(0xFF9A6412),
            warningSurface = Color(0xFFFFF2DC),
            danger = Color(0xFFB3261E),
            dangerSurface = Color(0xFFFBE4E2),
            scrimTop = Color(0xFF0B0A10),
            artworkScrim = Color(0x9914141C),
            hairline = Color(0x14000000),
            glass = Color(0x0A000000),
            glassStrong = Color(0x14000000),
            narration = Color(0xFF4A4757),
        )
    }
}

val LocalExtendedColors = androidx.compose.runtime.staticCompositionLocalOf {
    CharalyExtendedColors.Dark
}

/**
 * The per-screen accent, provided by whichever story pack is on show.
 *
 * A screen reads this instead of passing colours down by hand, which is what
 * lets a Miraculous screen feel violet and a Neon District screen feel cyan while
 * both keep Charaly's surfaces.
 */
val LocalStoryAccent = androidx.compose.runtime.staticCompositionLocalOf { AccentTokens() }

@Immutable
data class AccentTokens(
    val primary: Color = CharalyColors.BrandViolet,
    val secondary: Color = CharalyColors.BrandRose,
    val accent: Color = CharalyColors.BrandAmber,
    val onAccent: Color = Color(0xFF15121C),
) {
    companion object {
        val Brand = AccentTokens()

        fun of(theme: ResolvedTheme): AccentTokens = AccentTokens(
            primary = theme.primary.toColor(),
            secondary = theme.secondary.toColor(),
            accent = theme.accent.toColor(),
            onAccent = readableOn(theme.primary.toColor()),
        )
    }
}

/** The theme, with motion/typography/accessibility aware defaults. */
@Composable
fun CharalyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = CharalyTokens()
    val extended = if (darkTheme) CharalyExtendedColors.Dark else CharalyExtendedColors.Light

    MaterialTheme(
        colorScheme = if (darkTheme) CharalyDarkColors else CharalyLightColors,
        typography = tokens.typography,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = tokens.radii.shapeXs,
            small = tokens.radii.shapeSm,
            medium = tokens.radii.shapeMd,
            large = tokens.radii.shapeLg,
            extraLarge = tokens.radii.shapeXl,
        ),
    ) {
        CompositionLocalProvider(
            LocalCharalyTokens provides tokens,
            LocalExtendedColors provides extended,
            LocalStoryAccent provides AccentTokens.Brand,
        ) {
            content()
        }
    }
}

/** ARGB long -> Compose Color, with a safe fallback. */
fun Long.toColor(): Color = Color(if (this == 0L) 0xFF8B7BF0 else this)

/**
 * Picks black or white text for an accent background.
 *
 * Uses relative luminance rather than a fixed pair so a gold accent and a violet
 * accent both get readable labels.
 */
fun readableOn(background: Color): Color {
    val r = background.red
    val g = background.green
    val b = background.blue
    val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
    return if (luminance > 0.55f) Color(0xFF121016) else Color(0xFFF7F5FB)
}

/** Convenience accessors used all over the UI layer. */
object Charaly {
    val tokens: CharalyTokens
        @Composable @ReadOnlyComposable get() = LocalCharalyTokens.current

    val colors: CharalyExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    val accent: AccentTokens
        @Composable @ReadOnlyComposable get() = LocalStoryAccent.current
}