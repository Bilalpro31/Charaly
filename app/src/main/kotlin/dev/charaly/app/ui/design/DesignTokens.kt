package dev.charaly.app.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.charaly.runtime.presentation.ResolvedTheme
import kotlin.math.pow

/**
 * CHARALY'S DESIGN TOKENS.
 *
 * ## The whole visual identity, in one file
 *
 * Charaly itself is neutral. The background is true black, the ink is near-white, and
 * **the world provides the colour**. That is not a stylistic preference; it is the
 * mechanism that makes three unrelated worlds read as three products rather than as one
 * app with three accent colours.
 *
 * So this file defines *structure* - scale, rhythm, weight, motion - and the pack theme
 * supplies *identity* through [CharalyAtmosphere]. A screen therefore cannot be built
 * with a hard-coded hue without going through the atmosphere, which is what makes "colour
 * comes from the world" a structural property rather than a convention.
 *
 * ## The type scale
 *
 * Editorial, not utilitarian. Six steps, each with a job:
 *
 * ```
 *   DISPLAY    56sp   a screen's one idea
 *   TITLE      34sp   the name of the thing on screen
 *   SECTION    22sp   a heading inside a screen
 *   LEAD       19sp   the line that sets up a paragraph
 *   BODY       17sp   prose, and anything read for minutes
 *   SECONDARY  15sp   supporting text, still comfortable
 *   MICRO      12sp   labels and metadata, which should nearly disappear
 * ```
 *
 * The gap between DISPLAY and MICRO is the point. Metadata should almost disappear until
 * asked for; a UI where a timestamp competes with a title has no hierarchy at all.
 */
enum class CharalyType(
    val size: TextUnit,
    val lineHeight: TextUnit,
    val tracking: TextUnit,
) {
    DISPLAY(56.sp, 58.sp, (-1.4).sp),
    DISPLAY_COMPACT(44.sp, 47.sp, (-1.0).sp),
    TITLE(34.sp, 38.sp, (-0.6).sp),
    SECTION(22.sp, 27.sp, (-0.3).sp),
    LEAD(19.sp, 28.sp, (-0.1).sp),
    BODY(17.sp, 26.sp, 0.sp),
    SECONDARY(15.sp, 23.sp, 0.05.sp),
    MICRO(12.sp, 16.sp, 0.35.sp),
}

/**
 * Spacing.
 *
 * A 4dp grid with one exception that matters: the *gutter* grows on wide screens so
 * content does not hug a tablet's bezel, and the *measure* caps so prose does not become
 * a single unreadable line across a desktop window.
 */
data class CharalySpace(
    /** 2dp. Optical nudges only: the gap between a name and its label. */
    val xxs: Dp = 2.dp,
    /** 4dp. The base unit. */
    val unit: Dp = 4.dp,
    /** 8dp. */
    val xs: Dp = 8.dp,
    /** 12dp. */
    val sm: Dp = 12.dp,
    /** 16dp. */
    val md: Dp = 16.dp,
    /** 24dp. */
    val lg: Dp = 24.dp,
    /** 32dp. */
    val xl: Dp = 32.dp,
    /** 48dp. */
    val xxl: Dp = 48.dp,
    /** 72dp. Between major sections on a scrolling screen. */
    val section: Dp = 72.dp,
    /** Horizontal page margin. */
    val gutter: Dp = 24.dp,
    /** Wider gutter on a tablet, so content is inset from the bezel. */
    val gutterWide: Dp = 48.dp,
    /**
     * The comfortable measure for prose.
     *
     * 68 characters is the classic recommendation and it is the reason the chat does not
     * become one long line on a tablet.
     */
    val measureMax: Dp = 640.dp,
)

/**
 * Corner radii.
 *
 * Deliberately few, and mostly *not* rounded rectangles.
 *
 * - `none` for full-bleed artwork, which should meet the screen edge cleanly.
 * - `soft` for cards and sheets.
 * - `pill` for compact controls only.
 *
 * The previous design used five radii across every surface in the app, which is what made
 * it read as a grid of cards. A sea of rounded rectangles is a shape language for
 * dashboards; a cinema poster does not have one.
 */
data class CharalyShape(
    val none: Dp = 0.dp,
    /** 6dp. Artwork and full-bleed bands. */
    val cut: Dp = 6.dp,
    /** 14dp. Cards, sheets. */
    val soft: Dp = 14.dp,
    /** 24dp. Bottom sheets and the composer's inner field. */
    val sheet: Dp = 24.dp,
    val pill: Dp = 999.dp,
)

