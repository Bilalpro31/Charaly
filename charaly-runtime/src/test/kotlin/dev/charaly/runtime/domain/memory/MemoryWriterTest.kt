package dev.charaly.runtime.domain.memory

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Every message is a memory" is the failure mode this file exists to prevent.
 *
 * The pipeline has to reject trivial lines, refuse memories nobody is allowed to hold,
 * retire what a new claim contradicts rather than deleting it, and stay byte-for-byte
 * reproducible so a replayed story remembers the same things.
 */
class MemoryWriterTest {

    private val andre = CharacterId("andre")
    private val marinette = CharacterId("marinette")
    private val now = StoryTime.of(day = 1, hour = 12, minute = 0)

    private fun candidate(
        content: String,
        owner: CharacterId = andre,
        tier: MemoryTier = MemoryTier.EPISODIC,
        subject: String = "",
        predicate: String = "",
        visibility: MemoryVisibility = MemoryVisibility.CHARACTER,
        visibleTo: List<CharacterId> = emptyList(),
    ) = MemoryWriter.Candidate(
        ownerId = owner,
        content = content,
        tier = tier,
        subject = subject,
        predicate = predicate,
        visibility = visibility,
        visibleTo = visibleTo,
    )

    // ------------------------------------------------------------------
    // Importance calibration
    // ------------------------------------------------------------------

    @Test
    fun `greetings and weather are small talk`() {
        listOf(
            "Hello there, lovely morning, isn't it?",
            "Hi!",
            "Good morning. The sky is grey today.",
            "Thanks, bye!",
        ).forEach { line ->
            val score = MemoryWriter.importanceOf(line, MemoryTier.WORKING)
            assertTrue("'$line' scored $score and should read as small talk", score < MemoryWriter.SMALL_TALK_THRESHOLD)
        }
    }

    @Test
    fun `a disclosure scores far above small talk`() {
        val confession = "I saw the akuma take the brooch from the museum, and I know who sent it"
        assertTrue(
            MemoryWriter.importanceOf(confession, MemoryTier.EPISODIC) >= MemoryWriter.SMALL_TALK_THRESHOLD,
        )
    }

    @Test
    fun `a promise scores as a promise`() {
        val promise = "I promise I will tell her, whatever it costs me"
        assertTrue(
            "a commitment must be memorable",
            MemoryWriter.importanceOf(promise, MemoryTier.EPISODIC) >= 3,
        )
    }

    @Test
    fun `importance is clamped and blank content scores nothing`() {
        val huge = "I promise. ".repeat(80)
        val score = MemoryWriter.importanceOf(huge, MemoryTier.CANON)
        assertTrue(score in 1..MemoryWriter.MAX_IMPORTANCE)
        // The raw reading is what reports "there is nothing here"; the tiered reading
        // reports a stored weight, which a floor always supplies.
        assertEquals(0, MemoryWriter.rawImportance("   "))
        assertTrue(MemoryWriter.isTrivial(MemoryWriter.Candidate(andre, "   ")))
    }

    @Test
    fun `the tier sets a floor, so a short canon line still matters`() {
        assertTrue(
            MemoryWriter.importanceOf("The museum wing is shut.", MemoryTier.CANON) >= 4,
        )
        assertEquals(1, MemoryWriter.importanceOf("The museum wing is shut.", MemoryTier.WORKING))
    }

    @Test
    fun `scoring is deterministic`() {
        val line = "I found the hidden key behind the shutter, and I know it was hidden on purpose"
        assertEquals(MemoryWriter.importanceOf(line, MemoryTier.EPISODIC), MemoryWriter.importanceOf(line, MemoryTier.EPISODIC))
    }

    // ------------------------------------------------------------------
    // The gates
    // ------------------------------------------------------------------

    @Test
    fun `small talk is written as no memory at all`() {
        val result = MemoryWriter.write(
            store = MemoryStore.EMPTY,
            candidates = listOf(candidate("Hi!", tier = MemoryTier.WORKING), candidate("Hello!", tier = MemoryTier.WORKING)),
            now = now,
        )
        assertEquals(0, result.createdCount)
        assertEquals(0, result.store.size)
        assertTrue(result.rejected.all { it.rejection == MemoryWriter.Rejection.TRIVIAL })
    }

    @Test
    fun `an important line is remembered`() {
        val result = MemoryWriter.write(
            store = MemoryStore.EMPTY,
            candidates = listOf(candidate("I found the akuma hiding in the museum, and I know who sent it")),
            now = now,
        )
        assertEquals(1, result.createdCount)
        assertEquals(1, result.store.size)
    }

