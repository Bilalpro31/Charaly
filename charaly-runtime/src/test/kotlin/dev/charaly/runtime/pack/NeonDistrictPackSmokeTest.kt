package dev.charaly.runtime.pack

import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryInstanceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for the second demo pack.
 *
 * The point is not coverage of every field: it is that the pack *builds* (which runs
 * the cross-reference validator in PackAuthoring.pack), that the authored counts are the
 * ones the pack claims, and that a real StoryInstance can be created from it. If any
 * of that stops being true, this fails loudly instead of at event-application time.
 */
class NeonDistrictPackSmokeTest {

    @Test
    fun `neon district pack builds and starts a story`() {
        val pack = NeonDistrictPack.pack

        assertEquals(5, pack.characters.size)
        assertEquals(6, pack.locations.size)
        assertEquals(3, pack.scenarios.size)
        assertEquals(9, pack.events.size)
        assertEquals(7, pack.initialStoryThreads.size)
        assertEquals(7, pack.initialKnowledge.facts.size)
        assertTrue(pack.factions.isNotEmpty())
        assertTrue(pack.lore.isNotEmpty())
        assertTrue(pack.personas.isNotEmpty())
        assertTrue(pack.initialRelationships.size >= 8)

        val instance = StoryInstanceFactory.create(pack, StoryInstanceId("neon-smoke"))

        assertEquals(5, instance.worldState.characters.size)
        assertEquals(6, instance.worldState.locations.size)
    }
}