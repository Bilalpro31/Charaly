package dev.charaly.runtime.design

import dev.charaly.runtime.domain.CharalyAccent
import dev.charaly.runtime.domain.CharalySurface
import dev.charaly.runtime.domain.HeroTreatment
import dev.charaly.runtime.domain.PackColor
import dev.charaly.runtime.domain.PackTheme
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.presentation.PackShowcaseBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the two design decisions that are easy to lose and expensive to rediscover:
 * the background is real black, and purple is not the product's colour.
 *
 * Both are assertions rather than conventions because both were true once and were
 * quietly abandoned. `CharalyColors.BrandViolet` was a Material seed colour adopted
 * because it was there, and the background drifted to a navy-black that made every
 * piece of artwork look slightly wrong. Nothing about that is detectable by reading the
 * code - it is only visible as "this looks like a template", which is exactly the
 * judgement no compiler makes.
 */
class PackIdentityDesignTest {

    /**
     * The background is #000000.
     *
     * Checks the value itself and not a usage: a design token that is correct but unused
     * fails the user's eye just as badly as one that is wrong.
     */
    @Test
    fun `the page background is pure black`() {
        assertEquals("#000000", CharalySurface.VOID)
        assertEquals(0xFF000000L, PackColor.parse(CharalySurface.VOID))
    }

    @Test
    fun `elevation steps are near-black rather than tinted`() {
        // Every step above black must stay dark enough to read as "the same black,
        // slightly closer". A tinted step reintroduces the colour cast that pure black
        // exists to avoid.
        for (step in listOf(CharalySurface.BASE, CharalySurface.RAISED, CharalySurface.ELEVATED, CharalySurface.OVERLAY)) {
            val argb = PackColor.parse(step)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val maxChannel = maxOf(r, g, b)
            assertTrue("$step should be near-black but has a channel at $maxChannel", maxChannel <= 0x20)
        }
    }

    @Test
    fun `ink is white, not violet`() {
        val ink = PackColor.parse(CharalySurface.INK_PRIMARY)
        val r = (ink shr 16) and 0xFF
        val g = (ink shr 8) and 0xFF
        val b = ink and 0xFF
        // A neutral has near-equal channels. Violet has a large blue-green gap.
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        assertTrue("ink should be neutral but channel spread is $spread", spread <= 0x12)
    }

    /**
     * The default theme is not purple.
     *
     * Purple was the Material 3 seed colour, and it became the product's identity by
     * default rather than by decision. If this test fails, someone has re-seeded a theme
     * and the app has started wearing a generic colour again.
     */
    @Test
    fun `the default accent is neutral, not purple`() {
        val default = PackTheme()
        val primary = PackColor.parse(default.primaryHex)
        val r = (primary shr 16) and 0xFF
        val g = (primary shr 8) and 0xFF
        val b = primary and 0xFF

        // Violet is blue and red high, green low. This asserts green is not the outlier.
        val greenGap = abs((r + b) / 2 - g).toLong()
        assertTrue(
            "default primary ${default.primaryHex} is chromatic (green gap $greenGap)",
            greenGap <= 0x20,
        )
    }

    @Test
    fun `no shipped pack uses a purple identity`() {
        // The pack identities are named and lookup-able. Purple must not be one of them.
        for ((id, theme) in CharalyAccent.all) {
            assertFalse(
                "pack identity '$id' looks purple: ${theme.primaryHex}",
                looksViolet(theme.primaryHex),
            )
        }
    }

    /**
     * Each shipped pack is visually distinct from the others.
     *
     * A shared identity would make the library look like one product with three
     * titles, which is the failure the accent system exists to prevent.
     */
    @Test
    fun `shipped packs have distinct identities`() {
        val mirrors = DemoStoryPacks.all
        val primaries = mirrors.map { PackColor.parse(it.identity.theme.primaryHex) }
        assertEquals(
            "two packs share a primary accent: " +
                mirrors.map { it.title to it.identity.theme.primaryHex },
            primaries.size,
            primaries.distinct().size,
        )
    }

    /**
     * The Miraculous pack is red, black and white.
     *
     * Asserted literally because the brief calls for it specifically, and because
     * "the accent is a hue that is not purple" is too weak: a blue accent on a Paris
     * superhero pack would pass that and still be wrong.
     */
    @Test
    fun `miraculous is red on black`() {
        val theme = MiraculousPack.pack.identity.theme
        val primary = PackColor.parse(theme.primaryHex)
        val r = (primary shr 16) and 0xFF
        val g = (primary shr 8) and 0xFF
        val b = primary and 0xFF

        assertTrue("red channel should dominate, was r=$r g=$g b=$b", r > g && r > b)

        // The named identity supplies its own surface, which is very slightly warm-black
        // rather than Charaly's neutral RAISED - a deliberate, tiny tint so the pack's
        // stage is not the same black as the page behind it. Asserted as "near-black"
        // rather than "equals RAISED", because the exact value is an authoring choice.
        val surface = PackColor.parse(theme.surfaceHex)
        assertTrue(
            "miraculous surface ${theme.surfaceHex} should be near-black",
            ((surface shr 16) and 0xFF) <= 0x20 &&
                ((surface shr 8) and 0xFF) <= 0x20 &&
                (surface and 0xFF) <= 0x20,
        )

        // Its second colour is the *dark* end of its own gradient, which is what makes
        // the hero read as red-to-black rather than red-to-something.
        assertEquals(CharalyAccent.MIRACULOUS.secondaryHex, theme.secondaryHex)
        assertEquals(HeroTreatment.FULL_BLEED.name, theme.heroTreatment)
    }

