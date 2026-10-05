package dev.charaly.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.charaly.runtime.presentation.MotionPolicy

/**
 * The app's motion policy, available to every composable.
 *
 * ## Why a CompositionLocal rather than a system setting
 *
 * Compose already honours Android's "Remove animations" option for its standard
 * transition primitives. It does *not* stop a hand-written `tween(220)` in a custom
 * `AnimatedContent`, and Charaly has several. So the app has to consult its own policy at
 * every animated site - which only works if that policy is reachable without a parameter
 * threaded through twenty functions.
 *
 * Providing it here is what makes the Settings switch real: the value is resolved once
 * from the user preference *and* the system setting, and every animation asks for its
 * duration through [motionDuration] rather than hard-coding a number.
 */
val LocalMotionPolicy = staticCompositionLocalOf { MotionPolicy.FULL }

/** The resolved policy for this composition. Defaults to full motion. */
val motionPolicy: MotionPolicy
    @Composable get() = LocalMotionPolicy.current

/**
 * The duration an animation should use, honouring the motion policy.
 *
 * Every animated affordance in Charaly routes through this instead of hard-coding a
 * duration. Under [MotionPolicy.NONE] it returns 0, which makes the animation complete
 * immediately while still going through the animation machinery - so state and
 * visibility end up in the right place, they simply do not travel.
 */
@Composable
fun motionDuration(preferredMs: Int): Int =
    when (val policy = motionPolicy) {
        MotionPolicy.FULL -> preferredMs
        MotionPolicy.REDUCED -> minOf(preferredMs, policy.durationMs)
        MotionPolicy.NONE -> 0
    }

/** A tween that honours the motion policy. */
@Composable
fun motionTween(preferredMs: Int): AnimationSpec<Float> =
    tween(durationMillis = motionDuration(preferredMs))

/** Wraps content with the resolved policy. Called once, at the activity. */
@Composable
fun ProvideMotionPolicy(policy: MotionPolicy, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMotionPolicy provides policy) {
        content()
    }
}

/** True when decorative animation is allowed right now. */
@Composable
fun allowsDecorativeMotion(): Boolean = motionPolicy.isAnimated