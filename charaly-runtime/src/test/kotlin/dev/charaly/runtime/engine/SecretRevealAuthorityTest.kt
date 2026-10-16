package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.InitialKnowledge
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.events.EventPayload
import dev.charaly.runtime.domain.events.FactRevealed
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.SecretRevealed
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.inference.MockInferenceEngine
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.FileCharalyStorage
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHO MAY REVEAL A SECRET.
 *
 * Phase 4 found the hole: `SecretRevealed` believed its own `revealedBy` claim. Anyone -
 * a model proposal, an authored effect, a hand-built payload - could name a character who
 * never knew the fact and hand the story's secret to an audience, which is how a mystery
 * becomes a formality.
 *
 * The rule this file pins down lives in the ONE authoritative place, `EventEngine`
 * validation, not in a prompt instruction or a UI filter:
 *
 *  1. a reveal is authorized by the revealer's own character-scoped knowledge of the fact;
 *  2. world truth is not knowledge (`FactRevealed` teaches nobody), and one character's
 *     knowledge never authorizes another;
 *  3. the domain's real knowledge sources - pack seeding ("story pack start"),
 *     `KnowledgeDiscovered`, an earlier reveal - all authorize, because all of them are
 *     what `knows` is made of;
 *  4. a refused reveal leaves NOTHING behind: no knowledge, no secret memory, no
 *     objective or reward downstream, no residue in the queue, and no partial mutation.
 *
 * Every rejection is asserted at the payload/apply boundary where it happens, through the
 * same `EventEngine` and `CharalyRuntime` the app runs.
 */
class SecretRevealAuthorityTest {

    private val hall = LocationId("loc-hall")
    private val garden = LocationId("loc-garden")

    private val alice = CharacterId("ch-alice")
    private val bob = CharacterId("ch-bob")
    private val carol = CharacterId("ch-carol")

    private val secretId = FactId("fact-secret")
    private val rewardId = FactId("fact-reward")

    private val secret = Fact(
        id = secretId,
        description = "The letter is hidden behind the portrait.",
        secret = true,
    )
    private val reward = Fact(
        id = rewardId,
        description = "The cellar door is open tonight.",
    )

    /**
     * Three characters and one secret.
     *
     * [seededKnower] receives the secret through `initialKnowledge`, which the factory
     * applies as `KnowledgeDiscovered(via = "story pack start")` - the domain's authored
     * source and the legitimate alternative to learning it mid-story.
     */
    private fun pack(
        seededKnower: CharacterId? = null,
        withObjective: Boolean = false,
    ): StoryPack = StoryPack(
        id = StoryPackId("pack.reveal-authority"),
        title = "Reveal Authority",
        characters = listOf(
            CharacterDefinition(id = alice, name = "Alice"),
            CharacterDefinition(id = bob, name = "Bob"),
            CharacterDefinition(id = carol, name = "Carol"),
        ),
        locations = listOf(
            Location(id = hall, name = "Hall", connections = listOf(garden)),
            Location(id = garden, name = "Garden", connections = listOf(hall)),
        ),
        initialWorldState = InitialWorldState(
            startLocations = mapOf(alice to hall, bob to garden, carol to hall),
        ),
        initialKnowledge = InitialKnowledge(
            facts = listOf(secret, reward),
            characterKnowledge = buildMap {
                seededKnower?.let { put(it, listOf(secretId.value)) }
            },
        ),
        objectives = if (withObjective) {
            listOf(
                PackObjective(
                    id = "obj-spill",
                    title = "The secret comes out",
                    description = "Bob learns what is behind the portrait.",
                    completionConditions = listOf(EventCondition.CharacterKnowsFact(bob, secretId)),
                    // The reward is itself a validated world change: it lands only if the
                    // objective truly completes.
                    onComplete = listOf(EventEffect.GrantKnowledge(carol, rewardId, via = "the secret came out")),
                ),
            )
        } else {
            emptyList()
        },
    )

    private fun repository(): CharalyRepository = JsonCharalyRepository(InMemoryCharalyStorage())

