package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.ScheduleResult
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.FactRevealed
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.SecretRevealed
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The causal graph.
 *
 * An event log answers "in what order". The graph answers "why", which is the only
 * question a story is actually made of - and the reason a player who was offered a
 * deal three scenes ago cannot otherwise connect that to the akuma appearing now.
 *
 * Two things must hold: the graph is real (edges exist, are typed, and can be walked),
 * and it is honest (an event that did nothing does not become a node, and no cycle can
 * hang a diagnostic).
 */
class CausalGraphTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val engine = EventEngine(definition)

    private val marinette = CharacterId("marinette")
    private val adrien = CharacterId("adrien")
    private val gabriel = CharacterId("gabriel")
    private val factId = FactId("fact-a")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-causal"),
            title = "Causality",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun apply(from: StoryInstance, payload: dev.charaly.runtime.domain.events.EventPayload) =
        engine.applyImmediately(from, payload)

    /** Applies a scheduled event, not just a payload. */
    private fun appliedEvent(from: StoryInstance, event: dev.charaly.runtime.domain.events.WorldEvent): StoryInstance =
        (engine.applyEvent(from, event) as EventApplication.Applied).instance

    private fun applied(
        from: StoryInstance,
        payload: dev.charaly.runtime.domain.events.EventPayload,
    ): StoryInstance = (apply(from, payload) as EventApplication.Applied).instance

    /**
     * An instance where [factId] exists in world truth.
     *
     * `KnowledgeDiscovered` validates that the fact exists, so a test that discovers a
     * fact has to put it in the world first - otherwise it is testing the validator
     * rather than the graph.
     */
    private fun withFact(): StoryInstance =
        applied(instance(), FactRevealed(Fact(id = factId, description = "the east wing was unlocked")))

    /** The same, for a chain-specific fact id. */
    private fun withFact(id: FactId): StoryInstance =
        applied(instance(), FactRevealed(Fact(id = id, description = "chain fact $id")))

    // ------------------------------------------------------------------
    // Edges exist and are typed
    // ------------------------------------------------------------------

    @Test
    fun `a chained event records why it happened`() {
        val start = withFact()
        // The first event becomes the root of the chain.
        val rootScheduled = engine.scheduleEvent(
            instance = start,
            payload = KnowledgeDiscovered(marinette, factId),
            at = start.worldClock.now,
            origin = EventOrigin.USER,
            because = CausalReason.PLAYER_CHOICE,
        )
        val root = (rootScheduled as ScheduleResult.Scheduled)
        val rootId = root.events.single().id

        val next = engine.scheduleEvent(
            instance = root.instance,
            payload = FactRevealed(Fact(id = factId, description = "the east wing was unlocked")),
            at = start.worldClock.now.plusMinutes(5),
            origin = EventOrigin.ENGINE,
            causedBy = rootId,
            because = CausalReason.KNOWLEDGE_GAINED,
        )
        val effectId = (next as ScheduleResult.Scheduled).events.single().id

        val withBoth = appliedEvent(appliedEvent(root.instance, root.events.single()), next.events.single())

        assertTrue(
            "the edge must exist once both events have been applied",
            withBoth.worldState.causality.containsKey(effectId),
        )
        assertEquals(rootId, withBoth.worldState.causality[effectId]!!.causeId)
        assertEquals(CausalReason.KNOWLEDGE_GAINED, withBoth.worldState.causality[effectId]!!.reason)
    }

    @Test
    fun `an event with no declared cause records no edge`() {
        val start = withFact()
        val result = apply(start, KnowledgeDiscovered(marinette, factId)) as EventApplication.Applied
        // applyImmediately declares no cause, so *this* event contributes no edge - even
        // though the story's own opening seeds legitimately did.
        assertFalse(
            "inventing a cause is worse than admitting there is none",
            result.event.id in result.instance.worldState.causality,
        )
    }

    @Test
    fun `a no-op event does not become a node other things point at`() {
        val start = instance()
        // Establish the fact first, so the second discovery is a genuine no-op.
        val informed = applied(start, FactRevealed(Fact(id = factId, description = "something")))
        val knows = applied(informed, KnowledgeDiscovered(marinette, factId))

        val noOp = apply(knows, KnowledgeDiscovered(marinette, factId)) as EventApplication.Applied
        assertTrue("the premise: this second discovery changes nothing", noOp.noOp)
        assertFalse(
            "an event that did not happen cannot have caused anything",
            noOp.event.id in noOp.instance.worldState.causality,
        )
    }

    // ------------------------------------------------------------------
    // Walking the graph
    // ------------------------------------------------------------------

    @Test
    fun `a cause chain can be walked`() {
        val chain = buildChain()
        val story = chain.last()
        val previousId = chain[chain.size - 2].worldState.eventLog.last()
        val link = story.worldState.causality.entries.first { it.value.causeId == previousId }.value
        assertTrue("the effect must have a cause", story.worldState.causality.isNotEmpty())
        assertEquals("the cause must be the previous event", previousId, link.causeId)
    }

    @Test
    fun `effects can be found from a cause`() {
        val chain = buildChain()
        val firstEventId = chain.first().worldState.eventLog.last()
        assertTrue(
            "something must follow the first event",
            chain.last().worldState.effectsOf(firstEventId).isNotEmpty(),
        )
    }

    @Test
    fun `why is explained in words`() {
        val chain = buildChain()
        val story = chain.last()
        val effectId = story.worldState.causality.keys.last()
        val explained = story.worldState.explainWhy(effectId) { it.value }
        assertTrue("an unexplained cause chain is a log again", explained != "nothing in particular")
        assertTrue(explained.contains("because"))
    }

    @Test
    fun `an event with no causes says so plainly`() {
        val chain = buildChain()
        val root = chain.first()
        val rootId = root.worldState.eventLog.last()
        assertEquals("nothing in particular", root.worldState.explainWhy(rootId))
    }

    /**
     * Three events chained a -> b -> c, all applied.
     *
     * The chain is built by scheduling each event with the previous one's id as its
     * cause, then applying them in order - which is exactly how a consequence chain
     * forms at runtime.
     */
    private fun buildChain(): List<StoryInstance> {
        var current = instance()
        val steps = mutableListOf<StoryInstance>()
        var previous: EventId? = null

        repeat(3) { index ->
            val fact = FactId("fact-chain-$index")
            current = applied(current, FactRevealed(Fact(id = fact, description = "chain fact $index")))
            val payload = KnowledgeDiscovered(characterId = marinette, factId = fact)
            val scheduled = engine.scheduleEvent(
                instance = current,
                payload = payload,
                at = current.worldClock.now.plusMinutes(index.toLong()),
                origin = EventOrigin.ENGINE,
                causedBy = previous,
                because = CausalReason.CONSEQUENCE,
            )
            val event = (scheduled as ScheduleResult.Scheduled).events.single()
            current = appliedEvent(current, event)
            steps += current
            previous = event.id
        }
        return steps
    }

    // ------------------------------------------------------------------
    // Robustness
    // ------------------------------------------------------------------

    @Test
    fun `a cycle in the graph does not hang the walk`() {
        // Consequences referencing each other can absolutely produce a cycle, and a
        // diagnostic that hangs is worse than one that truncates.
        val cyclic = instance().let { start ->
            start.copy(
                worldState = start.worldState.copy(
                    causality = mapOf(
                        EventId("evt-a") to CausalLink(EventId("evt-b"), EventId("evt-a"), CausalReason.CONSEQUENCE),
                        EventId("evt-b") to CausalLink(EventId("evt-a"), EventId("evt-b"), CausalReason.CONSEQUENCE),
                    ),
                ),
            )
        }
        val chain = cyclic.worldState.causesOf(EventId("evt-a"))
        assertTrue("a cycle must terminate", chain.size <= WorldState.MAX_CAUSE_DEPTH)
        assertTrue("and it must return something rather than nothing", chain.isNotEmpty())
    }

    @Test
    fun `an event cannot cause itself`() {
        assertTrue(
            runCatching { CausalLink(EventId("evt-x"), EventId("evt-x"), CausalReason.CLOCK) }.isFailure,
        )
    }

    @Test
    fun `the graph survives persistence`() {
        val chain = buildChain()
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val encoded = codec.encodeToString(StoryInstance.serializer(), chain.last())
        val decoded = codec.decodeFromString(StoryInstance.serializer(), encoded)
        assertEquals(chain.last().worldState.causality, decoded.worldState.causality)
    }

    // ------------------------------------------------------------------
    // Reasons are queryable, which is the whole point
    // ------------------------------------------------------------------

    @Test
    fun `player responsibility is queryable`() {
        // "What did my decisions actually do?" has to be answerable from the graph
        // rather than from the model's narration, or the feature is decorative.
        assertTrue(CausalReason.PLAYER_CHOICE.isPlayerResponsible)
        assertTrue(CausalReason.DEAL_ACCEPTED.isPlayerResponsible)
        assertTrue(CausalReason.DEAL_REFUSED.isPlayerResponsible)
        assertFalse(CausalReason.CLOCK.isPlayerResponsible)
        assertFalse(CausalReason.ROUTINE.isPlayerResponsible)
    }

    @Test
    fun `every reason has a human label`() {
        // An enum member with a blank label shows up as a blank in the inspector.
        CausalReason.entries.forEach {
            assertTrue("${it.name} has no label", it.label.isNotBlank())
        }
    }

    // ------------------------------------------------------------------
    // Secrets
    // ------------------------------------------------------------------

    @Test
    fun `a reveal teaches exactly its recipients`() {
        val start = applied(
            instance(),
            FactRevealed(Fact(id = factId, description = "Adrien is Cat Noir", secret = true)),
        )
        val story = applied(
            start,
            SecretRevealed(
                factId = factId,
                revealedBy = adrien,
                recipients = listOf(marinette),
            ),
        )
        assertTrue("the person told now knows", story.knowledge.knows(marinette, factId))
        assertFalse(
            "and nobody else does",
            story.knowledge.knows(gabriel, factId),
        )
    }

    @Test
    fun `a reveal to nobody is refused`() {
        // A secret that came out has an audience. Registering it as revealed while
        // teaching no one is how a reveal becomes omnipotent.
        val start = applied(instance(), FactRevealed(Fact(id = factId, description = "something")))
        assertTrue(apply(start, SecretRevealed(factId, adrien, recipients = emptyList())) is EventApplication.Rejected)
    }

    @Test
    fun `a revealed secret is remembered as a secret, not world knowledge`() {
        val start = applied(
            instance(),
            FactRevealed(Fact(id = factId, description = "Adrien is Cat Noir", secret = true)),
        )
        val story = applied(start, SecretRevealed(factId, adrien, recipients = listOf(marinette)))
        val memory = story.memories.of(marinette).firstOrNull {
            it.relatedFactIds.contains(factId)
        }
        assertNotNull("being told a secret must be remembered", memory)
        assertEquals(
            dev.charaly.runtime.domain.memory.MemoryTier.SECRET,
            memory!!.tier,
        )
        assertFalse(
            "and it must not be readable by anyone else",
            memory.visibleTo(dev.charaly.runtime.domain.memory.MemorySubject.Character(gabriel)),
        )
    }
}