/**
 * Motion.
 *
 * ## Why these numbers and not "it feels nice"
 *
 * Three durations, three jobs:
 *
 * ```
 *   QUICK     200ms  a control acknowledging a press
 *   STANDARD  320ms  content arriving, sheets, screen changes
 *   CINEMATIC 620ms  entering a world
 * ```
 *
 * `CINEMATIC` is the only one above half a second and it is used exactly once: when a
 * world takes over the screen. A 620ms transition used for a filter change would be
 * intolerable; the same transition used once, deliberately, to cross from *browsing* to
 * *being somewhere* is the reason the app has an entrance.
 *
 * Every value is routed through the motion policy, so "Reduce motion" and the system's
 * "Remove animations" both reduce them to zero - and a zero duration means the state
 * still changes, it just does not travel.
 */
data class CharalyTiming(
    val quick: Int = 200,
    val standard: Int = 320,
    val cinematic: Int = 620,
    /** The cross-fade used when artwork changes under a title. */
    val crossfade: Int = 380,
)

/**
 * Surfaces.
 *
 * On true black, the steps above black exist for *elevation*, not for tint. `#050505` and
 * `#080808` are indistinguishable from black at arm's length on a phone, which is the
 * point: a card should be felt rather than seen.
 */
data class CharalySurface(
    val void: Color = Color(0xFF000000),
    val base: Color = Color(0xFF050505),
    val raised: Color = Color(0xFF0A0A0A),
    val elevated: Color = Color(0xFF111111),
    val overlay: Color = Color(0xFF171717),
    /** Translucent white, for a control floating over artwork. */
    val glass: Color = Color(0x1AFFFFFF),
    val glassStrong: Color = Color(0x2EFFFFFF),
)

/**
 * Ink.
 *
 * Three levels on black, and the third is genuinely dim rather than "slightly less
 * white". Metadata that competes with a title is metadata that has been given too much
 * weight.
 */
data class CharalyInk(
    val primary: Color = Color(0xFFF7F7F8),
    val secondary: Color = Color(0xFFA6A6B0),
    val muted: Color = Color(0xFF6E6E79),
    /** Narration, between ink and secondary: prose, but not speech. */
    val prose: Color = Color(0xFFC4C4CD),
)

/**
 * THE ATMOSPHERE.
 *
 * ## Structure versus identity, restated as a type
 *
 * ```
 *   structure   background, surfaces, ink     Charaly's. Identical everywhere.
 *   identity    accent, gradient, glow        the world's. Per screen.
 * ```
 *
 * A pack supplies an accent and, where it declares them, gradient stops. It can never
 * supply a background. That is the whole contract, and [CharalyThemeScope] is the only
 * way a pack's colours reach the UI - so a screen physically cannot pick up a hue that
 * did not come from a world.
 *
 * ## Why glow exists at all
 *
 * A cinematic screen needs a light source, and the pack's accent is the only honest place
 * to put one. `glow` is what a hero's highlight blooms into and what a chat header washes
 * down from; `gradient` is the declared atmospheric band. Both are derived, so a pack
 * that declares nothing gets black, which is correct.
 */
data class CharalyAtmosphere(
    val accent: Color,
    val secondary: Color,
    val highlight: Color,
    /** Two declared stops, or null when the pack declared no gradient. */
    val gradient: Pair<Color, Color>?,
) {
    val hasGradient: Boolean get() = gradient != null

    /** Readable ink for a control filled with [accent]. Luminance-based, not a guess. */
    val onAccent: Color get() = readableOn(accent)

    /**
     * The atmospheric wash for a header.
     *
     * A short band of the pack's colour fading into true black, never a tint across the
     * whole screen: a filter over an entire conversation makes every scene look the same.
     */
    fun wash(): List<Color> = listOf(
        accent.copy(alpha = 0.16f),
        secondary.copy(alpha = 0.07f),
        Color(0xFF000000),
    )

    /**
     * A radial bloom for a hero's light source.
     *
     * Two stops rather than a flat wash, because a glow with a hard edge reads as a
     * rectangle of colour and not as light.
     */
    fun bloom(): List<Color> = listOf(
        highlight.copy(alpha = 0.30f),
        accent.copy(alpha = 0.10f),
        Color(0x00000000),
    )

    companion object {
        /**
         * Charaly's own atmosphere.
         *
         * Near-white on black. With no world on show there is nothing for a hue to belong
         * to, so the shell's accent is deliberately achromatic - which is also why a
         * violet cannot creep back in through a default argument.
         */
        val Neutral: CharalyAtmosphere = CharalyAtmosphere(
            accent = Color(0xFFF5F5F7),
            secondary = Color(0xFF9E9EA8),
            highlight = Color(0xFFFFFFFF),
            gradient = null,
        )

        /** Projects a pack's resolved theme. The single place colours enter the UI. */
        fun of(theme: ResolvedTheme): CharalyAtmosphere = CharalyAtmosphere(
            accent = theme.primary.toCompose(),
            secondary = theme.secondary.toCompose(),
            highlight = theme.accent.toCompose(),
            gradient = if (theme.hasGradient) {
                theme.gradientStart.toCompose() to theme.gradientEnd.toCompose()
            } else {
                null
            },
        )
    }
}

