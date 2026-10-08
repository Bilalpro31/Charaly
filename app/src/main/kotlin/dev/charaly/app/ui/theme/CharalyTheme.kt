package dev.charaly.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import dev.charaly.app.R
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyDesign
import dev.charaly.app.ui.design.CharalyType
import dev.charaly.app.ui.design.LocalAtmosphere
import dev.charaly.app.ui.design.LocalDesign
import dev.charaly.runtime.presentation.ResolvedTheme

/**
 * Figtree, bundled.
 *
 * V5's typeface, in one variable file per style. Weights 400-700 all resolve through the
 * same font resource, so a bold headline and a quiet caption are genuinely the same family
 * rather than a system fallback that happens to be nearby.
 */
val Figtree: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(
        resId = R.font.figtree_variable,
        weight = FontWeight.Normal,
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.figtree_variable,
        weight = FontWeight.Medium,
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.figtree_variable,
        weight = FontWeight.SemiBold,
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.figtree_variable,
        weight = FontWeight.Bold,
    ),
    androidx.compose.ui.text.font.Font(
        resId = R.font.figtree_italic_variable,
        weight = FontWeight.Normal,
        style = FontStyle.Italic,
    ),
)

/**
 * Charaly's theme root, and the only place a colour enters the UI.
 *
 * ## True black, and why it is not negotiable
 *
 * The background is `#000000`. It was a navy-black (`#0E0D12`) until it was not, and the
 * reason it had to change is worth keeping:
 *
 * > A dark navy page imposes a cool cast on everything drawn on it. Generated artwork and
 * > licensed artwork both appear slightly discoloured, as though the page were tinting the
 * > picture. `#000000` has no cast to impose, which is what lets a red-accented hero and a
 * > gold-accented one both look like themselves.
 *
 * ## Neutral shell, coloured world
 *
 * `primary` here is Charaly's own near-white, **not a hue**. A pack supplies the accent
 * through [CharalyAtmosphere], and the shell must not compete with it: a purple `primary`
 * in the scheme was the single largest reason the app read as a generic dark template
 * rather than as a story product.
 *
 * ## Dark first
 *
 * A story app is read at night, and artwork reads better on black than on white. The light
 * scheme exists so the OS setting is honoured rather than overridden, and it uses the same
 * near-white-on-graphite structure so the two do not feel like different apps.
 */
@Composable
fun CharalyTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = charalyTypography(),
    ) {
        CompositionLocalProvider(
            LocalDesign provides CharalyDesign(),
            // Neutral by default. A pack overrides this for its own screens; the shell
            // never does, because the shell has no identity of its own to show.
            LocalAtmosphere provides CharalyAtmosphere.Neutral,
        ) {
            content()
        }
    }
}

/**
 * Wraps content in a world's identity.
 *
 * The single route by which a pack's colours reach composables. A screen cannot pick up a
 * hue that did not come through here, which is what makes "the world provides the colour" a
 * structural guarantee rather than a review item.
 */
@Composable
fun CharalyThemeScope(
    theme: ResolvedTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAtmosphere provides CharalyAtmosphere.of(theme)) {
        content()
    }
}

/**
 * The editorial type scale.
 *
 * Material's own slots are filled from it so a screen that reaches for `bodyLarge` gets
 * Charaly's idea of body text rather than Material's - which is what stops a rebuild from
 * quietly producing two type systems.
 */
private fun charalyTypography(): Typography {
    fun style(
        type: CharalyType,
        weight: FontWeight = FontWeight.Normal,
    ) = TextStyle(
        fontFamily = Figtree,
        fontWeight = weight,
        fontSize = type.size,
        lineHeight = type.lineHeight,
        letterSpacing = type.tracking,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

    return Typography(
        displayLarge = style(CharalyType.DISPLAY, FontWeight.Bold),
        displayMedium = style(CharalyType.DISPLAY_COMPACT, FontWeight.Bold),
        displaySmall = style(CharalyType.TITLE, FontWeight.SemiBold),
        headlineLarge = style(CharalyType.TITLE, FontWeight.SemiBold),
        headlineMedium = style(CharalyType.SECTION, FontWeight.SemiBold),
        headlineSmall = style(CharalyType.LEAD, FontWeight.Medium),
        titleLarge = style(CharalyType.LEAD, FontWeight.SemiBold),
        titleMedium = style(CharalyType.SECONDARY, FontWeight.Medium),
        titleSmall = style(CharalyType.SECONDARY, FontWeight.Medium),
        bodyLarge = style(CharalyType.BODY),
        bodyMedium = style(CharalyType.SECONDARY),
        bodySmall = style(CharalyType.MICRO),
        labelLarge = style(CharalyType.SECONDARY, FontWeight.SemiBold),
        labelMedium = style(CharalyType.MICRO, FontWeight.Medium),
        labelSmall = style(CharalyType.MICRO, FontWeight.Medium),
    )
}

/**
 * Dark: V5's stepped greys on the `#0F0F11` floor.
 *
 * Exposed rather than private so the app's own tests can assert the background really is
 * the V5 floor. A theme that drifts back to navy is invisible to review and obvious on a
 * device.
 */
val DarkScheme = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFFF4F4F6),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF1C1C1F),
    onPrimaryContainer = Color(0xFFF4F4F6),
    secondary = Color(0xFF9B9BA4),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF131315),
    onSecondaryContainer = Color(0xFFE6E6EA),
    tertiary = Color(0xFF6E6E76),
    onTertiary = Color(0xFFF4F4F6),
    tertiaryContainer = Color(0xFF131315),
    onTertiaryContainer = Color(0xFFB8B8C0),
    background = Color(0xFF0F0F11),
    onBackground = Color(0xFFF4F4F6),
    surface = Color(0xFF0F0F11),
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFF9B9BA4),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF131315),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF232326),
    surfaceContainerHighest = Color(0xFF2B2B2F),
    outline = Color(0x1AFFFFFF),
    outlineVariant = Color(0xFF131315),
    error = Color(0xFFFF6B5A),
    onError = Color(0xFF1A0A08),
    errorContainer = Color(0xFF2A0F0B),
    onErrorContainer = Color(0xFFFFDAD4),
    scrim = Color(0xF20F0F11),
)

/**
 * Light: quiet, and structurally the same app.
 *
 * The dark scheme is the designed experience. This exists so the OS setting is honoured
 * rather than overridden.
 */
private val LightScheme = androidx.compose.material3.lightColorScheme(
    primary = Color(0xFF15151A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8EC),
    onPrimaryContainer = Color(0xFF15151A),
    secondary = Color(0xFF4A4A55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDF1),
    onSecondaryContainer = Color(0xFF1A1A20),
    tertiary = Color(0xFF6E6E78),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF1F1F4),
    onTertiaryContainer = Color(0xFF232329),
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF15151A),
    surface = Color(0xFFFAFAFB),
    onSurface = Color(0xFF15151A),
    surfaceVariant = Color(0xFFF0F0F3),
    onSurfaceVariant = Color(0xFF4A4A55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Color(0xFFF4F4F7),
    surfaceContainerHigh = Color(0xFFEDEDF1),
    surfaceContainerHighest = Color(0xFFE6E6EB),
    outline = Color(0xFFDDDDE2),
    outlineVariant = Color(0xFFE9E9ED),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xB3000000),
)
