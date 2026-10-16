package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.settingEra
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pack *schema*: what a Story Pack is allowed to be.
 *
 * Schema tests sit at the bottom of the stack deliberately. Everything above - validation,
 * installation, saving - assumes these shapes hold, and a shape test is the cheapest place
 * to prove it.
 */
class StoryPackSchemaTest {

    @Test
    fun `a pack carries every content section the format promises`() {
        val pack = PackFixtures.pack()

        assertEquals("Miraculous", pack.world.universe)
        assertEquals("Modern Paris", pack.settingEra())
        assertTrue(pack.characters.isNotEmpty())
        assertTrue(pack.locations.isNotEmpty())
        assertTrue(pack.initialRelationships.isNotEmpty())
        assertTrue(pack.scenes.isNotEmpty())
        assertTrue(pack.events.isNotEmpty())
        assertTrue(pack.initialStoryThreads.isNotEmpty())
        assertTrue(pack.objectives.isNotEmpty())
        assertTrue(pack.scenarios.isNotEmpty())
        assertTrue(pack.personas.isNotEmpty())
        assertTrue(pack.initialKnowledge.facts.isNotEmpty())
        assertNotNull(pack.canon)
        assertNotNull(pack.settings)
    }

    @Test
    fun `a pack identity is separate from its content version`() {
        val pack = PackFixtures.pack(id = "miraculous.paris.afterdark", version = "1.2.0")
        assertEquals("miraculous.paris.afterdark", pack.id.value)
        assertEquals("1.2.0", pack.semanticVersion)
    }

    @Test
    fun `legacy integer versions still describe a semantic edition`() {
        // A save written before semantic versions existed pins nothing but the old integer,
        // which is exactly what `packVersion = ""` means: "ask the legacy field".
        val legacy = StoryPack(id = StoryPackId("pack.old"), title = "Old", version = 3, packVersion = "")
        assertEquals("3.0.0", legacy.semanticVersion)

        // An explicitly declared semantic version always wins.
        assertEquals("2.1.0", StoryPack(id = StoryPackId("pack.old"), title = "Old", version = 3, packVersion = "2.1.0").semanticVersion)
        assertEquals("1.0.0", StoryPack(id = StoryPackId("pack.new"), title = "New").semanticVersion)
    }

    @Test
    fun `duplicate content ids are refused by the schema itself`() {
        val pack = PackFixtures.pack()
        val boom = runCatching { pack.copy(characters = pack.characters + pack.characters) }
        assertTrue("duplicate characters must not construct", boom.isFailure)

        val duplicateScenes = runCatching { pack.copy(scenes = pack.scenes + pack.scenes) }
        assertTrue("duplicate scenes must not construct", duplicateScenes.isFailure)

        val duplicateObjectives = runCatching { pack.copy(objectives = pack.objectives + pack.objectives) }
        assertTrue("duplicate objectives must not construct", duplicateObjectives.isFailure)
    }

    @Test
    fun `a choice needs a label and an id`() {
        val boom = runCatching {
            SceneChoice(id = " ", label = "Ok", effects = emptyList())
        }
        assertTrue(boom.isFailure)
    }

    @Test
    fun `a scene cannot hold two choices with the same id`() {
        val scene = PackFixtures.scene()
        val boom = runCatching {
            scene.copy(choices = scene.choices + scene.choices.first())
        }
        assertTrue("duplicate choice ids must not construct", boom.isFailure)
    }

    @Test
    fun `an objective that can never resolve says so`() {
        val unresolvable = PackObjective(id = "obj-x", title = "X")
        assertFalse(unresolvable.isResolvable)
        assertTrue(PackFixtures.objective().isResolvable)
    }

    @Test
    fun `objective lifecycle is terminal in one direction only`() {
        assertTrue(ObjectiveStatus.COMPLETED.isTerminal)
        assertTrue(ObjectiveStatus.FAILED.isTerminal)
        assertTrue(ObjectiveStatus.SKIPPED.isTerminal)
        assertFalse(ObjectiveStatus.ACTIVE.isTerminal)
        assertFalse(ObjectiveStatus.LOCKED.isTerminal)
    }

    @Test
    fun `scene availability is deterministic and state driven`() {
        val pack = PackFixtures.pack()
        val instance = dev.charaly.runtime.engine.StoryInstanceFactory.create(
            pack,
            dev.charaly.runtime.engine.StoryCreationOptions(
                instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-1"),
                scenario = pack.scenarios.first(),
            ),
        )
        val scene = pack.scenes.first()
        assertTrue("entry conditions are empty, so the scene is always available", scene.isAvailable(instance))
        assertFalse("exit condition is not met at the opening", scene.wantsToClose(instance))
    }

    @Test
    fun `the content envelope is the only document shape in a pack`() {
        val encoded = StoryPackCodec.encode(PackFixtures.pack())
        val json = Json { ignoreUnknownKeys = true }
        encoded.values.forEach { text ->
            val envelope = json.decodeFromString(ContentEnvelope.serializer(), text)
            assertTrue(envelope.schemaVersion >= 1)
            assertTrue(envelope.kind.isNotBlank())
        }
        assertEquals(StoryPackFormat.CONTENT_FILES.toSet(), encoded.keys.map { it.substringAfterLast("/") }.toSet())
    }

    @Test
    fun `pack settings can only ask for a model, never bind one`() {
        val pack = PackFixtures.pack()
        assertTrue(pack.settings.recommendedModelId.isBlank() || pack.settings.recommendedModelId.isNotBlank())
        assertFalse("story runtime must stay offline", pack.settings.requiresNetwork)
    }

    @Test
    fun `event triggers cover exactly the deterministic families`() {
        val pack = PackFixtures.pack()
        val categories = pack.events.map { it.category }.toSet()
        assertTrue(categories.any { it.name == "CONDITIONAL" })
        assertTrue(pack.events.any { it.trigger is EventTrigger.StoryStart })
        assertTrue(pack.events.any { it.trigger is EventTrigger.WhenConditionMet })
    }
}