    @Test
    fun `a memory for a character who does not exist is refused`() {
        val result = MemoryWriter.write(
            store = MemoryStore.EMPTY,
            candidates = listOf(candidate("I know everything about the akuma", owner = CharacterId("nobody"))),
            now = now,
            isKnownCharacter = { it != CharacterId("nobody") },
        )
        assertEquals(0, result.createdCount)
        assertEquals(MemoryWriter.Rejection.UNKNOWN_OWNER, result.rejected.single().rejection)
    }

    @Test
    fun `a memory a character may not hold is refused rather than stored quietly`() {
        val result = MemoryWriter.write(
            store = MemoryStore.EMPTY,
            candidates = listOf(
                candidate("I know Andre's real name", owner = marinette, visibility = MemoryVisibility.PRIVATE),
            ),
            now = now,
        )
        assertEquals(0, result.createdCount)
        assertEquals(MemoryWriter.Rejection.FORBIDDEN, result.rejected.single().rejection)
    }

    @Test
    fun `the same thing said twice is stored once`() {
        val line = "The museum closed early on Tuesday because of the storm"
        val first = MemoryWriter.write(MemoryStore.EMPTY, listOf(candidate(line)), now = now)
        val second = MemoryWriter.write(first.store, listOf(candidate(line)), now = now.plusMinutes(5))
        assertEquals(1, first.createdCount)
        assertEquals(0, second.createdCount)
        assertEquals(MemoryWriter.Rejection.DUPLICATE, second.rejected.single().rejection)
        assertEquals(1, second.store.size)
    }

    @Test
    fun `a memory that world truth already holds is not stored again`() {
        val line = "The museum wing was closed for repairs after the storm"
        val result = MemoryWriter.write(
            store = MemoryStore.EMPTY,
            candidates = listOf(
                candidate(line, tier = MemoryTier.EPISODIC, subject = "museum wing", predicate = "state"),
            ),
            now = now,
            canonFacts = listOf(line),
        )
        assertEquals(0, result.createdCount)
        assertEquals(MemoryWriter.Rejection.REDUNDANT_WITH_CANON, result.rejected.single().rejection)
    }

    @Test
    fun `canon itself is never suppressed by world truth`() {
        val line = "The museum wing was closed for repairs after the storm"
        val result = MemoryWriter.write(
            store = MemoryStore.EMPTY,
            candidates = listOf(
                candidate(line, tier = MemoryTier.CANON, subject = "museum wing", predicate = "state"),
            ),
            now = now,
            canonFacts = listOf(line),
        )
        assertEquals(1, result.createdCount)
    }

    // ------------------------------------------------------------------
    // Contradiction handling
    // ------------------------------------------------------------------

    @Test
    fun `a new claim supersedes the old one instead of deleting it`() {
        val first = MemoryWriter.write(
            MemoryStore.EMPTY,
            listOf(candidate("The museum is open on Sunday", subject = "museum", predicate = "opens")),
            now = now,
        )
        val later = now.plusMinutes(120)
        val second = MemoryWriter.write(
            first.store,
            listOf(candidate("The museum is closed on Sunday", subject = "museum", predicate = "opens")),
            now = later,
        )

        assertEquals(1, second.createdCount)
        val old = first.store.all().first()
        val retained = second.store.byId(old.id)
        assertNotNull("the superseded memory must be kept as history", retained)
        assertFalse(retained!!.isCurrent)
        assertEquals(second.created.single().id, retained.supersededBy)
        assertEquals(later, retained.validUntil)

        // And the new memory records what it replaced, for the inspector.
        assertEquals(listOf(old.id), second.created.single().supersedes)
    }

    @Test
    fun `contradictions only resolve within one owner`() {
        val andreMemory = MemoryWriter.write(
            MemoryStore.EMPTY,
            listOf(candidate("The museum is open on Sunday", owner = andre, subject = "museum", predicate = "opens")),
            now = now,
        )
        val marinetteMemory = MemoryWriter.write(
            andreMemory.store,
            listOf(candidate("The museum is closed on Sunday", owner = marinette, subject = "museum", predicate = "opens")),
            now = now.plusMinutes(60),
        )
        assertEquals(2, marinetteMemory.store.current().count { it.subject == "museum" })
    }

    @Test
    fun `a memory with no claim cannot contradict anything`() {
        val first = MemoryWriter.write(
            MemoryStore.EMPTY,
            listOf(candidate("The sky is grey and I found the shutters closed")),
            now = now,
        )
        val second = MemoryWriter.write(
            first.store,
            listOf(candidate("The shop was busy and I remember the queue out the door")),
            now = now.plusMinutes(60),
        )
        assertEquals(2, second.store.current().size)
    }

    @Test
    fun `a declarative claim is remembered even though it is not first person`() {
        // "The museum is open on Sunday" carries none of the conversational signals the
        // importance score counts, so it must be admitted on the strength of its label.
        val result = MemoryWriter.write(
            MemoryStore.EMPTY,
            listOf(candidate("The museum is open on Sunday", subject = "museum", predicate = "opens")),
            now = now,
        )
        assertEquals(1, result.createdCount)
    }

