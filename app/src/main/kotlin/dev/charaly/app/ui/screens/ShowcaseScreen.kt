package dev.charaly.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.charaly.runtime.presentation.Loc
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.art.PackArtworkHero
import dev.charaly.app.ui.components.CharalyAction
import dev.charaly.app.ui.components.CharalyEmptyState
import dev.charaly.app.ui.components.CharalyIconButton
import dev.charaly.app.ui.components.CharalyPill
import dev.charaly.app.ui.components.CharalyQuietAction
import dev.charaly.app.ui.components.CharalySkeleton
import dev.charaly.app.ui.design.Charaly
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.CharalyShapes
import dev.charaly.app.ui.theme.motionDuration
import dev.charaly.runtime.domain.HeroTreatment
import dev.charaly.runtime.presentation.EmptyState
import dev.charaly.runtime.presentation.PackShowcase
import dev.charaly.runtime.presentation.SessionCard

/**
 * THE WORLD SHOWCASE.
 *
 * ## One idea: an invitation
 *
 * This screen replaces the old Story Pack detail, which listed a pack's characters,
 * locations, event programs, threads, factions and lore each under its own heading with a
 * count. That is the pack's *authoring data* shown as if it were content, and it front-loads
 * the information the engine is supposed to reveal: a reader who can see nine event types
 * and twenty-six locations before pressing a button has been handed the design document
 * instead of the world.
 *
 * So it renders exactly one thing - [PackShowcase] - and that type has no field for a
 * character, a location or an event. The guarantee is structural rather than a review item.
 *
 * ```
 *   artwork, edge to edge
 *   title
 *   UNIVERSE / GENRE / TONE       small, three facts
 *   premise                        the one substantial paragraph
 *   two or three hooks             mood, in the reader's terms
 *   [ ENTER WORLD ]
 * ```
 *
 * ## The hero treatment is the pack's choice
 *
 * `HeroTreatment` lets an author choose between full-bleed artwork, a gradient wash and a
 * flat field, because a superhero pack and a courtly one should not open identically. The
 * app draws what the pack declared rather than imposing one treatment on every world.
 */
