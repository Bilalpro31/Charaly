package dev.charaly.runtime.domain.knowledge

import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.BeliefFormed
import dev.charaly.runtime.domain.events.CharacterObserved
import dev.charaly.runtime.domain.events.MisconceptionCorrected
import dev.charaly.runtime.domain.events.MisconceptionFormed
import dev.charaly.runtime.domain.events.SuspicionRaised
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Character knowledge boundaries, in the form the brief actually demands.
 *
 * "Author knows it" is not "character knows it". The pack contains the whole truth
 * about Ladybug's identity; nothing in this file lets that reach a prompt unless a
 * validated event put it there. And a character has to be able to be *wrong*, because
 * a character who is either informed or silent is not a character.
 */
class CharacterMindTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val engine = EventEngine(definition)

    private val andre = CharacterId("andre")
    private val marinette = CharacterId("marinette")
    private val adrien = CharacterId("adrien")

    // Two characters the Miraculous pack seeds no mind for, so the tests below can
    // assert on an empty mind without that being an accident of pack content.
    private val sab = CharacterId("sabine")
    private val kim = CharacterId("kim")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-mind"),
            title = "Minds",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette, andre),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun apply(from: dev.charaly.runtime.domain.StoryInstance, payload: dev.charaly.runtime.domain.events.EventPayload) =
        engine.applyImmediately(from, payload)

    private fun applied(from: dev.charaly.runtime.domain.StoryInstance, payload: dev.charaly.runtime.domain.events.EventPayload) =
        (apply(from, payload) as EventApplication.Applied).instance

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    @Test
    fun `a character records what they witnessed`() {
        val story = applied(
            instance(),
            CharacterObserved(sab, "the museum wing was empty at closing", LocationIdOf("museum")),
        )
        val observation = story.knowledge.mind(sab).observations.single()
        assertEquals("the museum wing was empty at closing", observation.description)
        assertEquals(1, story.knowledge.mind(sab).observationsAt(LocationIdOf("museum")).size)
    }

    @Test
    fun `witnessing the same thing twice is one observation`() {
        val first = applied(instance(), CharacterObserved(sab, "a black cat on the school roof"))
        val second = applied(first, CharacterObserved(sab, "a black cat on the school roof"))
        assertEquals("the same sighting is one sighting", 1, second.knowledge.mind(sab).observations.size)
    }

    @Test
    fun `two characters witnessing the same thing each record it`() {
        val first = applied(instance(), CharacterObserved(sab, "Nathalie in the east wing", LocationIdOf("museum")))
        val second = applied(first, CharacterObserved(kim, "Nathalie in the east wing", LocationIdOf("museum")))
        assertEquals(1, second.knowledge.mind(sab).observations.size)
        assertEquals(1, second.knowledge.mind(kim).observations.size)
    }

    @Test
    fun `an observation with nothing in it is refused, not thrown`() {
        // Validation must reject malformed input, never throw on it: an event that
        // crashes the reducer is an event that can wedge the world.
        val result = apply(instance(), CharacterObserved(sab, "   "))
        assertTrue(result is EventApplication.Rejected)
    }

    @Test
    fun `an observation of a place that does not exist is refused`() {
        val result = apply(instance(), CharacterObserved(andre, "something", LocationIdOf("atlantis")))
        assertTrue(result is EventApplication.Rejected)
    }

    @Test
    fun `an observation by a character who does not exist is refused`() {
        val result = apply(instance(), CharacterObserved(CharacterId("nobody"), "something"))
        assertTrue(result is EventApplication.Rejected)
    }

    // ------------------------------------------------------------------
    // Belief
    // ------------------------------------------------------------------

    @Test
    fun `a belief is held with a confidence`() {
        val story = applied(
            instance(),
            BeliefFormed(andre, "the museum wing", "was locked all week", confidence = 60, via = "Nathalie said so"),
        )
        val belief = story.knowledge.mind(andre).beliefAbout("the museum wing")!!
        assertEquals(60, belief.confidence)
        assertEquals("Nathalie said so", belief.via)
        assertFalse("60% is not firm", belief.isFirm)
    }

    @Test
    fun `a firm belief is firm`() {
        val story = applied(instance(), BeliefFormed(andre, "the museum wing", "was robbed", confidence = 90))
        assertTrue(story.knowledge.mind(andre).beliefAbout("the museum wing")!!.isFirm)
    }

    @Test
    fun `a second belief about the same subject replaces the first`() {
        val first = applied(instance(), BeliefFormed(sab, "the thief", "was left-handed", confidence = 30))
        val second = applied(first, BeliefFormed(sab, "the thief", "was left-handed", confidence = 80))
        val beliefs = second.knowledge.mind(sab).beliefs
        assertEquals("one belief per subject, not a pile of them", 1, beliefs.size)
        assertEquals(80, beliefs.single().confidence)
    }

    @Test
    fun `a belief repeated identically is a no-op`() {
        val first = applied(instance(), BeliefFormed(sab, "x", "y", confidence = 50))
        val result = apply(first, BeliefFormed(sab, "x", "y", confidence = 50))
        assertTrue("restating a belief changes nothing", (result as EventApplication.Applied).noOp)
    }

    @Test
    fun `a belief with no claim is refused`() {
        assertTrue(apply(instance(), BeliefFormed(andre, "x", "")) is EventApplication.Rejected)
    }

    @Test
    fun `an out of range confidence is refused`() {
        assertTrue(apply(instance(), BeliefFormed(andre, "x", "y", confidence = 140)) is EventApplication.Rejected)
    }

    // ------------------------------------------------------------------
    // Suspicion
    // ------------------------------------------------------------------

    @Test
    fun `a suspicion is neither believed nor dismissed`() {
        val story = applied(instance(), SuspicionRaised(andre, "the east wing", "was opened from inside", strength = 70))
        val mind = story.knowledge.mind(andre)
        val suspicion = mind.suspicionAbout("the east wing")!!
        assertEquals(70, suspicion.strength)
        assertNull("a suspicion is not a belief", mind.beliefAbout("the east wing"))
    }

    // ------------------------------------------------------------------
    // Misconception - the case the brief calls out by name
    // ------------------------------------------------------------------

    @Test
    fun `a character can be wrong, and the truth is kept with the error`() {
        val story = applied(
            instance(),
            MisconceptionFormed(
                characterId = andre,
                subject = "the new student",
                claim = "is nobody Marinette knows",
                truth = "is Adrien Agreste",
                via = "Nino said so",
            ),
        )
        val error = story.knowledge.mind(andre).misconceptionAbout("the new student")!!
        assertEquals("is nobody Marinette knows", error.claim)
        assertEquals("is Adrien Agreste", error.truth)
        assertTrue(story.knowledge.mistakenAbout("the new student").contains(andre))
    }

    @Test
    fun `a misconception with no truth recorded is refused`() {
        // Without the truth this is an unlabelable falsehood that no later reveal
        // could ever find.
        assertTrue(
            apply(instance(), MisconceptionFormed(andre, "x", "is wrong", truth = "")) is EventApplication.Rejected,
        )
    }

    @Test
    fun `a character cannot be wrong about something true`() {
        assertTrue(
            apply(
                instance(),
                MisconceptionFormed(andre, "the shop", "sells ice cream", truth = "sells ice cream"),
            ) is EventApplication.Rejected,
        )
    }

    @Test
    fun `a correction retires the error and leaves the truth behind`() {
        val wrong = applied(
            instance(),
            MisconceptionFormed(andre, "the new student", "is nobody Marinette knows", truth = "is Adrien Agreste"),
        )
        val corrected = applied(
            wrong,
            MisconceptionCorrected(
                characterId = andre,
                subject = "the new student",
                correctedBelief = "is Adrien Agreste, and he is kind about it",
                via = "he said so himself",
            ),
        )
        val mind = corrected.knowledge.mind(andre)
        assertNull("the wrong belief must be gone", mind.misconceptionAbout("the new student"))
        assertEquals(
            "is Adrien Agreste, and he is kind about it",
            mind.beliefAbout("the new student")!!.claim,
        )
        assertFalse(
            "correcting a character makes them no longer mistaken",
            corrected.knowledge.mistakenAbout("the new student").contains(andre),
        )
    }

    @Test
    fun `a correction resolves the suspicion that produced it`() {
        val suspicious = applied(
            instance(),
            SuspicionRaised(andre, "the new student", "is hiding something", strength = 60),
        )
        val corrected = applied(
            suspicious,
            MisconceptionCorrected(andre, "the new student", correctedBelief = "is Adrien"),
        )
        assertNull(
            "a resolved question must not keep being wondered about",
            corrected.knowledge.mind(andre).suspicionAbout("the new student"),
        )
    }

    @Test
    fun `correcting a belief nobody held is a no-op`() {
        val result = apply(instance(), MisconceptionCorrected(andre, "nothing in particular"))
        assertTrue((result as EventApplication.Applied).noOp)
    }

    @Test
    fun `a character can hold a wrong belief and a true one at once`() {
        // This is the pair that makes a reveal land, and the reason a misconception is
        // not simply "the opposite of a fact".
        val story = applied(instance(), BeliefFormed(andre, "the museum", "was robbed on Tuesday", confidence = 90))
        val both = applied(
            story,
            MisconceptionFormed(andre, "the museum", "was robbed on Saturday", truth = "was robbed on Tuesday"),
        )
        val mind = both.knowledge.mind(andre)
        assertNotNull("the true belief survives", mind.beliefAbout("the museum"))
        assertNotNull("so does the wrong one", mind.misconceptionAbout("the museum"))
    }

    // ------------------------------------------------------------------
    // The boundary: a mind is readable only by its owner
    // ------------------------------------------------------------------

    @Test
    fun `one character's mind is not another's`() {
        val story = applied(
            instance(),
            MisconceptionFormed(
                characterId = andre,
                subject = "Ladybug",
                claim = "is some girl from another school",
                truth = "is Marinette",
                via = "overheard a rumour",
            ),
        )
        assertTrue(story.knowledge.mind(andre).misconceptionAbout("Ladybug") != null)
        assertNull(
            "Marinette must not be able to read Andre's private belief",
            story.knowledge.mind(marinette).misconceptionAbout("Ladybug"),
        )
    }

    @Test
    fun `a character's prompt carries their own mind and nobody else's`() {
        val wrong = applied(
            instance(),
            MisconceptionFormed(
                characterId = andre,
                subject = "Ladybug",
                claim = "is some girl from another school",
                truth = "is Marinette",
                via = "overheard a rumour",
            ),
        )
        val director = SceneDirector(definition)
        val andreScene = director.directScene(wrong, andre)!!
        val marinetteScene = director.directScene(wrong, marinette)!!

        val andrePrompt = ContextBuilder(definition).buildContext(wrong, andreScene, andre).systemPrompt()
        val marinettePrompt = ContextBuilder(definition).buildContext(wrong, marinetteScene, marinette).systemPrompt()

        assertTrue("Andre should be able to act on his own belief", andrePrompt.contains("believes (wrongly)"))
        assertFalse(
            "Marinette must not receive Andre's belief",
            marinettePrompt.contains("some girl from another school"),
        )
        assertTrue(
            "Marinette must not receive Andre's belief",
            marinettePrompt.contains("Knows nothing about"),
        )
    }

    @Test
    fun `the pack knowing Ladybug's identity does not put it in anyone's prompt`() {
        // The brief's exact complaint: "author knows it" is not "character knows it".
        val instance = instance()
        val scene = SceneDirector(definition).directScene(instance, andre)!!
        val prompt = ContextBuilder(definition).buildContext(instance, scene, andre).systemPrompt()

        // Andre is a shopkeeper who knows nothing of any of it.
        assertTrue(
            "Andre's knowledge boundaries must hold in the prompt",
            prompt.contains("knows nothing about the Miraculous"),
        )
        assertNull(
            "Andre should hold no beliefs yet",
            instance.knowledge.mind(andre).beliefAbout("Ladybug"),
        )
    }

    @Test
    fun `the prompt tells the model not to correct its own beliefs`() {
        val story = applied(
            instance(),
            MisconceptionFormed(andre, "the new student", "is nobody Marinette knows", truth = "is Adrien Agreste"),
        )
        val scene = SceneDirector(definition).directScene(story, andre)!!
        val prompt = ContextBuilder(definition).buildContext(story, scene, andre).systemPrompt()
        assertTrue(
            "without this the model just corrects itself and the error is wasted",
            prompt.contains("Do not correct them"),
        )
    }

    // ------------------------------------------------------------------
    // Shape and lifecycle
    // ------------------------------------------------------------------

    @Test
    fun `a character with no mind gets an empty one rather than null`() {
        val mind = KnowledgeStore.EMPTY.mind(andre)
        assertNotNull(mind)
        assertTrue(mind.isEmpty())
        assertTrue(mind.promptLines().isEmpty())
        assertEquals(0, mind.size)
    }

    @Test
    fun `prompt lines are ordered so the load-bearing claims come first`() {
        val mind = CharacterMind(andre)
            .withObservation(Observation("an empty museum", StoryTime.START))
            .withBelief(Belief("the museum", "was robbed", 90))
            .withBelief(Belief("the thief", "wears gloves", 30))
            .withSuspicion(Suspicion("the curator", "lied", 50))
            .withMisconception(Misconception("the new student", "is nobody", "is Adrien"))

        val lines = mind.promptLines()
        assertTrue(lines.first().startsWith("saw:"))
        assertTrue(lines[1].startsWith("believes:"))
        assertTrue("a weak belief reads differently", lines.any { it.startsWith("half-believes:") })
        assertTrue(lines.any { it.startsWith("suspects:") })
        // The error goes last: it is the one the model must not "helpfully" fix on sight.
        assertTrue(lines.last().startsWith("believes (wrongly):"))
    }

    @Test
    fun `a mind survives persistence`() {
        val story = applied(
            instance(),
            MisconceptionFormed(andre, "x", "is wrong", truth = "is right"),
        )
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val encoded = codec.encodeToString(
            dev.charaly.runtime.domain.knowledge.KnowledgeStore.serializer(),
            story.knowledge,
        )
        val decoded = codec.decodeFromString(
            dev.charaly.runtime.domain.knowledge.KnowledgeStore.serializer(),
            encoded,
        )
        assertEquals(story.knowledge, decoded)
        assertEquals("is right", decoded.mind(andre).misconceptionAbout("x")!!.truth)
    }

    @Test
    fun `a mind can be cleared`() {
        val story = applied(instance(), BeliefFormed(andre, "x", "y", confidence = 90))
        assertTrue(story.knowledge.hasMind(andre))
        val cleared = story.knowledge.clearMind(andre)
        assertFalse(cleared.hasMind(andre))
        assertTrue(cleared.mind(andre).isEmpty())
    }

    @Test
    fun `minds are reported per character`() {
        // The Miraculous pack seeds a mind for most of its cast, so this asserts that
        // seeding two *more* is additive rather than replacing.
        val before = instance().knowledge.charactersWithMinds().toSet()
        val first = applied(instance(), BeliefFormed(sab, "x", "y", confidence = 90))
        val second = applied(first, BeliefFormed(kim, "z", "w", confidence = 90))
        val after = second.knowledge.charactersWithMinds().toSet()
        assertEquals(before + sab + kim, after)
        assertTrue(after.containsAll(before))
    }

    private fun LocationIdOf(value: String) = dev.charaly.runtime.domain.LocationId(value)
}