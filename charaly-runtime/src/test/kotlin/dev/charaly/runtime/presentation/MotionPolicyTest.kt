package dev.charaly.runtime.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings screen offers a "Reduce motion" switch.
 *
 * Before this existed, that switch saved a preference that nothing read - the user was
 * told they had asked for less movement and the app carried on regardless. These tests
 * pin the resolution rules so the switch cannot quietly become decorative again.
 */
class MotionPolicyTest {

    @Test
    fun `no preference means full motion`() {
        assertEquals(
            MotionPolicy.FULL,
            MotionPolicy.resolve(systemRemovesAnimations = false, userPrefersReducedMotion = false),
        )
    }

    @Test
    fun `the in-app switch reduces motion`() {
        assertEquals(
            MotionPolicy.REDUCED,
            MotionPolicy.resolve(systemRemovesAnimations = false, userPrefersReducedMotion = true),
        )
    }

    @Test
    fun `the system setting stops animation entirely`() {
        assertEquals(
            MotionPolicy.NONE,
            MotionPolicy.resolve(systemRemovesAnimations = true, userPrefersReducedMotion = false),
        )
    }

    @Test
    fun `the system setting wins over the in-app switch`() {
        // A user who has told the OS to stop animations should not have to tell every
        // app separately, and an app that overrides that is overriding an accessibility
        // setting with a preference.
        assertEquals(
            MotionPolicy.NONE,
            MotionPolicy.resolve(systemRemovesAnimations = true, userPrefersReducedMotion = true),
        )
    }

    @Test
    fun `only NONE disables animation`() {
        assertTrue(MotionPolicy.FULL.isAnimated)
        assertTrue(MotionPolicy.REDUCED.isAnimated)
        assertFalse(MotionPolicy.NONE.isAnimated)
    }

    @Test
    fun `a reduced policy shortens every duration rather than only some`() {
        listOf(100, 200, 220, 500, 1000).forEach { preferred ->
            assertTrue(
                "a $preferred ms animation must not outlive the reduced budget",
                MotionPolicy.REDUCED.durationMs <= preferred,
            )
        }
    }

    @Test
    fun `decorative animation follows the policy`() {
        assertTrue(MotionSettings.allowsDecorativeAnimation(MotionPolicy.FULL))
        assertTrue(MotionSettings.allowsDecorativeAnimation(MotionPolicy.REDUCED))
        assertFalse(MotionSettings.allowsDecorativeAnimation(MotionPolicy.NONE))
    }

    @Test
    fun `a progress indicator keeps working when motion is off`() {
        // A spinner that does not spin reads as "stuck", not as "quiet". Progress is
        // informational rather than decorative, so it is exempt.
        MotionPolicy.entries.forEach { policy ->
            assertTrue(
                "progress must stay available under $policy",
                MotionSettings.allowsProgressAnimation(policy),
            )
        }
    }

    @Test
    fun `full motion stays fast enough not to be felt`() {
        // A story app should keep up with the reader. 220ms is the whole budget.
        assertTrue(MotionPolicy.FULL.durationMs <= 250)
    }

    @Test
    fun `the convenience constructor matches the full resolution`() {
        // `of` is the shortcut used where the system setting is not in play (previews,
        // tests). It must agree with `resolve` when the system allows animation.
        assertEquals(
            MotionPolicy.resolve(systemRemovesAnimations = false, userPrefersReducedMotion = false),
            MotionPolicy.of(userPrefersReducedMotion = false),
        )
        assertEquals(
            MotionPolicy.resolve(systemRemovesAnimations = false, userPrefersReducedMotion = true),
            MotionPolicy.of(userPrefersReducedMotion = true),
        )
        assertEquals(MotionPolicy.FULL, MotionPolicy.of(userPrefersReducedMotion = false))
        assertEquals(MotionPolicy.REDUCED, MotionPolicy.of(userPrefersReducedMotion = true))
    }
}