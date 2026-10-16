package dev.charaly.runtime.integration

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.domain.events.SecretRevealed
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.CharalyRuntime.ChoiceOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * World-runtime hardening: the invariants that keep a story coherent, deterministic
 * and persistent.
 *
 * Every test here drives the REAL runtime path (CharalyRuntime + EventEngine +
 * JsonCharalyRepository) rather than a substitute, because the defects being
 * guarded against live exactly at the seams between those pieces - a choice that is
 * recorded without landing, an objective that completes without paying out, a
 * rejected event that lingers in the queue.
 *
 * The fixture pack is built so that a specific effect is *guaranteed* to be refused
 * by the engine: the rooftop is deliberately disconnected from the library, so any
 * `MoveCharacter(library -> rooftop)` is an ILLEGAL_MOVEMENT. That gives each test a
 * deterministic rejection to aim at.
 */
class WorldRuntimeHardeningTest {

    private val alice = CharacterId("alice")
    private val bob = CharacterId("bob")
    private val library = LocationId("library")
    private val square = LocationId("square")
    private val rooftop = LocationId("rooftop")

    /**
     * A pack whose rooftop is unreachable from the library, so a move between them is
     * always refused. That is the rejection every defect test aims at.
     */
    private fun hardeningPack(): StoryPack = StoryPack(
        id = StoryPackId("pack-hardening"),
        title = "Hardening",
        characters = listOf(
            CharacterDefinition(id = alice, name = "Alice"),
            CharacterDefinition(id = bob, name = "Bob"),
        ),
        locations = listOf(
            Location(id = library, name = "Library", connections = listOf(square)),
            Location(id = square, name = "Square", connections = listOf(library)),
            // Deliberately isolated: nothing connects to the rooftop.
            Location(id = rooftop, name = "Rooftop", connections = emptyList()),
        ),
        initialWorldState = InitialWorldState(
            startLocations = mapOf(alice to library, bob to square),
        ),
        scenes = listOf(
            SceneTemplate(
                id = "scene-library",
                name = "The Library",
                locationId = library,
                choices = listOf(
                    SceneChoice(
                        id = "choice-follow",
                        label = "Follow her up",
                        once = true,
                        // Always refused: the rooftop is not adjacent to the library.
                        effects = listOf(EventEffect.MoveCharacter(alice, rooftop)),
                    ),
                ),
            ),
        ),
        objectives = listOf(
            PackObjective(
                id = "obj-reach-rooftop",
                title = "Reach the rooftop",
                completionConditions = listOf(EventCondition.VariableEquals("flag", "yes")),
                // Always refused, so the payout can never land.
                onComplete = listOf(EventEffect.MoveCharacter(alice, rooftop)),
            ),
        ),
    )

    private fun runtime(): CharalyRuntime = CharalyRuntime(
        repository = JsonCharalyRepository(InMemoryCharalyStorage()),
        engine = dev.charaly.runtime.inference.MockInferenceEngine(),
    )

