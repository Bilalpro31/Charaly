package dev.charaly.runtime.domain.knowledge

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WORLD TRUTH vs CHARACTER KNOWLEDGE.
 *
 * The reference scenario: the key is inside the library. Alice does not know.
 * Bob knows. This suite exists to prove that separation cannot leak.
 */
class KnowledgeTest {

    private val alice = CharacterId("alice")
    private val bob = CharacterId("bob")
    private val keyFact = Fact(
        id = FactId("key"),
        subject = "key",
        predicate = "is in",
        description = "The key is inside the library.",
        locationId = LocationId("library"),
        secret = true,
    )
    private val lampFact = Fact(
        id = FactId("lamps"),
        description = "The lamps go out at the same hour every night.",
    )

    private val store: KnowledgeStore = KnowledgeStore.EMPTY
        .withFacts(listOf(keyFact, lampFact))
        .withKnowledge(bob, listOf(KnowledgeEntry(FactId("key"), StoryTime(1, 8, 0), via = "saw it himself")))
        .withKnowledge(alice, listOf(KnowledgeEntry(FactId("lamps"), StoryTime(1, 9, 0))))

    @Test
    fun `world truth holds the fact regardless of who knows it`() {
        assertEquals(2, store.factCount)
        assertNotNull(store.fact(FactId("key")))
        assertEquals("The key is inside the library.", store.fact(FactId("key"))!!.render())
    }

    @Test
    fun `alice does not know the key even though it is true`() {
        assertFalse(store.knows(alice, FactId("key")))
        assertEquals(listOf("lamps"), store.visibleTo(alice).map { it.id.value })
    }

    @Test
    fun `bob does know the key`() {
        assertTrue(store.knows(bob, FactId("key")))
        assertEquals(listOf("key"), store.visibleTo(bob).map { it.id.value })
    }

    @Test
    fun `visible facts never include unknown ones`() {
        store.visibleTo(alice).forEach { fact ->
            assertTrue("leaked ${fact.id}", store.knows(alice, fact.id))
        }
    }

    @Test
    fun `a secret fact is still ordinary truth`() {
        assertTrue(store.fact(FactId("key"))!!.secret)
        assertTrue("secrecy must not remove it from truth", store.factCount == 2)
    }

    @Test
    fun `knowledge entries carry provenance`() {
        val entry = store.entry(bob, FactId("key"))!!
        assertEquals("saw it himself", entry.via)
        assertEquals(StoryTime(1, 8, 0), entry.learnedAt)
        assertEquals(100, entry.confidence)
        assertNull(store.entry(alice, FactId("key")))
    }

    @Test
    fun `learning the same fact twice updates rather than duplicates`() {
        val twice = store
            .withKnowledge(alice, listOf(KnowledgeEntry(FactId("lamps"), StoryTime(1, 10, 0), confidence = 60)))
        assertEquals(1, twice.knowledge.getValue(alice).size)
        assertEquals("later knowledge wins", 60, twice.entry(alice, FactId("lamps"))!!.confidence)
    }

    @Test
    fun `forgetting removes the fact from view`() {
        val forgotten = store.forget(alice, FactId("lamps"))
        assertFalse(forgotten.knows(alice, FactId("lamps")))
        assertTrue("truth must survive forgetting", forgotten.factCount == 2)
        assertTrue(forgotten.visibleTo(alice).isEmpty())
    }

    @Test
    fun `knownBy lists every character who knows a fact`() {
        assertEquals(listOf(bob), store.knownBy(FactId("key")))
        assertEquals(listOf(alice), store.knownBy(FactId("lamps")))
    }

    @Test
    fun `visibility is ordered deterministically by learning time`() {
        val ordered = KnowledgeStore.EMPTY
            .withFact(keyFact)
            .withKnowledge(alice, listOf(KnowledgeEntry(FactId("key"), StoryTime(1, 12, 0))))
            .withKnowledge(alice, listOf(KnowledgeEntry(FactId("key"), StoryTime(1, 6, 0))))
        assertEquals(1, ordered.knowledge.getValue(alice).size)
        assertEquals(StoryTime(1, 6, 0), ordered.entry(alice, FactId("key"))!!.learnedAt)
    }

    @Test
    fun `a fact needs content`() {
        assertTrue(runCatching { Fact(id = FactId("blank")) }.isFailure)
    }

    @Test
    fun `an entry for an unknown fact resolves to nothing`() {
        // The store is deliberately dumb; the EventEngine is what enforces that
        // a character can only learn a fact that exists in world truth.
        val bogus = store.withKnowledge(alice, listOf(KnowledgeEntry(FactId("nope"), StoryTime.START)))
        assertEquals("the dangling reference is kept", 2, bogus.knowledge.getValue(alice).size)
        assertTrue(
            "and it can never leak into a prompt",
            bogus.visibleTo(alice).none { it.id.value == "nope" },
        )
    }

    @Test
    fun `subject and predicate render when there is no description`() {
        val fact = Fact(id = FactId("sp"), subject = "Bob", predicate = "is missing a key", description = "")
        assertEquals("Bob is missing a key", fact.render())
    }
}