@Composable
fun ShowcaseScreen(
    showcase: PackShowcase?,
    resumable: SessionCard?,
    loading: Boolean,
    heroHeight: Dp,
    onBack: () -> Unit,
    onEnterWorld: () -> Unit,
    onContinueStory: (String) -> Unit,
    /**
     * Called when About is opened.
     *
     * The sheet's own dismissal is local to this screen - it is a layer over the poster, not
     * a navigation event, and routing it through the caller would put a back-stack entry on
     * a thing that is meant to be glanced past.
     */
    onAboutThisWorld: () -> Unit = {},
) {
    var aboutOpen by remember { mutableStateOf(false) }
    val openAbout = {
        aboutOpen = true
        onAboutThisWorld()
    }

    if (showcase == null) {
        ShowcaseMissing(loading = loading, onBack = onBack)
        return
    }

    val atmosphere = CharalyAtmosphere.of(showcase.theme)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Charaly.space.section),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ---- the hero, full bleed -------------------------------------------
        item(key = "hero") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
            ) {
                when (showcase.heroTreatment) {
                    // Full-bleed artwork for a pack whose identity is pictorial.
                    HeroTreatment.FULL_BLEED,
                    HeroTreatment.WASH,
                    -> PackArtworkHero(
                        artwork = showcase.artwork,
                        atmosphere = atmosphere,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Flat: the pack is typographic, so the type does the work and the
                    // artwork is withheld rather than forced into a band.
                    HeroTreatment.FLAT -> Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                atmosphere.gradient?.let { stops ->
                                    Brush.verticalGradient(listOf(stops.first, stops.second))
                                } ?: Brush.verticalGradient(atmosphere.wash()),
                            ),
                    )
                }

                // The title sits on the artwork, not below it. This is the difference
                // between a poster and a form with a header image.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Charaly.space.lg),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (showcase.universe.isNotBlank()) {
                        Text(
                            text = showcase.universe.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = atmosphere.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = showcase.title,
                        style = MaterialTheme.typography.displayMedium,
                        color = Charaly.ink.primary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .semantics { heading() },
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Charaly.space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CharalyIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = Loc.t("a11y.back_to_worlds"),
                        onClick = onBack,
                        container = Charaly.surface.glassStrong,
                    )
                }
            }
        }

        // ---- the three facts ------------------------------------------------
        item(key = "metadata") {
            Row(
                modifier = Modifier.padding(
                    start = Charaly.space.gutter,
                    end = Charaly.space.gutter,
                    top = Charaly.space.lg,
                ),
                horizontalArrangement = Arrangement.spacedBy(Charaly.space.xs),
            ) {
                showcase.genres.take(2).forEach { genre -> CharalyPill(label = genre) }
                listOfNotNull(
                    showcase.tone.takeIf { it.isNotBlank() },
                    showcase.atmosphere.takeIf { it.isNotBlank() && it != showcase.tone },
                ).take(1).forEach { CharalyPill(label = it) }
            }
        }

        // ---- the premise ----------------------------------------------------
        if (showcase.premise.isNotBlank()) {
            item(key = "premise") {
                Text(
                    text = showcase.premise,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary,
                    modifier = Modifier.padding(
                        start = Charaly.space.gutter,
                        end = Charaly.space.gutter,
                        top = Charaly.space.md,
                    ),
                )
            }
        }

        // ---- the hooks -----------------------------------------------------
        //
        // Two or three lines, no labels, no bullets. They are mood, not a feature list.
        if (showcase.hasHooks) {
            item(key = "hooks") {
                Column(
                    modifier = Modifier.padding(
                        start = Charaly.space.gutter,
                        end = Charaly.space.gutter,
                        top = Charaly.space.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                ) {
                    showcase.hooks.forEach { hook ->
                        Text(
                            text = hook,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Charaly.ink.primary,
                        )
                    }
                }
            }
        }

        // ---- the invitation -------------------------------------------------
        item(key = "action") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        start = Charaly.space.gutter,
                        end = Charaly.space.gutter,
                        top = Charaly.space.xl,
                    ),
            ) {
                if (showcase.canContinue && resumable != null) {
                    // A returning player is not being invited; they are being offered a
                    // resume. Two actions, the primary one first.
                    CharalyAction(
                        label = showcase.continueLabel,
                        onClick = { onContinueStory(resumable.id) },
                        icon = Icons.Filled.PlayArrow,
                        fillWidth = true,
                    )
                    Spacer(Modifier.height(Charaly.space.xs))
                    CharalyQuietAction(
                        label = Loc.t("showcase.start_new"),
                        onClick = onEnterWorld,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = showcase.invitation,
                        style = MaterialTheme.typography.titleLarge,
                        color = atmosphere.accent,
                        modifier = Modifier.padding(bottom = Charaly.space.sm),
                    )
                    CharalyAction(
                        label = showcase.primaryActionLabel,
                        onClick = onEnterWorld,
                        icon = Icons.Filled.PlayArrow,
                        fillWidth = true,
                    )
                }

                // "About this world" is the only secondary affordance, and it is text
                // rather than a second button: the tone, the notice and the content notes
                // are context, not an action.
                Spacer(Modifier.height(Charaly.space.sm))
                CharalyQuietAction(
                    label = Loc.t("showcase.about"),
                    onClick = openAbout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // The About sheet, over the showcase rather than as a separate destination.
        //
        // A route would put "what is this world" on the same level as the world itself, and
        // a reader who taps it wants a layer - glance, read, dismiss, still looking at the
        // poster - not a screen that has to be popped back out of.
        if (aboutOpen && showcase != null) {
            item(key = "about") {
                AboutWorldSheet(
                    showcase = showcase,
                    onClose = { aboutOpen = false },
                )
            }
        }

        // Rights and attribution are shown, never buried. A pack built on a third-party
        // setting has to say so on its own front page.
        if (showcase.fandomNotice.isNotBlank() || showcase.contentNotes.isNotEmpty()) {
            item(key = "notice") {
                Column(
                    modifier = Modifier.padding(
                        start = Charaly.space.gutter,
                        end = Charaly.space.gutter,
                        top = Charaly.space.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Charaly.space.xs),
                ) {
                    if (showcase.fandomNotice.isNotBlank()) {
                        Text(
                            text = showcase.fandomNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
                    if (showcase.contentNotes.isNotEmpty()) {
                        Text(
                            text = showcase.contentNotes.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Charaly.ink.muted,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The cinematic transition into a world.
 *
 * ## 620ms, once
 *
 * Artwork stays visible and slowly darkens; the world's name fades; the companion appears;
 * the scene line appears. Then the stage takes over.
 *
 * The sequence is a *sequence*, not a cross-fade, because the product's claim is that you
 * are crossing a threshold. Four overlapping fades would read as a page transition; a
 * staged one reads as arriving.
 *
 * It is the only budget above half a second in the app. Everything else is 200-320ms,
 * because an animation that is expensive becomes an animation the user waits through.
 *
 * The whole thing collapses to a single frame under [dev.charaly.runtime.presentation.MotionPolicy.NONE]:
 * the content still appears and the state still changes, it simply does not travel.
 */
@Composable
fun EnterWorldTransition(
    worldName: String,
    sceneLine: String,
    companionName: String,
    companionAccent: Color,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    // Each element is a phase of the same 0..1 progress rather than its own animation, so
    // the whole sequence is one thing that can be interrupted and reversed.
    val titleAlpha = (progress / 0.35f).coerceIn(0f, 1f)
    val darkAlpha = (progress / 0.55f).coerceIn(0f, 1f)
    val companionAlpha = ((progress - 0.4f) / 0.35f).coerceIn(0f, 1f)
    val sceneAlpha = ((progress - 0.6f) / 0.4f).coerceIn(0f, 1f)

    Box(modifier = modifier.background(Charaly.surface.void)) {
        // The darkening: artwork is still visible at 20% and gone by 60%, so the reader
        // watches the world close over rather than seeing a cut.
        Box(
            Modifier
                .fillMaxSize()
                .background(Charaly.surface.void.copy(alpha = darkAlpha * 0.92f)),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Charaly.space.gutter),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = worldName,
                style = MaterialTheme.typography.displayMedium,
                color = Charaly.ink.primary.copy(alpha = 1f - titleAlpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (companionName.isNotBlank()) {
                Spacer(Modifier.height(Charaly.space.lg))
                AnimatedVisibility(
                    visible = companionAlpha > 0.01f,
                    enter = fadeIn(tween(motionDuration(Charaly.timing.standard))),
                    exit = fadeOut(),
                ) {
                    CharalyPill(
                        label = companionName,
                        accent = companionAccent,
                    )
                }
            }

            if (sceneLine.isNotBlank()) {
                Spacer(Modifier.height(Charaly.space.sm))
                Text(
                    text = sceneLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Charaly.ink.secondary.copy(alpha = sceneAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * "About this world", as a sheet.
 *
 * The tone, the atmosphere, the notice and the content notes - the four things a reader is
 * entitled to before stepping in - and nothing else. The engine's own view is one Settings
 * toggle away and is gated behind developer mode.
 */
@Composable
fun AboutWorldSheet(
    showcase: PackShowcase,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = Charaly.space.gutter,
                end = Charaly.space.gutter,
                top = Charaly.space.md,
                bottom = Charaly.space.lg,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = showcase.title,
                style = MaterialTheme.typography.headlineMedium,
                color = Charaly.ink.primary,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            CharalyIconButton(
                icon = Icons.Filled.Close,
                contentDescription = Loc.t("action.dismiss"),
                onClick = onClose,
            )
        }

        Spacer(Modifier.height(Charaly.space.md))

        if (showcase.tone.isNotBlank()) {
            AboutLine(label = Loc.t("showcase.tone"), value = showcase.tone)
        }
        if (showcase.atmosphere.isNotBlank()) {
            AboutLine(label = Loc.t("showcase.atmosphere"), value = showcase.atmosphere)
        }
        if (showcase.universe.isNotBlank()) {
            AboutLine(label = Loc.t("showcase.world"), value = showcase.universe)
        }
        if (showcase.genres.isNotEmpty()) {
            AboutLine(label = Loc.t("showcase.genre"), value = showcase.genres.joinToString(" · "))
        }
        if (showcase.fandomNotice.isNotBlank()) {
            AboutLine(label = Loc.t("showcase.rights"), value = showcase.fandomNotice)
        }
        if (showcase.contentNotes.isNotEmpty()) {
            AboutLine(label = Loc.t("showcase.content"), value = showcase.contentNotes.joinToString(" · "))
        }
    }
}

@Composable
private fun AboutLine(label: String, value: String) {
    Column(Modifier.padding(bottom = Charaly.space.md)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Charaly.ink.muted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = Charaly.ink.secondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ShowcaseMissing(loading: Boolean, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            CharalySkeleton(
                Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                shape = CharalyShapes.soft,
            )
        } else {
            CharalyEmptyState(
                state = EmptyState(
                    title = Loc.t("showcase.missing_title"),
                    body = Loc.t("showcase.missing_body"),
                    actionLabel = "Back",
                    artSeed = "charaly-empty-showcase",
                ),
                action = { CharalyAction(label = Loc.t("action.back"), onClick = onBack) },
            )
        }
    }
}

/** The hero's height, by treatment. A decision rather than a magic number at a call site. */
internal fun heroHeightFor(treatment: HeroTreatment): Dp = when (treatment) {
    HeroTreatment.FULL_BLEED -> 460.dp
    HeroTreatment.WASH -> 380.dp
    HeroTreatment.FLAT -> 300.dp
}

/** A hero that fills the display when the screen is the whole experience. */
internal val FullBleedHeroHeight: Dp = heroHeightFor(HeroTreatment.FULL_BLEED)