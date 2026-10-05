package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.domain.events.RelationshipChanged
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.persistence.JsonCharalyRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relationship 2.0.
 *
 * The old model was trust, familiarity and affinity, which cannot tell apart "trusted
 * colleague who is not a friend" from "friend who lies constantly". These tests hold
 * the four extra axes, the stage, and - the part that actually matters - the claim
 * that the model still cannot move any of them by saying something.
 */
class RelationshipAxesTest {

    private val pack = MiraculousPack.pack
    private val marinette = CharacterId("marinette")
    private val andre = CharacterId("andre")
    private val adrien = CharacterId("adrien")

    private val engine = EventEngine(dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations))

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-rel"),
            title = "Relationships",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    /**
     * Applies one relationship event and returns both the new edge and the instance.
     *
     * The instance is threaded through rather than rebuilt, because a relationship's
     * whole point is that it accumulates: rebuilding per call would test each event in
     * isolation and never exercise the stage.
     *
     * The pair is Marinette and Andre rather than Marinette and Adrien, because the
     * pack ships those two already close (trust 70, familiarity 75). Starting from a
     * pair the world already knows would make every baseline assertion a lie.
     */
    private fun relate(
        delta: RelationshipDelta,
        from: dev.charaly.runtime.domain.StoryInstance = instance(),
        note: String = "test",
    ): Pair<Relationship, dev.charaly.runtime.domain.StoryInstance> {
        val applied = engine.applyImmediately(
            from,
            RelationshipChanged(
                sourceId = marinette,
                targetId = andre,
                delta = delta,
                reason = note,
            ),
        )
        val rejected = applied as? dev.charaly.runtime.engine.EventApplication.Rejected
        assertTrue(
            "the event must apply, but was rejected: ${rejected?.validation?.describe()}",
            rejected == null,
        )
        val appliedInstance = (applied as dev.charaly.runtime.engine.EventApplication.Applied).instance
        return appliedInstance.relationships.getValue(RelationshipKey(marinette, andre)) to appliedInstance
    }

    /** [relate] for the common case where only the resulting edge is needed. */
    private fun edge(delta: RelationshipDelta, note: String = "test"): Relationship =
        relate(delta, note = note).first

    // ------------------------------------------------------------------
    // The new axes
    // ------------------------------------------------------------------

    @Test
    fun `trust and affinity are independent axes`() {
        // A brand new edge starts at the neutral 50/50/0, so the expected values are
        // the deltas themselves rather than anything the pack happened to ship.
        val relation = edge(RelationshipDelta(trust = 40, affinity = -40, familiarity = 20))
        assertEquals(90, relation.trust)
        assertEquals(10, relation.affinity)
        assertEquals(20, relation.familiarity)
    }

    @Test
    fun `tension, respect, fear and dependency are real state`() {
        val relation = edge(
            RelationshipDelta(
                familiarity = 30,
                tension = 40,
                respect = 30,
                fear = 20,
                dependency = 45,
            ),
        )
        assertEquals(40, relation.tension)
        assertEquals(80, relation.respect)
        assertEquals(20, relation.fear)
        assertEquals(45, relation.dependency)
    }

    @Test
    fun `every axis is clamped rather than overflowing`() {
        val relation = edge(RelationshipDelta(trust = 500, tension = -400, fear = 9000))
        assertTrue("trust must clamp to 100, was ${relation.trust}", relation.trust in 0..100)
        assertEquals(100, relation.trust)
        assertTrue("tension must clamp to 0, was ${relation.tension}", relation.tension in 0..100)
        assertEquals(0, relation.tension)
        assertEquals(100, relation.fear)
    }

    @Test
    fun `an out of range axis is refused at construction`() {
        listOf(
            { Relationship(sourceId = marinette, targetId = adrien, tension = 120) },
            { Relationship(sourceId = marinette, targetId = adrien, respect = -1) },
            { Relationship(sourceId = marinette, targetId = adrien, fear = 200) },
            { Relationship(sourceId = marinette, targetId = adrien, dependency = 999) },
        ).forEach { build ->
            assertTrue("an out of range axis must fail loudly", runCatching(build).isFailure)
        }
    }

    @Test
    fun `a delta with only tension is not a no-op`() {
        assertFalse(RelationshipDelta(tension = 5).isZero)
        assertTrue(RelationshipDelta.NONE.isZero)
    }

    // ------------------------------------------------------------------
    // Stage
    // ------------------------------------------------------------------

    @Test
    fun `strangers start as strangers`() {
        assertEquals(RelationshipStage.STRANGER, Relationship(marinette, adrien).stage)
    }

    @Test
    fun `interaction advances the stage`() {
        val (acquainted, afterFirst) = relate(RelationshipDelta(familiarity = 25))
        assertEquals(RelationshipStage.ACQUAINTED, acquainted.stage)

        val (settled, _) = relate(RelationshipDelta(familiarity = 30), from = afterFirst)
        assertEquals(
            "a second interaction should settle the relationship",
            RelationshipStage.ESTABLISHED,
            settled.stage,
        )
    }

    @Test
    fun `one event cannot jump more than a single stage`() {
        // A single +100 familiarity event must not teleport a stranger to CLOSE.
        val relation = edge(RelationshipDelta(familiarity = 100))
        assertEquals(
            "stage must advance at most one step per change",
            RelationshipStage.ACQUAINTED,
            relation.stage,
        )
        assertEquals("the familiarity itself is not clamped by the stage rule", 100, relation.familiarity)
    }

    @Test
    fun `stage never goes backwards on its own`() {
        val (acquainted, afterFirst) = relate(RelationshipDelta(familiarity = 25))
        assertEquals(RelationshipStage.ACQUAINTED, acquainted.stage)
        val (settled, afterSecond) = relate(RelationshipDelta(familiarity = 30), from = afterFirst)
        assertEquals(RelationshipStage.ESTABLISHED, settled.stage)

        // A hostile event drives familiarity hard negative. Demotion would need a
        // deliberate event, so the stage must hold rather than silently falling.
        val (after, _) = relate(RelationshipDelta(familiarity = -100, tension = 50), from = afterSecond)
        assertEquals(
            "stage must not silently demote on a familiarity drop; was ${after.stage}",
            RelationshipStage.ESTABLISHED,
            after.stage,
        )
        assertEquals(0, after.familiarity)
    }

    @Test
    fun `the history records why the relationship changed`() {
        val relation = edge(RelationshipDelta(trust = 10, familiarity = 25), note = "she kept the secret")
        val change = relation.history.last()
        assertEquals("she kept the secret", change.reason)
        assertEquals(10, change.delta.trust)
    }

    @Test
    fun `the history records the stage transition`() {
        val relation = edge(RelationshipDelta(familiarity = 25))
        val change = relation.history.last()
        assertEquals(RelationshipStage.STRANGER, change.stageBefore)
        assertEquals(RelationshipStage.ACQUAINTED, change.stageAfter)
        assertTrue("the change moved the relationship a stage", change.advancedStage)
    }

    @Test
    fun `the last interaction is story time, not wall clock time`() {
        val relation = edge(RelationshipDelta(familiarity = 5))
        assertEquals(
            "an interaction must be stamped with the story clock",
            relation.updatedAt,
            relation.lastInteraction,
        )
        // Story time, not an epoch: the story starts on day one at 08:10.
        assertEquals(1, relation.lastInteraction!!.day)
    }

    // ------------------------------------------------------------------
    // Prompt rendering
    // ------------------------------------------------------------------

    @Test
    fun `a plain friendship renders as a plain friendship`() {
        val relation = Relationship(
            sourceId = marinette,
            targetId = adrien,
            relationshipType = RelationshipType.CLOSE_FRIEND,
            trust = 80,
            familiarity = 80,
            affinity = 85,
            stage = RelationshipStage.CLOSE,
        )
        val described = relation.describe(marinette)
        assertTrue(described, described.contains("close"))
        assertFalse(
            "neutral axes must not be narrated: $described",
            described.contains("tension") || described.contains("fear") || described.contains("dependent"),
        )
    }

    @Test
    fun `an axis worth knowing about is named`() {
        val relation = Relationship(
            sourceId = marinette,
            targetId = adrien,
            trust = 30,
            familiarity = 70,
            affinity = 60,
            tension = 70,
            stage = RelationshipStage.ESTABLISHED,
        )
        assertTrue(relation.describe(marinette).contains("unresolved tension"))
    }

    @Test
    fun `known well and distrusted is describable`() {
        // This is the state three numbers cannot express: high familiarity, low trust.
        val relation = Relationship(
            sourceId = marinette,
            targetId = adrien,
            trust = 20,
            familiarity = 75,
            affinity = 55,
        )
        assertTrue(relation.describeBriefly().contains("not trusted"))
    }

    @Test
    fun `respected but not liked is describable`() {
        val relation = Relationship(
            sourceId = marinette,
            targetId = adrien,
            trust = 70,
            familiarity = 60,
            affinity = 20,
            respect = 85,
        )
        assertTrue(relation.describeBriefly().contains("respected but not liked"))
    }

    // ------------------------------------------------------------------
    // The model cannot move any of it by talking
    // ------------------------------------------------------------------

    @Test
    fun `only an event changes a relationship`() {
        val (_, before) = relate(RelationshipDelta(trust = 20, tension = 10))
        val beforeEdge = before.relationships.getValue(RelationshipKey(marinette, andre))

        // The whole point: a string is not a world change. Prose cannot alter an edge.
        val afterProse = before.copy(
            conversation = before.conversation.append(
                dev.charaly.runtime.domain.TranscriptEntry(
                    id = "turn-x",
                    turn = 99,
                    role = dev.charaly.runtime.domain.TranscriptRole.CHARACTER,
                    text = "I think I trust her completely now, and I no longer fear her at all.",
                    at = StoryTime.START,
                ),
            ),
        )
        val after = afterProse.relationships.getValue(RelationshipKey(marinette, andre))
        assertEquals(beforeEdge.trust, after.trust)
        assertEquals(beforeEdge.tension, after.tension)
        assertEquals(beforeEdge.fear, after.fear)
        assertEquals(beforeEdge.dependency, after.dependency)
        assertEquals(beforeEdge.stage, after.stage)
        assertEquals(beforeEdge.history.size, after.history.size)
    }

    @Test
    fun `a relationship with a character that does not exist is refused`() {
        val applied = engine.applyImmediately(
            instance(),
            RelationshipChanged(
                sourceId = marinette,
                targetId = CharacterId("nobody-at-all"),
                delta = RelationshipDelta(trust = 10),
            ),
        )
        assertTrue(applied is dev.charaly.runtime.engine.EventApplication.Rejected)
    }

    @Test
    fun `a relationship with oneself is refused`() {
        val applied = engine.applyImmediately(
            instance(),
            RelationshipChanged(
                sourceId = marinette,
                targetId = marinette,
                delta = RelationshipDelta(trust = 10),
            ),
        )
        assertTrue(applied is dev.charaly.runtime.engine.EventApplication.Rejected)
    }

    @Test
    fun `a delta that changes nothing is refused`() {
        val applied = engine.applyImmediately(
            instance(),
            RelationshipChanged(sourceId = marinette, targetId = andre, delta = RelationshipDelta.NONE),
        )
        assertTrue(applied is dev.charaly.runtime.engine.EventApplication.Rejected)
    }

    @Test
    fun `the stage and the new axes survive persistence`() {
        val relation = edge(RelationshipDelta(familiarity = 25, tension = 30, dependency = 40))
        val codec = JsonCharalyRepository.defaultJson
        val encoded = codec.encodeToString(Relationship.serializer(), relation)
        val decoded = codec.decodeFromString(Relationship.serializer(), encoded)
        assertEquals(relation, decoded)
        assertEquals(RelationshipStage.ACQUAINTED, decoded.stage)
        assertEquals(30, decoded.tension)
        assertEquals(40, decoded.dependency)
        assertNotNull(decoded.lastInteraction)
    }

    @Test
    fun `a save from before the new axes still opens`() {
        // Exactly the JSON an older build would have written: no stage, no extra axes.
        val legacy = """
            {"sourceId":"marinette","targetId":"andre",
             "relationshipType":"FRIEND","trust":70,"familiarity":75,"affinity":74,
             "note":"","history":[],"updatedAt":{"day":1,"hour":8,"minute":10}}
        """.trimIndent()
        val decoded = JsonCharalyRepository.defaultJson
            .decodeFromString(Relationship.serializer(), legacy)

        assertEquals(70, decoded.trust)
        assertEquals("older saves default to the lowest stage, not a crash", RelationshipStage.STRANGER, decoded.stage)
        assertEquals(0, decoded.tension)
        assertNull(decoded.lastInteraction)
    }

    @Test
    fun `a relationship without a stage still opens`() {
        // Older saves have no stage field; the default must be sensible, not a crash.
        val legacy = Relationship(marinette, adrien, trust = 60, familiarity = 40)
        assertEquals(RelationshipStage.STRANGER, legacy.stage)
        assertNotNull(legacy)
        assertNull(legacy.lastInteraction)
    }
}