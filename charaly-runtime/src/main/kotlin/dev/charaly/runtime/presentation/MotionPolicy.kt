package dev.charaly.runtime.presentation

/**
 * How much motion Charaly is allowed to use.
 *
 * ## Why this is explicit
 *
 * The Settings screen offers a "Reduce motion" switch. A switch that saves a preference
 * and changes nothing is worse than no switch: the user believes they have asked for
 * less movement, and the app has told them a lie by omission.
 *
 * So motion is a *policy*, resolved once and consulted everywhere, and every animated
 * affordance in the app routes its duration through it.
 *
 * ## Two independent sources, both honoured
 *
 *  1. **The system setting.** Android's "Remove animations" accessibility option. Compose
 *     honours this for the standard transition primitives, but it does not stop a
 *     hand-written `tween(...)`, so Charaly has to ask too.
 *  2. **The in-app preference.** Set independently of the system, and the reason the
 *     control exists.
 *
 * Either one reduces motion to [NONE]; only both being off allows full animation.
 */
enum class MotionPolicy(
    /** Duration in milliseconds. */
    val durationMs: Int,
) {
    /** Full motion. */
    FULL(220),

    /**
     * A gentle reduction for ordinary transitions.
     *
     * Short enough to read as instant, long enough that a fade does not snap.
     */
    REDUCED(100),

    /**
     * No animation at all.
     *
     * Used when the user asks the system to remove animations, or turns the in-app
     * switch on. Content still appears and still changes; it just does not travel.
     */
    NONE(0),
    ;

    val isAnimated: Boolean get() = this != NONE

    companion object {
        /**
         * Resolves the policy from both sources.
         *
         * @param systemRemovesAnimations Android's "Remove animations" setting
         * @param userPrefersReducedMotion the in-app switch
         *
         * The system setting wins over the in-app one: it is the stronger statement,
         * and a user who has asked the OS to stop animations should not have to ask
         * every app again.
         */
        fun resolve(
            systemRemovesAnimations: Boolean,
            userPrefersReducedMotion: Boolean,
        ): MotionPolicy = when {
            systemRemovesAnimations -> NONE
            userPrefersReducedMotion -> REDUCED
            else -> FULL
        }

        /** Convenience for previews and tests. */
        fun of(userPrefersReducedMotion: Boolean): MotionPolicy =
            resolve(systemRemovesAnimations = false, userPrefersReducedMotion = userPrefersReducedMotion)
    }
}

/**
 * Resolves the motion policy once, and hands it to the tree.
 *
 * Kept in the runtime module so the *decision* is unit tested; the app layer only reads
 * the resulting enum.
 */
object MotionSettings {

    /**
     * Animations that must still run even under [MotionPolicy.NONE].
     *
     * Progress indicators are informational, not decorative: an indeterminate spinner
     * that does not spin communicates "stuck", not "quiet". So under NONE the spinner
     * stays but is told not to animate its own rotation.
     */
    fun allowsProgressAnimation(policy: MotionPolicy): Boolean = true

    /**
     * Whether a decorative animation may run.
     *
     * Everything the brief calls "subtle motion" - screen transitions, card press,
     * story opening, message appearance, sheet transitions - goes through this.
     */
    fun allowsDecorativeAnimation(policy: MotionPolicy): Boolean = policy.isAnimated
}