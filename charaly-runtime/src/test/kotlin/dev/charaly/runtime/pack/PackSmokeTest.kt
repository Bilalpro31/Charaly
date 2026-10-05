package dev.charaly.runtime.pack

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryInstanceFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural smoke test for the demo pack.
 *
 * The *count* here is deliberately a floor rather than an exact number. The pack used
 * to assert exactly 7 characters, which made "Paris gained a shopkeeper and a
 * caretaker" look like a regression. The brief for this pack is that the world is
 * populated; what matters is that the core seven are present and that there are
 * meaningfully more than seven people in it.
 */
class PackSmokeTest {

    @Test
    fun `miraculous pack builds and starts`() {
        val pack = MiraculousPack.pack

        assertTrue("the pack needs at least one opening", pack.scenarios.isNotEmpty())
        assertTrue(
            "the core seven must all be present",
            listOf("marinette", "adrien", "alya", "nino", "gabriel", "ladybug", "catnoir")
                .all { id -> pack.character(CharacterId(id)) != null },
        )
        assertTrue(
            "Paris must be populated, not just the core cast (was ${pack.characters.size})",
            pack.characters.size >= MiraculousPack.MIN_NPCS + 7,
        )

        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("t"))

        // Every pack character gets runtime state, including the NPCs: they are world
        // entities, not decorations in a character list.
        assertTrue(
            "every character must exist in the running world",
            pack.characters.all { instance.characters[it.id] != null },
        )
        assertTrue(
            "at least the core cast must be placed somewhere",
            pack.characters.count { instance.characters[it.id]?.locationId != null } >= 7,
        )
        assertTrue(
            "the pack must contain meaningful NPCs",
            pack.characters.count { it.storyRole == CharacterRole.NPC } >= MiraculousPack.MIN_NPCS,
        )
    }
}