/**
 * Picks black or white for a filled accent.
 *
 * ## Why this computes the real thing
 *
 * An earlier version weighted the raw sRGB bytes - `0.2126 * r + 0.7152 * g + 0.0722 * b` -
 * and called it WCAG luminance. It is not: WCAG **linearises** each channel first, because
 * the eye does not perceive light linearly. On a mid-tone that difference is large enough to
 * flip the answer, which is how a saturated blue gets white text when black was correct.
 *
 * So this is the real formula, and it is used for one decision only: what colour a label
 * printed on an accent-filled control should be. The alternative - a fixed pair - always
 * breaks for one end of the range, and it is always the same pack that ships broken.
 */
fun readableOn(background: Color): Color {
    val l = background.relativeLuminance()
    val onWhite = 1.05f / (l + 0.05f)
    val onBlack = (l + 0.05f) / 0.05f
    return if (onBlack >= onWhite) Color(0xFF000000) else Color(0xFFFFFFFF)
}

/** Long ARGB -> Compose colour. 0 means "unset", and becomes Charaly's own ink. */
fun Long.toCompose(): Color =
    if (this == 0L) Color(0xFFF5F5F7) else Color(this)

/**
 * WCAG 2.1 relative luminance.
 *
 * The channel transfer function matters and is the part usually left out: sRGB bytes are
 * gamma-encoded, so they are weighted only *after* being linearised. Skipping it makes a
 * saturated colour look far darker than it is and inverts the black-or-white decision for
 * every accent in the middle of the range.
 */
private fun Color.relativeLuminance(): Float {
    fun linear(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f
        else ((channel + 0.055f) / 1.055f).pow(2.4f)

    return 0.2126f * linear(red) +
        0.7152f * linear(green) +
        0.0722f * linear(blue)
}

/** Every token, in one value. Composed once at the theme root. */
data class CharalyDesign(
    val space: CharalySpace = CharalySpace(),
    val shape: CharalyShape = CharalyShape(),
    val timing: CharalyTiming = CharalyTiming(),
    val surface: CharalySurface = CharalySurface(),
    val ink: CharalyInk = CharalyInk(),
)

/**
 * The ready-made [androidx.compose.ui.graphics.Shape]s.
 *
 * Derived from [CharalyShape] rather than declared separately, so a change to a radius in
 * the tokens moves every surface that uses it. Three shapes, used for three purposes -
 * artwork, panels, compact controls - and nothing else.
 */
object CharalyShapes {
    /** Artwork and full-bleed bands. */
    val cut = RoundedCornerShape(CharalyShape().cut)

    /** Panels, cards, sheets. */
    val soft = RoundedCornerShape(CharalyShape().soft)

    /** A sheet: rounded at the top only, so it meets the screen edge cleanly. */
    val sheet = RoundedCornerShape(
        topStart = CharalyShape().sheet,
        topEnd = CharalyShape().sheet,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )

    /** Compact controls only. */
    val pill = RoundedCornerShape(CharalyShape().pill)
}
/**
 * The composition locals the tokens live in.
 *
 * Filled once, by the theme root. A token that could be provided from anywhere would
 * eventually be provided from somewhere inconsistent.
 */
val LocalDesign = staticCompositionLocalOf { CharalyDesign() }
val LocalAtmosphere = staticCompositionLocalOf { CharalyAtmosphere.Neutral }

/**
 * Everything a screen needs, in two reads.
 *
 * `Charaly.space` for rhythm and `Charaly.atmosphere` for identity. Nothing else is
 * required to draw a Charaly screen, which is what keeps the component library small
 * enough to hold in one's head.
 */
object Charaly {
    val design: CharalyDesign
        @Composable @ReadOnlyComposable get() = LocalDesign.current

    val space: CharalySpace
        @Composable @ReadOnlyComposable get() = LocalDesign.current.space

    val shape: CharalyShape
        @Composable @ReadOnlyComposable get() = LocalDesign.current.shape

    val timing: CharalyTiming
        @Composable @ReadOnlyComposable get() = LocalDesign.current.timing

    val surface: CharalySurface
        @Composable @ReadOnlyComposable get() = LocalDesign.current.surface

    val ink: CharalyInk
        @Composable @ReadOnlyComposable get() = LocalDesign.current.ink

    val atmosphere: CharalyAtmosphere
        @Composable @ReadOnlyComposable get() = LocalAtmosphere.current
}