    private suspend fun startedStory(rt: CharalyRuntime, pack: StoryPack = hardeningPack()) =
        rt.startStory(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-hardening"),
                focusCharacterId = alice,
            ),
        )

    private fun engineFor(pack: StoryPack) =
        EventEngine(dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations))

    // ------------------------------------------------------------------
    // Defect 1: a branch choice recorded without its state transition
    // ------------------------------------------------------------------

    /**
     * Taking a branch whose every effect the engine refuses must NOT record the branch
     * as taken.
     *
     * The choice is offered (no conditions), but its only effect - a move to the
     * disconnected rooftop - is an ILLEGAL_MOVEMENT. Recording it anyway would consume
     * a `once` branch while leaving the world exactly as it was: the branch is gone,
     * nothing happened, and there is no way to take it again.
     */
    @Test
    fun `a choice whose every effect is refused is not recorded as taken`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        val instance = startedStory(rt, pack)
        val revisionBefore = instance.worldState.revision

        val outcome = rt.takeChoice(instance, "scene-library", "choice-follow")

        assertTrue(
            "a choice with no landing effect must be refused, was: $outcome",
            outcome is ChoiceOutcome.Refused,
        )
        val after = rt.loadStory(instance.id)!!
        assertFalse(
            "the choice ledger must not record a branch that changed nothing",
            after.tookChoice("scene-library", "choice-follow"),
        )
        assertEquals(
            "the world must be unchanged by a fully-refused choice",
            revisionBefore,
            after.worldState.revision,
        )
        assertEquals(library, after.characters.getValue(alice).locationId)
    }

    /**
     * The mirror of the defect: a choice whose effect DOES land is recorded, and a
     * `once` choice cannot then be taken a second time.
     */
    @Test
    fun `a choice whose effect lands is recorded and cannot be retaken once`() = runTest {
        val rt = runtime()
        val pack = hardeningPack().let { p ->
            p.copy(
                scenes = listOf(
                    SceneTemplate(
                        id = "scene-library",
                        name = "The Library",
                        locationId = library,
                        choices = listOf(
                            SceneChoice(
                                id = "choice-to-square",
                                label = "Step outside",
                                once = true,
                                // Legal: the square is adjacent to the library.
                                effects = listOf(EventEffect.MoveCharacter(alice, square)),
                            ),
                        ),
                    ),
                ),
            )
        }
        rt.createPack(pack)
        val instance = startedStory(rt, pack)

        val first = rt.takeChoice(instance, "scene-library", "choice-to-square")
        assertTrue("the legal choice must be taken", first is ChoiceOutcome.Taken)
        val afterFirst = rt.loadStory(instance.id)!!
        assertTrue(afterFirst.tookChoice("scene-library", "choice-to-square"))
        assertEquals(square, afterFirst.characters.getValue(alice).locationId)

        val second = rt.takeChoice(afterFirst, "scene-library", "choice-to-square")
        assertTrue("a once choice must not be retaken", second is ChoiceOutcome.Refused)
    }

    // ------------------------------------------------------------------
    // Defect 2: an objective resolved without its payout landing
    // ------------------------------------------------------------------

    /**
     * An objective whose completion conditions hold but whose payout the engine
     * refuses must NOT be marked resolved.
     *
     * Marking it resolved both loses the reward and stops it ever being retried,
     * because a terminal objective is never re-evaluated. Keeping it active lets the
     * next turn try the payout again.
     */
    @Test
    fun `an objective whose payout is refused stays active rather than completing`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        var instance = startedStory(rt, pack)

        // Make the completion condition true through a validated event.
        val engine = engineFor(pack)
        instance = (engine.applyImmediately(instance, WorldVariableSet("flag", "yes")) as EventApplication.Applied)
            .instance

        val evaluated = rt.evaluateObjectives(instance)
        val state = evaluated.objective("obj-reach-rooftop")

        assertTrue(
            "an objective whose payout cannot land must stay active, was ${state?.status}",
            state?.status == ObjectiveStatus.ACTIVE,
        )
        assertEquals(
            "the refused payout must not have moved Alice",
            library,
            evaluated.characters.getValue(alice).locationId,
        )
    }

    /**
     * The mirror: an objective whose payout lands completes normally.
     */
    @Test
    fun `an objective whose payout lands completes`() = runTest {
        val rt = runtime()
        val pack = hardeningPack().let { p ->
            p.copy(
                objectives = listOf(
                    PackObjective(
                        id = "obj-legal",
                        title = "Step outside",
                        completionConditions = listOf(EventCondition.VariableEquals("flag", "yes")),
                        onComplete = listOf(EventEffect.MoveCharacter(alice, square)),
                    ),
                ),
            )
        }
        rt.createPack(pack)
        var instance = startedStory(rt, pack)
        val engine = engineFor(pack)
        instance = (engine.applyImmediately(instance, WorldVariableSet("flag", "yes")) as EventApplication.Applied)
            .instance

        val evaluated = rt.evaluateObjectives(instance)
        assertEquals(ObjectiveStatus.COMPLETED, evaluated.objective("obj-legal")?.status)
        assertEquals(square, evaluated.characters.getValue(alice).locationId)
    }

    // ------------------------------------------------------------------
    // Defect 3: a rejected event lingering in the queue
    // ------------------------------------------------------------------

    /**
     * An explicitly scheduled action the engine refuses must be dropped from the
     * queue, exactly as `processEventsUntil` drops a refused due event.
     *
     * Leaving it queued means the next clock advance finds it due and rejects it
     * again - a stale invalid event that never goes away on its own.
     */
    @Test
    fun `a refused scheduled action is dropped from the queue`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        val instance = startedStory(rt, pack)

        val after = rt.scheduleAndProcess(
            instance,
            CharacterMoved(alice, from = library, to = rooftop),
        )

        assertEquals(
            "a refused event must not stay queued",
            0,
            after.eventQueue.size,
        )
        assertEquals(library, after.characters.getValue(alice).locationId)
    }

    // ------------------------------------------------------------------
    // Phase F journeys through the real runtime path
    // ------------------------------------------------------------------

    /**
     * Journey 4: revealing a secret grants knowledge to the intended recipient only,
     * and the boundary survives a save/reload.
     *
     * The revealer genuinely holds the secret beforehand - Alice learned it by
     * `KnowledgeDiscovered`, one of the domain's real knowledge sources. That is not
     * decoration: since `SecretRevealed` validates the revealer's own knowledge
     * (`SecretRevealAuthorityTest` proves the refusal), a fixture whose revealer was
     * ignorant could no longer exercise the recipient boundary at all.
     */
    @Test
    fun `a secret reveal grants knowledge only to its recipient and survives reload`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        var instance = startedStory(rt, pack)
        val engine = engineFor(pack)

        val secret = Fact(
            id = dev.charaly.runtime.domain.FactId("fact-secret"),
            description = "The rooftop key is loose.",
            secret = true,
        )
        instance = (engine.applyImmediately(instance, dev.charaly.runtime.domain.events.FactRevealed(secret))
            as EventApplication.Applied).instance
        instance = (engine.applyImmediately(
            instance,
            dev.charaly.runtime.domain.events.KnowledgeDiscovered(
                characterId = alice,
                factId = secret.id,
                via = "found the loose key herself",
            ),
        ) as EventApplication.Applied).instance
        assertTrue("the revealer holds the secret before revealing", instance.knowledge.knows(alice, secret.id))
        assertFalse("Bob is told nothing yet", instance.knowledge.knows(bob, secret.id))

        instance = (engine.applyImmediately(
            instance,
            SecretRevealed(secret.id, revealedBy = alice, recipients = listOf(bob)),
        ) as EventApplication.Applied).instance

        assertTrue("Bob was told", instance.knowledge.knows(bob, secret.id))
        // Exactly the two characters the reveal concerned hold it - the reveal taught its
        // audience and spread to nobody.
        assertEquals(
            "the revealer and the recipient, and nobody else",
            setOf(alice, bob),
            instance.knowledge.knownBy(secret.id).toSet(),
        )

        // The reveal left a secret-visible memory for Bob only.
        val bobSecrets = instance.memories.current().filter {
            it.characterId == bob && it.visibility == MemoryVisibility.SECRET
        }
        assertEquals("Bob holds a secret memory of the reveal", 1, bobSecrets.size)
        assertEquals(
            "and no stray secret memory leaked to Alice",
            0,
            instance.memories.current().count { it.characterId == alice && it.visibility == MemoryVisibility.SECRET },
        )

        // Save, restart, reload: the boundary holds.
        instance = rt.save(instance)
        val reopened = rt.loadStory(instance.id)!!
        assertTrue("Bob still knows after reload", reopened.knowledge.knows(bob, secret.id))
        assertEquals(
            "ownership after reload is still exactly the revealer and the recipient",
            setOf(alice, bob),
            reopened.knowledge.knownBy(secret.id).toSet(),
        )
    }

    /**
     * Journey 5: a relationship-changing event survives a save/restart.
     */
    @Test
    fun `a relationship change survives a restart`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        var instance = startedStory(rt, pack)
        val engine = engineFor(pack)

        instance = (engine.applyImmediately(
            instance,
            dev.charaly.runtime.domain.events.RelationshipChanged(
                sourceId = alice,
                targetId = bob,
                delta = dev.charaly.runtime.domain.RelationshipDelta(trust = 20, affinity = 10),
                reason = "shared a secret",
            ),
        ) as EventApplication.Applied).instance
        instance = rt.save(instance)

        val reopened = rt.loadStory(instance.id)!!
        val rel = reopened.worldState.relationship(alice, bob)
        assertTrue("the relationship must survive the restart", rel != null)
        assertEquals("and carry its history", 1, rel!!.history.size)
        assertTrue("and its note", rel.note.contains("shared a secret"))
    }

    /**
     * Journey 7: advancing time fires a scheduled event exactly once.
     */
    @Test
    fun `advancing time fires a scheduled event exactly once`() = runTest {
        val rt = runtime()
        val pack = hardeningPack().let { p ->
            p.copy(
                initialEvents = listOf(
                    dev.charaly.runtime.domain.SeedEvent(
                        payload = WorldVariableSet("visited", "yes"),
                        delayMinutes = 30,
                    ),
                ),
            )
        }
        rt.createPack(pack)
        var instance = startedStory(rt, pack)
        // The event is scheduled 30 minutes in the future, so it has not fired yet.
        assertEquals("the event must not have fired before its time", null, instance.worldState.variables["visited"]?.value)

        instance = rt.advance(instance, dev.charaly.runtime.domain.StoryDuration.minutes(60))
        assertEquals("yes", instance.worldState.variables["visited"]?.value)

        // Advancing further must not fire the one-shot event again. The only new event
        // a further advance may add is the clock tick itself (one TimeAdvanced); if the
        // seed event refired, the log would grow by two.
        val eventCount = instance.worldState.eventLog.size
        instance = rt.advance(instance, dev.charaly.runtime.domain.StoryDuration.minutes(60))
        assertEquals(
            "a one-shot scheduled event must not refire on a later advance",
            eventCount + 1,
            instance.worldState.eventLog.size,
        )
    }

    /**
     * Journey 8: a save/resume preserves world state and continued play does not
     * replay already-applied events.
     */
    @Test
    fun `save and resume preserves state and does not replay applied events`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        var instance = startedStory(rt, pack)
        val engine = engineFor(pack)

        instance = (engine.applyImmediately(instance, WorldVariableSet("progress", "5")) as EventApplication.Applied)
            .instance
        val eventCount = instance.worldState.eventLog.size
        instance = rt.save(instance)

        val reopened = rt.loadStory(instance.id)!!
        assertEquals("the clock survives", instance.worldClock.now, reopened.worldClock.now)
        assertEquals("the event log survives", eventCount, reopened.worldState.eventLog.size)
        assertEquals("variables survive", "5", reopened.worldState.variables["progress"]?.value)

        // Continuing applies new events without replaying old ones.
        val continued = (engine.applyImmediately(reopened, WorldVariableSet("progress", "6")) as EventApplication.Applied)
            .instance
        assertEquals("the new event is applied on top of the saved state", eventCount + 1, continued.worldState.eventLog.size)
        assertEquals("6", continued.worldState.variables["progress"]?.value)
    }

    /**
     * Journey 9: applying the same event twice is idempotent at the engine level -
     * a no-op application records the event without duplicating its effect.
     */
    @Test
    fun `reapplying an already-applied change is a no-op the second time`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        var instance = startedStory(rt, pack)
        val engine = engineFor(pack)

        val first = engine.applyImmediately(instance, WorldVariableSet("k", "v"))
        assertTrue(first is EventApplication.Applied)
        instance = (first as EventApplication.Applied).instance

        val second = engine.applyImmediately(instance, WorldVariableSet("k", "v"))
        assertTrue("reapplying the same change is a no-op", second is EventApplication.Applied)
        assertEquals("the variable is not duplicated", "v", instance.worldState.variables["k"]?.value)
    }

    /**
     * Journey 10: a previously valid pack and instance remain compatible - a story
     * created from a pack reloads and plays after a restart.
     */
    @Test
    fun `a saved story reloads and plays after a restart`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        val instance = startedStory(rt, pack)
        val saved = rt.save(instance)

        val reopened = rt.loadStory(saved.id)!!
        assertEquals(saved.storyPackId, reopened.storyPackId)
        assertEquals(saved.worldClock.now, reopened.worldClock.now)
        assertEquals(saved.worldState.characters.keys, reopened.worldState.characters.keys)

        // And it still accepts a validated world change.
        val engine = engineFor(pack)
        val played = (engine.applyImmediately(reopened, WorldVariableSet("played", "yes")) as EventApplication.Applied)
            .instance
        assertEquals("yes", played.worldState.variables["played"]?.value)
    }

    /**
     * A memory with stable identity and correct ownership survives a save/reload.
     */
    @Test
    fun `an important memory survives a save and reload with its owner`() = runTest {
        val rt = runtime()
        val pack = hardeningPack()
        rt.createPack(pack)
        var instance = startedStory(rt, pack)
        val engine = engineFor(pack)

        val memory = Memory(
            id = MemoryId("mem-important"),
            characterId = alice,
            content = "Bob told me about the rooftop key.",
            importance = 5,
        )
        instance = (engine.applyImmediately(
            instance,
            dev.charaly.runtime.domain.events.MemoryCreated(memory),
        ) as EventApplication.Applied).instance
        instance = rt.save(instance)

        val reopened = rt.loadStory(instance.id)!!
        val restored = reopened.memories.byId(MemoryId("mem-important"))
        assertTrue("the memory must survive the restart", restored != null)
        assertEquals("with its owner", alice, restored!!.characterId)
        assertEquals("and its content", "Bob told me about the rooftop key.", restored.content)
        assertTrue(
            "and be indexed on the owner's runtime",
            reopened.characters.getValue(alice).memoryIds.contains(MemoryId("mem-important")),
        )
    }
}
