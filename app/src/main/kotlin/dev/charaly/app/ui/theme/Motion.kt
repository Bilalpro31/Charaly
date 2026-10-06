package dev.charaly.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.charaly.app.ui.design.Charaly
import dev.charaly.runtime.presentation.MotionPolicy
import dev.charaly.runtime.presentation.MotionSettings

/**
 * MOTION, as policy.
 *
 * ## Why this is a CompositionLocal rather than a settings read
 *
 * Compose honours Android's "Remove animations" option for its *standard* transition
 * primitives. It does not stop a hand-written `tween(320)`, and Charaly has several - a
 * stage transition, a sheet, an artwork crossfade. So the app has to consult its own
 * policy at every animated site, and for that the policy has to be reachable without a
 * parameter threaded through twenty functions.
 *
 * ## Why a switch that changes nothing is worse than no switch
 *
 * Settings offers "Reduce motion". A switch that saves a preference and leaves every
 * animation at full length is a lie told by omission: the user believes they asked for
 * less movement and the app has agreed while doing nothing.
 *
 * So every duration in the app is asked for through [motionDuration] rather than written as
 * a literal. That is the whole contract, and it is asserted by the motion-policy tests in
 * the runtime module.
 */
val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy.FULL }

/** The resolved policy for this composition. */
val motionPolicy: MotionPolicy
    @Composable get() = LocalMotionPolicy.current

/**
 * The duration an animation should use.
 *
 * Under [MotionPolicy.NONE] this returns 0, which makes an animation complete immediately
 * while still going through the animation machinery - so visibility and state end up in the
 * right place, they simply do not travel. That distinction matters: a "reduce motion"
 * implementation that skips the animation entirely can leave a composable in the wrong
 * state permanently.
 */
@Composable
fun motionDuration(preferredMs: Int): Int = when (val policy = motionPolicy) {
    MotionPolicy.FULL -> preferredMs
    MotionPolicy.REDUCED -> minOf(preferredMs, policy.durationMs)
    MotionPolicy.NONE -> 0
}

/** A tween that honours the motion policy. */
@Composable
fun motionTween(preferredMs: Int): AnimationSpec<Float> =
    tween(durationMillis = motionDuration(preferredMs))

/** The standard transition, from the tokens rather than a literal. */
@Composable
fun standardTween(): AnimationSpec<Float> = motionTween(Charaly.timing.standard)

/** Wraps content with the resolved policy. Called once, at the activity. */
@Composable
fun ProvideMotionPolicy(policy: MotionPolicy, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionPolicy provides policy) {
        content()
    }
}

/**
 * Whether decorative animation may run.
 *
 * True for screen transitions, content reveal and artwork crossfades. False for a shimmer
 * or a drift.
 */
@Composable
fun allowsDecorativeMotion(): Boolean = MotionSettings.allowsDecorativeAnimation(motionPolicy)

/**
 * Whether a progress indicator may animate.
 *
 * Deliberately always true, even under [MotionPolicy.NONE], and the reason is worth stating
 * because it looks like an oversight: a spinner that has stopped spinning does not read as
 * "quiet", it reads as *stuck*. Progress is information rather than decoration, so it is
 * allowed to keep moving. What the policy stops is the motion around it.
 */
@Composable
fun allowsProgressAnimation(): Boolean = MotionSettings.allowsProgressAnimation(motionPolicy)

/**
 * The cinematic duration for entering a world.
 *
 * The only budget above half a second in the app, and it is used exactly once: when a world
 * takes over the screen. A 620ms filter change would be intolerable; the same transition,
 * spent once and deliberately, crossing from *browsing* to *being somewhere* is the reason
 * the product has an entrance at all.
 */
@Composable
fun cinematicDuration(): Int = motionDuration(Charaly.timing.cinematic)