    /**
     * Gradients are declared, not sprinkled.
     *
     * The two media-pack identities declare one because a hero wants it; the neutral
     * identity does not, because a gradient behind every card is exactly the look this
     * pass exists to remove. Both answers are correct, which is the point.
     */
    @Test
    fun `gradient presence is a decision not a default`() {
        assertTrue(CharalyAccent.MIRACULOUS.hasGradient)
        assertTrue(CharalyAccent.NEON_DISTRICT.hasGradient)
        assertFalse(CharalyAccent.NEUTRAL.hasGradient)
    }

    @Test
    fun `a named accent identity resolves and overrides only the treatment`() {
        val resolved = CharalyAccent.byId("miraculous")
        assertEquals(CharalyAccent.MIRACULOUS.primaryHex, resolved?.primaryHex)

        // An unknown id must not silently become the neutral identity, because that
        // would make a typo look like a deliberate choice.
        assertEquals(null, CharalyAccent.byId("cyberpunk-neon-v2"))
        assertEquals(CharalyAccent.NEON_DISTRICT.primaryHex, CharalyAccent.byId("cyberpunk")?.primaryHex)
    }

    @Test
    fun `hero treatment parses case-insensitively and falls back safely`() {
        assertEquals(HeroTreatment.FULL_BLEED, HeroTreatment.parse("full_bleed"))
        assertEquals(HeroTreatment.FLAT, HeroTreatment.parse("FLAT"))
        assertEquals(HeroTreatment.WASH, HeroTreatment.parse("something-else"))
        assertEquals(HeroTreatment.WASH, HeroTreatment.parse(""))
    }

    // ------------------------------------------------------------------
    // The showcase projection
    // ------------------------------------------------------------------

    /**
     * A pack detail screen shows a premise and some hooks, not its contents.
     *
     * This is the projection's whole contract, so it is asserted on the shipped packs
     * rather than on a fixture: if a future pack arrives with no hooks, this fails and
     * the author is asked for them.
     */
    @Test
    fun `every shipped pack presents a premise, hooks and an invitation`() {
        for (pack in DemoStoryPacks.all) {
            val showcase = PackShowcaseBuilder.build(pack)
            assertTrue("${pack.title} has no premise", showcase.premise.isNotBlank())
            assertTrue("${pack.title} has no hooks", showcase.hooks.isNotEmpty())
            assertTrue("${pack.title} has no invitation", showcase.invitation.isNotBlank())
            assertTrue(
                "${pack.title} has a blank primary action label",
                showcase.primaryActionLabel.isNotBlank(),
            )
        }
    }

    @Test
    fun `hooks are capped at three and never blank`() {
        val identity = MiraculousPack.pack.identity.copy(
            hooks = listOf("one", "", "two", "one", "three", "four", "five"),
        )
        val showcase = PackShowcaseBuilder.build(
            identity = identity,
            packId = "test",
            title = "Test",
        )
        // Blank and duplicate lines are dropped *before* the cap is applied, so a pack
        // that lists seven hooks still gets three good ones rather than three of which
        // one is a duplicate.
        assertEquals(listOf("one", "two", "three"), showcase.hooks)
    }

    @Test
    fun `a pack without hooks still gets an invitation`() {
        val identity = MiraculousPack.pack.identity.copy(
            hooks = emptyList(),
            enterInvitation = "",
            premise = "",
            tagline = "Paris is never as quiet as it looks.",
        )
        val showcase = PackShowcaseBuilder.build(identity, "test", "Test")
        assertTrue(showcase.primaryActionLabel.isNotBlank())
        // Premise falls back to the tagline rather than rendering an empty paragraph.
        assertEquals("Paris is never as quiet as it looks.", showcase.premise)
        assertFalse(showcase.hasHooks)
    }

    /**
     * The showcase carries no engine vocabulary.
     *
     * The projection is *types*, not string matching, so ids cannot leak by accident.
     * This test then checks the strings that do exist, because a presenter that
     * interpolated a location name into a sentence could still put a raw id in it.
     */
    @Test
    fun `the showcase exposes no ids or engine terms`() {
        val showcase = PackShowcaseBuilder.build(MiraculousPack.pack)
        val text = buildString {
            append(showcase.premise).append(' ')
            showcase.hooks.forEach { append(it).append(' ') }
            append(showcase.invitation).append(' ')
            append(showcase.tone).append(' ')
            append(showcase.atmosphere)
        }
        for (forbidden in listOf(
            "characterId", "locationId", "EventId", "MemoryTier",
            "EPISODIC", "CANON", "PROTAGONIST", "charaly:action",
        )) {
            assertFalse("showcase text contains '$forbidden': $text", text.contains(forbidden))
        }
    }

    private fun looksViolet(hex: String): Boolean {
        val argb = PackColor.parse(hex)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // Violet: green clearly the lowest channel and the gap to blue is large.
        return g < 0x60 && (b - g) > 0x40
    }

    private fun abs(v: Long) = if (v < 0) -v else v
}
