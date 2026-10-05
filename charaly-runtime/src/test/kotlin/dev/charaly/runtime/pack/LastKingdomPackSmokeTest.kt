package dev.charaly.runtime.pack

import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryInstanceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for [LastKingdomPack]: the pack must build (PackAuthoring validates
 * every cross reference at construction time) and must produce a runnable story
 * instance with the whole cast in it.
 */
class LastKingdomPackSmokeTest {

    @Test
    fun `last kingdom pack builds and starts`() {
        val p = LastKingdomPack.pack

        assertEquals(5, p.characters.size)
        assertEquals(6, p.locations.size)
        assertEquals(3, p.scenarios.size)

        val instance = StoryInstanceFactory.create(p, StoryInstanceId("kingdom-smoke"))

        assertEquals(5, instance.characters.size)
        assertEquals(6, instance.locations.size)
        assertTrue(p.events.isNotEmpty())
        assertTrue(p.initialKnowledge.facts.isNotEmpty())
    }
}