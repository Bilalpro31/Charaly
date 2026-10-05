package dev.charaly.runtime.director

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.SceneTempo
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scene momentum.
 *
 * The failure this exists to prevent: a small model handed an open-ended scene reliably
 * escalates every turn. Two people talk, an alarm goes off, someone confesses, the roof
 * collapses - not because anything in the world pressed for it, but because nothing in
 * the prompt said an uneventful scene was allowed.
 *
 * So the engine says how fast the scene may move, and the default answer is "quietly".
 */
class SceneTempoTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val director = SceneDirector(definition)
    private val builder = ContextBuilder(definition)

    private val marinette = CharacterId("marinette")
    private val andre = CharacterId("andre")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-tempo"),
            title = "Tempo",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette, andre),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun scene(participants: List<CharacterId> = listOf(andre, marinette), incidents: Int = 0) = Scene(
        id = SceneId("scene-1"),
        locationId = dev.charaly.runtime.domain.LocationId("andre-ice-cream"),
        participants = participants,
        focusCharacterId = participants.first(),
        incidents = incidents,
    )

    // ------------------------------------------------------------------
    // The rule
    // ------------------------------------------------------------------

    @Test
    fun `a scene with nothing live is quiet`() {
        assertEquals(SceneTempo.QUIET, scene().currentTempo(hasLivePressure = false))
    }

    @Test
    fun `pressure with nothing yet is building, not escalating`() {
        // The important case. A model told "escalating" on turn one of an ordinary
        // conversation is exactly the failure being fixed.
        assertEquals(SceneTempo.BUILDING, scene().currentTempo(hasLivePressure = true))
    }

    @Test
    fun `pressure plus two incidents is escalating`() {
        assertEquals(SceneTempo.ESCALATING, scene(incidents = 2).currentTempo(hasLivePressure = true))
    }

    @Test
    fun `incidents without pressure do not escalate anything`() {
        // Two things happened in a scene with nothing unresolved. That is a conversation,
        // not an emergency, and the engine should say so.
        assertEquals(
            SceneTempo.QUIET,
            scene(incidents = 5).currentTempo(hasLivePressure = false),
        )
    }

    @Test
    fun `an incident counter only goes up`() {
        assertEquals(1, scene().withIncident().incidents)
        assertEquals(2, scene().withIncident().withIncident().incidents)
        assertEquals(0, scene().incidents)
    }

    // ------------------------------------------------------------------
    // Every tempo tells the model what to do, and QUIET forbids something
    // ------------------------------------------------------------------

    @Test
    fun `every tempo carries an instruction`() {
        SceneTempo.entries.forEach { tempo ->
            assertTrue("${tempo.name} has no instruction", tempo.instruction.isNotBlank())
        }
    }

    @Test
    fun `quiet explicitly forbids manufacturing an event`() {
        // A "nothing is happening" hint that does not say *what not to do* leaves the
        // model to fill the silence, which is the thing we are preventing.
        val quiet = SceneTempo.QUIET.instruction.lowercase()
        assertTrue(quiet, quiet.contains("do not"))
        assertTrue(quiet, quiet.contains("ordinary"))
    }

    // ------------------------------------------------------------------
    // It reaches the model
    // ------------------------------------------------------------------

    @Test
    fun `the pace instruction is in the prompt`() {
        val story = instance()
        val resolved = director.directScene(story, andre)!!
        val prompt = builder.buildContext(story, resolved, andre).systemPrompt()
        assertTrue(prompt, prompt.contains("PACE"))
        assertTrue(
            "and the tempo's own words must be in it, not just a heading",
            prompt.contains(resolved.tempo.instruction.take(40)),
        )
    }

    @Test
    fun `an ordinary scene tells the model not to escalate`() {
        val story = instance()
        val resolved = director.directScene(story, andre)!!
        // With no thread pressing and nothing having happened, the answer must be quiet.
        if (resolved.tempo == SceneTempo.QUIET) {
            val prompt = builder.buildContext(story, resolved, andre).systemPrompt()
            assertTrue(
                "an uneventful scene must forbid the model from inventing one",
                prompt.contains("Do not introduce an event"),
            )
        }
    }

    @Test
    fun `the director derives tempo rather than being told it`() {
        // The tempo is a function of the world's own state. A caller cannot hand a scene
        // an "exciting" tempo when nothing has happened.
        listOf(andre, marinette).forEach { who ->
            val scene = director.directScene(instance(), who)!!
            val expected = if (scene.activeThreadIds.isEmpty()) SceneTempo.QUIET else SceneTempo.BUILDING
            assertEquals("tempo must be derived, not asserted", expected, scene.tempo)
        }
    }
}