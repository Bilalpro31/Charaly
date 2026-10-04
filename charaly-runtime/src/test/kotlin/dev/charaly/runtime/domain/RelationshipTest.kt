package dev.charaly.runtime.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Relationships are authoritative, persistent world state. They must never live
 * only inside a prompt.
 */
class RelationshipTest {

    private val a = CharacterId("alice")
    private val b = CharacterId("bob")

    @Test
    fun `a relationship needs two distinct characters`() {
        assertTrue(runCatching { Relationship(a, a) }.isFailure)
    }

    @Test
    fun `values are range checked at construction`() {
        assertTrue(runCatching { Relationship(a, b, trust = 101) }.isFailure)
        assertTrue(runCatching { Relationship(a, b, affinity = -1) }.isFailure)
        assertTrue(runCatching { Relationship(a, b, familiarity = 500) }.isFailure)
    }

    @Test
    fun `applying a delta clamps and records history`() {
        val rel = Relationship(a, b)
        val updated = rel.apply(
            RelationshipDelta(trust = 10, affinity = -100),
            RelationshipType.RIVAL,
            "an argument",
            StoryTime(1, 12, 0),
        )
        assertEquals(60, updated.trust)
        assertEquals("clamped at zero", 0, updated.affinity)
        assertEquals(RelationshipType.RIVAL, updated.relationshipType)
        assertEquals(1, updated.history.size)
        assertEquals(StoryTime(1, 12, 0), updated.updatedAt)
    }

    @Test
    fun `history accumulates across changes`() {
        var rel = Relationship(a, b)
        rel = rel.apply(RelationshipDelta(1), at = StoryTime(1, 0, 0))
        rel = rel.apply(RelationshipDelta(1), at = StoryTime(1, 1, 0))
        rel = rel.apply(RelationshipDelta(1), at = StoryTime(1, 2, 0))
        assertEquals(3, rel.history.size)
        assertEquals("each entry keeps its own time", 3, rel.history.map { it.at.hour }.distinct().size)
    }

    @Test
    fun `key is directional`() {
        assertEquals(RelationshipKey(a, b), Relationship(a, b).key())
        assertFalse(Relationship(a, b).key() == Relationship(b, a).key())
    }

    @Test
    fun `describe reflects the observer's perspective`() {
        val rel = Relationship(a, b, relationshipType = RelationshipType.MENTOR)
        assertTrue(rel.describe(fromPerspectiveOf = a).contains("a mentor"))
        assertTrue(rel.describe(fromPerspectiveOf = b).contains("a mentee"))
        assertTrue(rel.describe(fromPerspectiveOf = a).contains("trust 50/100"))
    }

    @Test
    fun `type categories are available for the context builder`() {
        assertTrue(RelationshipType.ENEMY.isHostile)
        assertTrue(RelationshipType.RIVAL.isHostile)
        assertTrue(RelationshipType.FRIEND.isFriendly)
        assertTrue(RelationshipType.PARTNER.isFriendly)
        assertFalse(RelationshipType.STRANGER.isFriendly)
    }

    @Test
    fun `zero delta is recognised`() {
        assertTrue(RelationshipDelta.NONE.isZero)
        assertFalse(RelationshipDelta(familiarity = 1).isZero)
    }

    @Test
    fun `default relationship is a stranger with neutral scores`() {
        val rel = Relationship(a, b)
        assertEquals(RelationshipType.UNKNOWN, rel.relationshipType)
        assertEquals(50, rel.trust)
        assertEquals(0, rel.familiarity)
        assertEquals(50, rel.affinity)
    }
}