    @Test
    fun `an unlabelled line with no signal is still discarded`() {
        val result = MemoryWriter.write(
            MemoryStore.EMPTY,
            listOf(candidate("Yes, that one over there, quite nice")),
            now = now,
        )
        assertEquals(0, result.createdCount)
        assertEquals(MemoryWriter.Rejection.TRIVIAL, result.rejected.single().rejection)
    }

    // ------------------------------------------------------------------
    // Visibility is preserved, not re-decided
    // ------------------------------------------------------------------

    @Test
    fun `a secret written through the pipeline stays secret`() {
        val result = MemoryWriter.write(
            store = MemoryStore.EMPTY,
            candidates = listOf(
                candidate(
                    "I know Ladybug is Marinette and I will never say it aloud",
                    owner = andre,
                    tier = MemoryTier.SECRET,
                    visibility = MemoryVisibility.SECRET,
                ),
            ),
            now = now,
        )
        val memory = result.created.single()
        assertFalse("a secret must never be readable by a bystander", memory.visibleTo(MemorySubject.Character(marinette)))
        assertTrue(memory.visibleTo(MemorySubject.Character(andre)))
    }

    @Test
    fun `the written memory keeps its source for explainability`() {
        val result = MemoryWriter.write(
            MemoryStore.EMPTY,
            candidates = listOf(
                candidate("I saw the akuma in the museum").copy(sourceMessageId = "turn-7"),
            ),
            now = now,
        )
        assertEquals("turn-7", result.created.single().sourceMessageId)
        assertEquals(now, result.created.single().createdAt)
        assertEquals(now, result.created.single().validFrom)
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    fun `the same input always produces the same store`() {
        val candidates = listOf(
            candidate("I found the akuma in the museum and I know who sent it"),
            candidate("Hi!"),
            candidate("The museum is open on Sunday", subject = "museum", predicate = "opens"),
        )
        val a = MemoryWriter.write(MemoryStore.EMPTY, candidates, now = now)
        val b = MemoryWriter.write(MemoryStore.EMPTY, candidates, now = now)
        assertEquals(a.created.map { it.id }, b.created.map { it.id })
        assertEquals(a.store.all().map { it.content to it.importance }, b.store.all().map { it.content to it.importance })
    }

    @Test
    fun `ids are unique within one write`() {
        val result = MemoryWriter.write(
            MemoryStore.EMPTY,
            listOf(
                candidate("I saw the akuma take the brooch", subject = "brooch", predicate = "taken"),
                candidate("I remember the brooch was already missing", subject = "brooch", predicate = "taken"),
                candidate("I know the akuma came from the museum"),
            ),
            now = now,
        )
        assertEquals(result.created.size, result.created.map { it.id }.toSet().size)
    }

    // ------------------------------------------------------------------
    // Tier semantics
    // ------------------------------------------------------------------

    @Test
    fun `only canon and world are authoritative`() {
        assertFalse(MemoryTier.CANON.isSubjective)
        assertFalse(MemoryTier.WORLD.isSubjective)
        assertTrue(MemoryTier.EPISODIC.isSubjective)
        assertTrue(MemoryTier.SEMANTIC.isSubjective)
        assertTrue(MemoryTier.CHARACTER.isSubjective)
        assertTrue(MemoryTier.SECRET.isSubjective)
    }

    @Test
    fun `durable tiers survive pruning`() {
        listOf(MemoryTier.CANON, MemoryTier.RELATIONSHIP, MemoryTier.CHARACTER, MemoryTier.THREAD, MemoryTier.PROMISE, MemoryTier.GOAL)
            .forEach { assertTrue("$it must be durable", it.isDurable) }
        listOf(MemoryTier.WORKING, MemoryTier.SCENE, MemoryTier.EPISODIC, MemoryTier.SEMANTIC, MemoryTier.WORLD, MemoryTier.SECRET, MemoryTier.CONSEQUENCE)
            .forEach { assertFalse("$it must not be durable", it.isDurable) }
    }

    @Test
    fun `confidence is validated`() {
        val bad = runCatching {
            Memory(id = MemoryId("m"), characterId = andre, content = "x", confidence = 140)
        }
        assertTrue("confidence out of range must fail loudly", bad.isFailure)
    }

    @Test
    fun `confidence is independent of importance`() {
        val tentative = MemoryWriter.write(
            MemoryStore.EMPTY,
            listOf(candidate("I think I saw the akuma, maybe", tier = MemoryTier.SEMANTIC).copy(confidence = 40)),
            now = now,
        ).created.single()
        assertEquals(40, tentative.confidence)
        assertTrue("importance and confidence are separate claims", tentative.importance >= 3)
    }
}