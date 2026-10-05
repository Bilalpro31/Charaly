package dev.charaly.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Charaly's design tokens.
 *
 * These are deliberately few. A design system for a story app should be about
 * rhythm and restraint: one background, two elevation steps, one accent per
 * screen, and a type scale that does all the work.
 */

/** 4dp base grid. Every spacing value is a multiple of 4. */
@Immutable
data class CharalySpacing(
    val hair: androidx.compose.ui.unit.Dp = 2.dp,
    val xxs: androidx.compose.ui.unit.Dp = 4.dp,
    val xs: androidx.compose.ui.unit.Dp = 8.dp,
    val sm: androidx.compose.ui.unit.Dp = 12.dp,
    val md: androidx.compose.ui.unit.Dp = 16.dp,
    val lg: androidx.compose.ui.unit.Dp = 20.dp,
    val xl: androidx.compose.ui.unit.Dp = 24.dp,
    val xxl: androidx.compose.ui.unit.Dp = 32.dp,
    val section: androidx.compose.ui.unit.Dp = 28.dp,
    /** Horizontal page gutter, which grows on tablets. */
    val gutter: androidx.compose.ui.unit.Dp = 20.dp,
)

/**
 * Corner radii.
 *
 * Cards are round enough to feel like objects rather than rows in a table, and
 * the composer is the roundest thing on screen because it is the thing you touch.
 */
@Immutable
data class CharalyRadii(
    val xs: androidx.compose.ui.unit.Dp = 8.dp,
    val sm: androidx.compose.ui.unit.Dp = 12.dp,
    val md: androidx.compose.ui.unit.Dp = 18.dp,
    val lg: androidx.compose.ui.unit.Dp = 24.dp,
    val xl: androidx.compose.ui.unit.Dp = 32.dp,
    val pill: androidx.compose.ui.unit.Dp = 999.dp,
) {
    val shapeXs get() = RoundedCornerShape(xs)
    val shapeSm get() = RoundedCornerShape(sm)
    val shapeMd get() = RoundedCornerShape(md)
    val shapeLg get() = RoundedCornerShape(lg)
    val shapeXl get() = RoundedCornerShape(xl)
    val shapePill get() = RoundedCornerShape(pill)
    val composerShape get() = RoundedCornerShape(xl)
}

/** Icon sizes, so a chevron never sits next to a hero glyph by accident. */
@Immutable
data class CharalyIconSizes(
    val micro: androidx.compose.ui.unit.Dp = 12.dp,
    val small: androidx.compose.ui.unit.Dp = 16.dp,
    val medium: androidx.compose.ui.unit.Dp = 20.dp,
    val large: androidx.compose.ui.unit.Dp = 24.dp,
    val hero: androidx.compose.ui.unit.Dp = 32.dp,
)

/** Elevation, expressed as tinted surface layers rather than shadows. */
@Immutable
data class CharalyElevation(
    val flat: androidx.compose.ui.unit.Dp = 0.dp,
    val raised: androidx.compose.ui.unit.Dp = 1.dp,
    val floating: androidx.compose.ui.unit.Dp = 4.dp,
)

/** Motion durations. Short, and never on layout-critical properties. */
@Immutable
data class CharalyMotion(
    val quick: Int = 120,
    val standard: Int = 220,
    val slow: Int = 380,
)

@Immutable
data class CharalyTokens(
    val spacing: CharalySpacing = CharalySpacing(),
    val radii: CharalyRadii = CharalyRadii(),
    val icons: CharalyIconSizes = CharalyIconSizes(),
    val elevation: CharalyElevation = CharalyElevation(),
    val motion: CharalyMotion = CharalyMotion(),
    val typography: Typography = CharalyTypography.scale,
)

val LocalCharalyTokens = staticCompositionLocalOf { CharalyTokens() }

/**
 * The type scale.
 *
 * Display and headline styles get tighter line heights and negative tracking so
 * large text reads as editorial rather than as a title bar; body styles get
 * looser leading because the chat is read for minutes at a time.
 */
object CharalyTypography {

    private val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    val scale: Typography = Typography(
        displayLarge = TextStyle(
            fontFamily = FontFamily.Default,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            lineHeight = 44.sp,
            letterSpacing = (-0.8).sp,
            lineHeightStyle = trim,
        ),
        displayMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 32.sp,
            lineHeight = 36.sp,
            letterSpacing = (-0.5).sp,
        ),
        displaySmall = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 27.sp,
            lineHeight = 32.sp,
            letterSpacing = (-0.3).sp,
        ),
        headlineLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 23.sp,
            lineHeight = 28.sp,
            letterSpacing = (-0.2).sp,
        ),
        headlineMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            lineHeight = 25.sp,
        ),
        headlineSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 17.sp,
            lineHeight = 22.sp,
        ),
        titleLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            lineHeight = 23.sp,
        ),
        titleMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        titleSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyLarge = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.15.sp,
        ),
        bodyMedium = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            letterSpacing = 0.15.sp,
        ),
        bodySmall = TextStyle(
            fontWeight = FontWeight.Normal,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.2.sp,
        ),
        labelLarge = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            letterSpacing = 0.1.sp,
        ),
        labelMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            letterSpacing = 0.3.sp,
        ),
        labelSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            letterSpacing = 0.6.sp,
        ),
    )

    /** Prose style for narration: slightly larger, looser, italic-adjacent feel. */
    val narration = TextStyle(
        fontSize = 15.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.1.sp,
    )

    /** The voice a character speaks in. */
    val dialogue = TextStyle(
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.1.sp,
    )

    /** A quiet, monospaced technical label for the developer panel. */
    val technical = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        lineHeight = 17.sp,
    )
}