    private fun engine(pack: StoryPack): EventEngine =
        EventEngine(dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations))

    private suspend fun started(
        pack: StoryPack,
        repository: CharalyRepository = repository(),
    ): Pair<CharalyRuntime, StoryInstance> {
        val runtime = CharalyRuntime(repository = repository, engine = MockInferenceEngine())
        runtime.createPack(pack)
        val created = runtime.startStory(
            pack,
            StoryCreationOptions(
                instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-reveal"),
                focusCharacterId = alice,
            ),
        )
        return runtime to created
    }

    private fun EventApplication.rejectedBecause(reason: EventRejection): EventApplication.Rejected {
        assertTrue("expected a refusal, got $this", this is EventApplication.Rejected)
        val rejected = this as EventApplication.Rejected
        assertTrue(
            "expected $reason among ${rejected.validation.errors}",
            rejected.validation.errors.contains(reason),
        )
        return rejected
    }

    private fun StoryInstance.snapshotOfTruthAndHeads(): Pair<Set<CharacterId>, Set<Pair<CharacterId, String>>> =
        knowledge.knownBy(secretId).toSet() to
            knowledge.knowledge.flatMap { (who, entries) -> entries.map { who to it.factId.value } }.toSet()

    // ------------------------------------------------------------------
    // Positive: possessing the knowledge authorizes the reveal
    // ------------------------------------------------------------------

    @Test
    fun `a character who knows the fact may reveal it`() = runTest {
        val thePack = pack()
        val instance = started(thePack).second
        val engine = engine(thePack)

        // Carol learns the secret mid-story - an ordinary validated discovery.
        val learned = (engine.applyImmediately(
            instance,
            KnowledgeDiscovered(carol, secretId, via = "found the letter herself"),
        ) as EventApplication.Applied).instance

        val applied = engine.applyImmediately(
            learned,
            SecretRevealed(secretId, revealedBy = carol, recipients = listOf(bob)),
        )
        assertTrue("a knower may reveal, got $applied", applied is EventApplication.Applied)
        val story = (applied as EventApplication.Applied).instance

        assertTrue("the recipient was taught", story.knowledge.knows(bob, secretId))
        assertFalse("an unaddressed bystander was not", story.knowledge.knows(alice, secretId))
    }

    @Test
    fun `pack-seeded knowledge is a legitimate source that authorizes a reveal`() = runTest {
        // The authored path: Alice holds the secret from "story pack start".
        val thePack = pack(seededKnower = alice)
        val instance = started(thePack).second

        assertTrue(
            "the seed must leave Alice actually knowing the secret",
            instance.knowledge.knows(alice, secretId),
        )
        val applied = engine(thePack).applyImmediately(
            instance,
            SecretRevealed(secretId, revealedBy = alice, recipients = listOf(bob)),
        )
        assertTrue(
            "pack-seeded knowledge authorizes the reveal, got $applied",
            applied is EventApplication.Applied,
        )
    }

    // ------------------------------------------------------------------
    // Negative: no knowledge, no reveal
    // ------------------------------------------------------------------

    @Test
    fun `a character who does not know the fact cannot reveal it`() = runTest {
        val thePack = pack()
        val instance = started(thePack).second
        val engine = engine(thePack)

        // Truth is registered; nobody has learned it. Exactly the Phase 4 fixture shape.
        val before = instance.snapshotOfTruthAndHeads()
        val refused = engine.applyImmediately(
            instance,
            SecretRevealed(secretId, revealedBy = alice, recipients = listOf(bob)),
        )

        refused.rejectedBecause(EventRejection.REVEALER_DOES_NOT_KNOW)
        assertEquals("a refused reveal changes nobody's head", before, instance.snapshotOfTruthAndHeads())
    }

    @Test
    fun `one character's knowledge never authorizes another character's reveal`() = runTest {
        val thePack = pack(seededKnower = alice)
        val instance = started(thePack).second

        // Alice knows. Carol does not. Carol naming herself as revealer is the attack.
        val refused = engine(thePack).applyImmediately(
            instance,
            SecretRevealed(secretId, revealedBy = carol, recipients = listOf(bob)),
        )
        refused.rejectedBecause(EventRejection.REVEALER_DOES_NOT_KNOW)
        assertFalse("Bob must not be taught", instance.knowledge.knows(bob, secretId))
    }

    @Test
    fun `a reveal is refused even when the fact is not a secret at all`() = runTest {
        val thePack = pack()
        val instance = started(thePack).second

        // Non-secret truth is not public knowledge: `knows` is the only authority, and an
        // ordinary fact can no more be passed on unearned than a secret can.
        val refused = engine(thePack).applyImmediately(
            instance,
            SecretRevealed(rewardId, revealedBy = bob, recipients = listOf(carol)),
        )
        refused.rejectedBecause(EventRejection.REVEALER_DOES_NOT_KNOW)
        assertFalse(instance.knowledge.knows(carol, rewardId))
    }

    // ------------------------------------------------------------------
    // A refusal is total: nothing disclosed, nothing paid, nothing queued
    // ------------------------------------------------------------------

    @Test
    fun `a rejected reveal discloses nothing and pays out nothing downstream`() = runTest {
        val thePack = pack(withObjective = true)
        val (runtime, instance) = started(thePack)
        val engine = engine(thePack)

        val (beforeHeads, beforeEntries) = instance.snapshotOfTruthAndHeads()
        val refused = engine.applyImmediately(
            instance,
            SecretRevealed(secretId, revealedBy = carol, recipients = listOf(bob)),
        )
        refused.rejectedBecause(EventRejection.REVEALER_DOES_NOT_KNOW)

        // The evaluation the runtime runs after every turn: the objective's completion
        // condition is "Bob knows the secret", which the refused reveal must not satisfy.
        val evaluated = runtime.evaluateObjectives(instance)
        val objective = evaluated.objective("obj-spill")
        assertEquals(
            "a refused reveal must not resolve the objective",
            ObjectiveStatus.ACTIVE,
            objective?.status,
        )
        assertFalse(
            "and the reward must not be paid",
            evaluated.knowledge.knows(carol, rewardId),
        )
        assertEquals(beforeHeads, evaluated.knowledge.knownBy(secretId).toSet())
        assertEquals(beforeEntries, evaluated.knowledge.knowledge.flatMap { (who, entries) -> entries.map { who to it.factId.value } }.toSet())
        assertEquals(
            "no secret memory may leak out of a refusal",
            0,
            evaluated.memories.current().count { it.visibility == MemoryVisibility.SECRET },
        )

        // The mirror, so the assertion above cannot pass vacuously: the same reveal by a
        // real knower completes the objective and pays its reward.
        val seededPack = pack(seededKnower = alice, withObjective = true)
        val (seededRuntime, seededInstance) = started(seededPack)
        val landed = engine(seededPack).applyImmediately(
            seededInstance,
            SecretRevealed(secretId, revealedBy = alice, recipients = listOf(bob)),
        ) as EventApplication.Applied
        val paid = seededRuntime.evaluateObjectives(landed.instance)
        assertEquals(ObjectiveStatus.COMPLETED, paid.objective("obj-spill")?.status)
        assertTrue("the reward lands for the character it names", paid.knowledge.knows(carol, rewardId))
    }

    @Test
    fun `a refused reveal scheduled through the runtime leaves no residue`() = runTest {
        val thePack = pack()
        val (runtime, instance) = started(thePack)

        // The production entry point an inspector/debug UI and scripted effects use:
        // schedule -> applyEvent -> (refused) -> dropped from the queue.
        val after: StoryInstance = runtime.scheduleAndProcess(
            instance,
            SecretRevealed(secretId, revealedBy = bob, recipients = listOf(carol)),
        )

        assertEquals("nothing left to process later", 0, after.eventQueue.size)
        assertFalse(after.knowledge.knows(carol, secretId))
        assertFalse(
            "the world log must not claim a reveal that never happened",
            after.worldState.changeLog.any { it.summary.contains(secretId.value) },
        )
    }

    // ------------------------------------------------------------------
    // Durability: ownership and the rule survive a reload
    // ------------------------------------------------------------------

    @Test
    fun `knowledge ownership and reveal validation remain correct after persistence and reload`() = runTest {
        val root = Files.createTempDirectory("charaly-reveal-reload").toFile()
        try {
            val storage = FileCharalyStorage(root)
            val thePack = pack(seededKnower = alice)
            val (runtime, instance) = started(thePack, JsonCharalyRepository(storage))

            val revealed = (engine(thePack).applyImmediately(
                instance,
                SecretRevealed(secretId, revealedBy = alice, recipients = listOf(bob)),
            ) as EventApplication.Applied).instance
            runtime.save(revealed)

            // A cold start: a brand new repository over the same tree, exactly what a
            // process restart gives the app.
            val reopened = JsonCharalyRepository(storage).getInstance(revealed.id)!!
            assertEquals(setOf(alice, bob), reopened.knowledge.knownBy(secretId).toSet())

            // The rule travels with the state, not with the session: after the reload an
            // ignorant character is still refused, and a genuine knower may pass it on.
            val stillRefused = engine(thePack).applyImmediately(
                reopened,
                SecretRevealed(secretId, revealedBy = carol, recipients = listOf(alice)),
            )
            stillRefused.rejectedBecause(EventRejection.REVEALER_DOES_NOT_KNOW)

            val bobHandsItOn = engine(thePack).applyImmediately(
                reopened,
                SecretRevealed(secretId, revealedBy = bob, recipients = listOf(carol)),
            )
            assertTrue(
                "Bob learned it, so Bob may pass it on; got $bobHandsItOn",
                bobHandsItOn is EventApplication.Applied,
            )
            assertEquals(
                setOf(alice, bob, carol),
                (bobHandsItOn as EventApplication.Applied).instance.knowledge.knownBy(secretId).toSet(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics: a refusal is loud, not silent
    // ------------------------------------------------------------------

    @Test
    fun `a refusal names its cause for the diagnostics channel`() = runTest {
        val thePack = pack()
        val instance = started(thePack).second
        val refused = engine(thePack).applyImmediately(
            instance,
            SecretRevealed(secretId, revealedBy = bob, recipients = listOf(alice)),
        )

        val rejected = refused.rejectedBecause(EventRejection.REVEALER_DOES_NOT_KNOW)
        assertNotNull(rejected.event)
        assertTrue(
            "the human-readable description must name the rule, not shrug",
            rejected.validation.describe().contains("revealer does not know"),
        )
    }

    /** Every helper path exercises the same payload type the engine serializes. */
    @Test
    fun `the reveal payload names its parties from canonical ids`() = runTest {
        val payload: EventPayload = SecretRevealed(secretId, revealedBy = alice, recipients = listOf(bob))
        val summary = payload.summary
        assertTrue(summary.contains(alice.value))
        assertTrue(summary.contains(secretId.value))
        assertTrue(summary.contains(bob.value))
    }
}