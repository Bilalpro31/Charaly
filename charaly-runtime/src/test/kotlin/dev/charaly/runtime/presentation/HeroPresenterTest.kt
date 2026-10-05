package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.pack.DemoStoryPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pack screen's stated problem was "text overlap, giant text blocks, metadata walls".
 *
 * Those are measure problems, so they are decided and tested here rather than hoped for
 * inside a composable. Every assertion below is about what the user ends up seeing.
 */
class HeroPresenterTest {

    @Test
    fun `every demo pack produces a usable hero`() {
        DemoStoryPacks.all.forEach { pack ->
            val hero = HeroPresenter.forPack(pack)
            assertTrue("${pack.id.value}: hero needs a title", hero.title.isNotBlank())
            assertTrue("${pack.id.value}: hero needs an eyebrow", hero.eyebrow.isNotBlank())
            assertTrue(
                "${pack.id.value}: tagline must fit the hero budget, got ${hero.tagline.length} chars",
                hero.tagline.length <= HeroPresenter.MAX_TAGLINE_CHARS + 1,
            )
            assertTrue(
                "${pack.id.value}: chips must be capped",
                hero.chips.size <= HeroPresenter.MAX_CHIPS,
            )
        }
    }

    @Test
    fun `a long tagline is trimmed rather than allowed to overflow`() {
        val hero = HeroPresenter.forContent(
            eyebrow = "test",
            title = "A World",
            tagline = "word ".repeat(200),
        )
        assertTrue(
            "an over-long tagline must be cut, got ${hero.tagline.length} chars",
            hero.tagline.length <= HeroPresenter.MAX_TAGLINE_CHARS + 1,
        )
        assertTrue("the cut must be visible", hero.tagline.endsWith("…"))
        assertTrue(hero.isTruncated)
    }

    @Test
    fun `a trimmed tagline never ends mid-word`() {
        val tagline = "Paris is never as quiet as it looks at night when the akuma are loose and the city holds its breath"
        val trimmed = HeroPresenter.trimToLines(tagline, 40)
        val body = trimmed.removeSuffix("…")
        assertTrue(
            "'$body' is not a whole number of words from '$tagline'",
            tagline.startsWith(body) && !tagline[body.length].isLetter(),
        )
    }

    @Test
    fun `a short tagline is left exactly as written`() {
        val tagline = "Paris is never as quiet as it looks."
        val hero = HeroPresenter.forContent("test", "Miraculous", tagline)
        assertEquals(tagline, hero.tagline)
        assertFalse(hero.isTruncated)
    }

    @Test
    fun `genre chips are capped so the hero is not a metadata wall`() {
        val hero = HeroPresenter.forContent(
            eyebrow = "test",
            title = "A World",
            tagline = "Short.",
            chips = listOf("superhero", "school", "paris", "akuma", "teen", "comedy", "drama"),
        )
        assertEquals(HeroPresenter.MAX_CHIPS, hero.chips.size)
    }

    @Test
    fun `duplicate and blank chips are removed`() {
        val hero = HeroPresenter.forContent(
            eyebrow = "test",
            title = "A World",
            tagline = "Short.",
            chips = listOf("hero", "  ", "hero", " drama ", "comedy"),
        )
        assertEquals(listOf("hero", "drama", "comedy"), hero.chips)
    }

    @Test
    fun `a blank title still yields something readable`() {
        val hero = HeroPresenter.forContent("test", "   ", "A tagline.")
        assertTrue(hero.title.isNotBlank())
        assertFalse("an unnamed world must not show a blank title", hero.title.isEmpty())
    }

    @Test
    fun `more text means a taller hero, never a squeezed one`() {
        val compact = HeroPresenter.forContent("test", "Short Title", "")
        val regular = HeroPresenter.forContent("test", "Short Title", "A reasonable tagline.")
        val tall = HeroPresenter.forContent(
            eyebrow = "test",
            title = "A Very Long Title That Will Not Fit On One Line At All",
            tagline = "word ".repeat(200),
        )
        assertTrue(compact.heightDp <= regular.heightDp)
        assertTrue(regular.heightDp <= tall.heightDp)
        assertEquals(HeroPresenter.HERO_HEIGHT_COMPACT, compact.heightDp)
    }

    @Test
    fun `a pack with no tagline and no description still has a hero`() {
        val bare = DemoStoryPacks.all.first().copy(
            id = StoryPackId("pack-bare"),
            description = "",
            identity = DemoStoryPacks.all.first().identity.copy(tagline = ""),
        )
        val hero = HeroPresenter.forPack(bare)
        assertEquals(bare.title, hero.title)
        assertEquals("", hero.tagline)
        assertTrue(hero.eyebrow.isNotBlank())
        assertTrue(hero.hasOverlayText)
    }

    @Test
    fun `hero height always leaves room for the chrome above the text`() {
        // The back button and the eyebrow share the hero, so even the shortest hero must
        // clear a usable minimum.
        val shortest = HeroPresenter.forContent("test", "T", "")
        assertTrue(
            "hero must be tall enough for its own back button",
            shortest.heightDp >= 200,
        )
        assertNotNull(shortest.tagline)
    }
}