package dev.charaly.runtime.domain

import dev.charaly.runtime.domain.events.ConsequenceArmed
import dev.charaly.runtime.domain.events.ConsequenceFired
import dev.charaly.runtime.domain.events.GoalUpdated
import dev.charaly.runtime.domain.events.PromiseForgotten
import dev.charaly.runtime.domain.events.PromiseMade
import dev.charaly.runtime.domain.events.PromiseResolved
import dev.charaly.runtime.domain.memory.MemoryTier
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
 * Promises, goals and consequences.
 *
 * The property these all share, and the reason they are not just memories: they have
 * to be *checkable*. A promise has to outlive the keeper forgetting it, a goal has to
 * be able to report that it has not moved in fifty days, and a consequence has to fire
 * at the moment the story earns it rather than whenever it remembers.
 */
class CommitmentTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val engine = EventEngine(definition)

    private val andre = CharacterId("andre")
    private val marinette = CharacterId("marinette")
    private val shop = LocationId("andre-ice-cream")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-commitments"),
            title = "Commitments",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette, andre),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun applied(
        from: StoryInstance = instance(),
        payload: dev.charaly.runtime.domain.events.EventPayload,
    ): StoryInstance {
        val result = engine.applyImmediately(from, payload)
        assertTrue(
            "the event must apply, but was rejected: " +
                ((result as? EventApplication.Rejected)?.validation?.describe() ?: ""),
            result is EventApplication.Applied,
        )
        return (result as EventApplication.Applied).instance
    }

    private fun rejected(
        from: StoryInstance = instance(),
        payload: dev.charaly.runtime.domain.events.EventPayload,
    ) {
        assertTrue(
            "the event must be rejected",
            engine.applyImmediately(from, payload) is EventApplication.Rejected,
        )
    }

    private fun promise(text: String = "I will find out who did it") = PromiseMade(
        promiseId = PromiseId("p-1"),
        text = text,
        keeperId = andre,
        beneficiaryId = marinette,
    )

    // ------------------------------------------------------------------
    // Promises
    // ------------------------------------------------------------------

    @Test
    fun `a promise is recorded and both parties can see it`() {
        val story = applied(payload = promise())
        val ledger = story.worldState.commitments
        assertEquals(1, ledger.promises.size)
        assertEquals("I will find out who did it", ledger.promises.single().text)
        assertEquals(listOf(andre), ledger.openPromisesOf(andre).map { it.keeperId })
        assertEquals(
            "being owed something is a different situation from owing it",
            listOf(andre),
            ledger.promisesOwedTo(marinette).map { it.keeperId },
        )
        assertEquals(
            listOf(marinette),
            ledger.openPromisesInvolving(marinette).map { it.beneficiaryId },
        )
    }

    @Test
    fun `the beneficiary learns they were promised something`() {
        val story = applied(payload = promise())
        val memories = story.memories.of(marinette).filter { it.tier == MemoryTier.PROMISE }
        assertEquals(
            "a promise nobody was told about is a plot hole, not a promise",
            1,
            memories.size,
        )
        assertTrue(memories.single().content.contains("I will find out who did it"))
    }

    @Test
    fun `a promise to yourself is refused`() {
        rejected(
            payload = PromiseMade(
                promiseId = PromiseId("p-self"),
                text = "I will try to be better",
                keeperId = andre,
                beneficiaryId = andre,
            ),
        )
    }

    @Test
    fun `a promise to a character who does not exist is refused`() {
        rejected(
            payload = PromiseMade(
                promiseId = PromiseId("p-ghost"),
                text = "I will help you",
                keeperId = andre,
                beneficiaryId = CharacterId("nobody"),
            ),
        )
    }

    @Test
    fun `a promise with no words is refused`() {
        rejected(payload = promise(text = "  "))
    }

    @Test
    fun `the same promise cannot be recorded twice`() {
        val first = applied(payload = promise())
        rejected(from = first, payload = promise())
    }

    @Test
    fun `a promise is kept exactly once`() {
        val first = applied(payload = promise())
        val kept = applied(
            from = first,
            payload = PromiseResolved(PromiseId("p-1"), CommitmentStatus.KEPT),
        )
        assertEquals(CommitmentStatus.KEPT, kept.worldState.commitments.promise(PromiseId("p-1"))!!.status)
        assertTrue(kept.worldState.commitments.openPromisesOf(andre).isEmpty())
    }

    @Test
    fun `a kept promise cannot then be broken`() {
        // Otherwise the ledger has to show both at once, which is a contradiction the
        // health monitor would have to report for the rest of the story.
        val kept = applied(payload = promise()).let {
            applied(from = it, payload = PromiseResolved(PromiseId("p-1"), CommitmentStatus.KEPT))
        }
        rejected(from = kept, payload = PromiseResolved(PromiseId("p-1"), CommitmentStatus.BROKEN))
    }

    @Test
    fun `both parties are told how a promise ended`() {
        val broken = applied(payload = promise()).let {
            applied(from = it, payload = PromiseResolved(PromiseId("p-1"), CommitmentStatus.BROKEN, note = "he never came"))
        }
        listOf(andre, marinette).forEach { party ->
            assertTrue(
                "$party must know the promise ended",
                broken.memories.of(party).any {
                    it.tier == MemoryTier.PROMISE && it.content.contains("broken")
                },
            )
        }
    }

    @Test
    fun `a witnessed broken promise damages the relationship`() {
        val broken = applied(payload = promise()).let {
            applied(
                from = it,
                payload = PromiseResolved(
                    PromiseId("p-1"),
                    CommitmentStatus.BROKEN,
                    witnessedBy = marinette,
                ),
            )
        }
        val relationship = broken.relationships[RelationshipKey(marinette, andre)]
        assertNotNull("seeing it broken must change how she feels", relationship)
        assertTrue(
            "a witnessed broken promise must cost trust, trust was ${relationship!!.trust}",
            relationship.trust < 50,
        )
        assertTrue("and must leave tension", relationship.tension > 0)
    }

    @Test
    fun `a private broken promise does not damage any relationship`() {
        val broken = applied(payload = promise()).let {
            applied(from = it, payload = PromiseResolved(PromiseId("p-1"), CommitmentStatus.BROKEN))
        }
        val relationship = broken.relationships[RelationshipKey(marinette, andre)]
        assertTrue(
            "nobody witnessed it, so nobody's opinion should change",
            relationship == null || relationship.trust >= 50,
        )
    }

    @Test
    fun `a keeper can forget a promise, and the world still holds it`() {
        val forgotten = applied(payload = promise()).let {
            applied(from = it, payload = PromiseForgotten(PromiseId("p-1")))
        }
        val promise = forgotten.worldState.commitments.promise(PromiseId("p-1"))!!
        assertFalse(promise.rememberedByKeeper)
        assertEquals(
            "forgetting is what the keeper lost, not what the world lost",
            CommitmentStatus.OPEN,
            promise.status,
        )
        assertEquals(
            "and it must still be reportable, because a broken promise is held against you",
            1,
            forgotten.worldState.commitments.openPromisesOf(andre).size,
        )
    }

    @Test
    fun `forgetting twice is a no-op`() {
        val forgotten = applied(payload = promise()).let {
            applied(from = it, payload = PromiseForgotten(PromiseId("p-1")))
        }
        val again = engine.applyImmediately(forgotten, PromiseForgotten(PromiseId("p-1")))
        assertTrue((again as EventApplication.Applied).noOp)
    }

    @Test
    fun `a promise without a deadline is never overdue`() {
        val promise = Promise(
            id = PromiseId("p-2"),
            text = "sometime",
            keeperId = andre,
            beneficiaryId = marinette,
        )
        assertFalse(promise.hasDeadline)
        assertFalse(
            "treating 'no deadline' as 'overdue' breaks every promise on the first tick",
            promise.isOverdueAt(StoryTime.of(day = 99, hour = 23, minute = 0)),
        )
    }

    @Test
    fun `a promise with a deadline goes overdue only after it`() {
        val due = StoryTime.of(day = 1, hour = 18, minute = 0)
        val promise = Promise(
            id = PromiseId("p-3"),
            text = "by closing",
            keeperId = andre,
            beneficiaryId = marinette,
            dueOnDay = due.day,
            dueAtMinuteOfDay = due.hour * 60 + due.minute,
        )
        assertTrue(promise.hasDeadline)
        assertFalse(promise.isOverdueAt(due))
        assertTrue(promise.isOverdueAt(StoryTime.of(day = 1, hour = 19, minute = 0)))
    }

    @Test
    fun `a broken promise with a deadline stops being overdue`() {
        // Otherwise it becomes more overdue forever, which is how a closed thread turns
        // into a permanent warning in the health monitor.
        val promise = Promise(
            id = PromiseId("p-4"),
            text = "by closing",
            keeperId = andre,
            beneficiaryId = marinette,
            dueOnDay = 1,
            dueAtMinuteOfDay = 18 * 60,
        ).resolve(CommitmentStatus.BROKEN, StoryTime.of(day = 1, hour = 19, minute = 0))
        assertFalse(promise.isOverdueAt(StoryTime.of(day = 30, hour = 12, minute = 0)))
    }

    // ------------------------------------------------------------------
    // Goals
    // ------------------------------------------------------------------

    @Test
    fun `a goal records progress`() {
        val story = applied(
            payload = GoalUpdated(
                goalId = GoalId("g-1"),
                ownerId = andre,
                text = "find out who emptied the museum wing",
                progressDelta = 30,
            ),
        )
        val goal = story.worldState.commitments.goal(GoalId("g-1"))!!
        assertEquals(30, goal.progress)
        assertEquals(CommitmentStatus.OPEN, goal.status)
    }

    @Test
    fun `goal progress is clamped`() {
        val story = applied(
            payload = GoalUpdated(
                goalId = GoalId("g-1"),
                ownerId = andre,
                text = "find out who emptied the museum wing",
                progressDelta = 500,
            ),
        )
        assertEquals(100, story.worldState.commitments.goal(GoalId("g-1"))!!.progress)
    }

    @Test
    fun `a goal accumulates progress across events`() {
        val story = instance().let {
            val first = applied(
                from = it,
                payload = GoalUpdated(GoalId("g-1"), andre, "find out who emptied the museum wing", progressDelta = 30),
            )
            applied(
                from = first,
                payload = GoalUpdated(GoalId("g-1"), andre, progressDelta = 20),
            )
        }
        assertEquals(50, story.worldState.commitments.goal(GoalId("g-1"))!!.progress)
    }

    @Test
    fun `a goal can be completed`() {
        val story = instance().let {
            val started = applied(
                from = it,
                payload = GoalUpdated(GoalId("g-1"), andre, "find out who emptied the museum wing", progressDelta = 30),
            )
            applied(
                from = started,
                payload = GoalUpdated(GoalId("g-1"), andre, progressDelta = 70, status = CommitmentStatus.KEPT),
            )
        }
        val goal = story.worldState.commitments.goal(GoalId("g-1"))!!
        assertEquals(100, goal.progress)
        assertTrue(goal.status.isResolved)
    }

    @Test
    fun `a completed goal cannot be advanced`() {
        val done = instance().let {
            val started = applied(
                from = it,
                payload = GoalUpdated(GoalId("g-1"), andre, "find out who emptied the museum wing", progressDelta = 100, status = CommitmentStatus.KEPT),
            )
            started
        }
        rejected(from = done, payload = GoalUpdated(GoalId("g-1"), andre, progressDelta = 10))
    }

    @Test
    fun `a goal with no text and no existing record is refused`() {
        rejected(payload = GoalUpdated(GoalId("g-new"), andre))
    }

    @Test
    fun `a goal's owner alone can see their progress`() {
        val story = applied(
            payload = GoalUpdated(GoalId("g-1"), andre, "find out who emptied the museum wing", progressDelta = 30),
        )
        assertTrue(story.memories.of(andre).any { it.tier == MemoryTier.GOAL })
        assertFalse(
            "a stranger knowing your progress is not information",
            story.memories.of(marinette).any { it.tier == MemoryTier.GOAL },
        )
    }

    @Test
    fun `a stalled goal is detectable`() {
        val goal = Goal(
            id = GoalId("g-1"),
            text = "find out who emptied the museum wing",
            ownerId = andre,
            updatedAt = StoryTime.of(day = 1, hour = 8, minute = 0),
        )
        assertTrue(goal.isStalledAt(StoryTime.of(day = 20, hour = 8, minute = 0), afterMinutes = 60))
        assertFalse("an hour is not a stall", goal.isStalledAt(StoryTime.of(day = 1, hour = 9, minute = 0), afterMinutes = 120))
    }

    @Test
    fun `an ambient goal is never reported as stalled`() {
        // A shopkeeper's "keep the shop open" is not a stalled goal; it is what they do.
        val goal = Goal(
            id = GoalId("g-2"),
            text = "keep the shop open",
            ownerId = andre,
            isAmbient = true,
            updatedAt = StoryTime.of(day = 1, hour = 8, minute = 0),
        )
        assertFalse(goal.isStalledAt(StoryTime.of(day = 90, hour = 8, minute = 0), afterMinutes = 60))
    }

    // ------------------------------------------------------------------
    // Consequences
    // ------------------------------------------------------------------

    @Test
    fun `a consequence is armed and then fires`() {
        val armed = applied(
            payload = ConsequenceArmed(
                consequenceId = ConsequenceId("c-1"),
                decision = "you told Gabriel about the museum",
                decidedBy = marinette,
                outcome = "the east wing is watched by his people",
                delayMinutes = 120,
            ),
        )
        val pending = armed.worldState.commitments.consequence(ConsequenceId("c-1"))!!
        assertTrue(pending.isPending)
        assertFalse("not yet", pending.isDueAt(StoryTime.of(day = 1, hour = 8, minute = 30)))
        assertTrue("later", pending.isDueAt(StoryTime.of(day = 1, hour = 12, minute = 0)))

        val fired = applied(from = armed, payload = ConsequenceFired(ConsequenceId("c-1")))
        assertTrue(fired.worldState.commitments.consequence(ConsequenceId("c-1"))!!.resolved)
    }

    @Test
    fun `whoever decided a consequence finds out it landed`() {
        val story = instance().let {
            val armed = applied(
                from = it,
                payload = ConsequenceArmed(
                    ConsequenceId("c-1"),
                    decision = "you told Gabriel about the museum",
                    decidedBy = marinette,
                    outcome = "the east wing is watched",
                ),
            )
            applied(from = armed, payload = ConsequenceFired(ConsequenceId("c-1")))
        }
        val memory = story.memories.of(marinette).firstOrNull { it.tier == MemoryTier.CONSEQUENCE }
        assertNotNull("a choice with no aftermath feels like it had no weight", memory)
        assertEquals(5, memory!!.importance)
    }

    @Test
    fun `a consequence cannot fire twice`() {
        val fired = instance().let {
            val armed = applied(
                from = it,
                payload = ConsequenceArmed(
                    ConsequenceId("c-1"),
                    decision = "you told Gabriel",
                    decidedBy = marinette,
                    outcome = "the wing is watched",
                ),
            )
            applied(from = armed, payload = ConsequenceFired(ConsequenceId("c-1")))
        }
        val again = engine.applyImmediately(fired, ConsequenceFired(ConsequenceId("c-1")))
        assertTrue("a consequence that lands twice is a bug a player reports", (again as EventApplication.Applied).noOp)
    }

    @Test
    fun `a consequence blocked by an absent character is reported as blocked`() {
        val armed = applied(
            payload = ConsequenceArmed(
                consequenceId = ConsequenceId("c-2"),
                decision = "you left the brooch on the bench",
                decidedBy = marinette,
                outcome = "someone else picks it up",
                delayMinutes = 0,
                requiresPresenceOf = andre,
                requiresLocationId = shop,
            ),
        )
        val consequence = armed.worldState.commitments.consequence(ConsequenceId("c-2"))!!
        val elsewhere = armed.evolved(
            worldState = armed.worldState.withCharacter(
                armed.characters.getValue(andre).copy(locationId = LocationId("city-streets")),
            ),
        )
        assertTrue(
            "waiting on a character is a different problem from waiting on the clock",
            consequence.isBlockedBy(elsewhere),
        )
        assertFalse(
            "Andre starts the story in his shop, so nothing is blocked yet",
            consequence.isBlockedBy(
                armed.evolved(
                    worldState = armed.worldState.withCharacter(
                        armed.characters.getValue(andre).copy(locationId = shop),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `an empty consequence is refused`() {
        rejected(
            payload = ConsequenceArmed(
                ConsequenceId("c-3"),
                decision = "  ",
                decidedBy = marinette,
                outcome = "something",
            ),
        )
    }

    @Test
    fun `a consequence in the past is refused`() {
        rejected(
            payload = ConsequenceArmed(
                ConsequenceId("c-3"),
                decision = "you did it",
                decidedBy = marinette,
                outcome = "something",
                delayMinutes = -5,
            ),
        )
    }

    // ------------------------------------------------------------------
    // The ledger
    // ------------------------------------------------------------------

    @Test
    fun `commitments of one character list only theirs`() {
        val story = instance().let {
            val withPromise = applied(from = it, payload = promise())
            applied(
                from = withPromise,
                payload = GoalUpdated(GoalId("g-1"), andre, "find out who emptied the museum wing", progressDelta = 10),
            )
        }
        val andreLines = story.worldState.commitments.commitmentsOf(andre)
        assertTrue(andreLines.any { it.contains("I will find out who did it") })
        assertTrue(andreLines.any { it.contains("find out who emptied the museum wing") })
        assertTrue(
            "Marinette owes nothing here",
            story.worldState.commitments.commitmentsOf(marinette).none { it.contains("did it") },
        )
    }

    @Test
    fun `the ledger survives persistence`() {
        val story = applied(payload = promise())
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val encoded = codec.encodeToString(StoryInstance.serializer(), story)
        val decoded = codec.decodeFromString(StoryInstance.serializer(), encoded)
        assertEquals(
            story.worldState.commitments.promises,
            decoded.worldState.commitments.promises,
        )
    }

    @Test
    fun `an empty promise is refused at construction`() {
        assertTrue(runCatching { Promise(PromiseId("x"), "  ", andre, marinette) }.isFailure)
    }

    @Test
    fun `a promise to yourself is refused at construction`() {
        assertTrue(runCatching { Promise(PromiseId("x"), "text", andre, andre) }.isFailure)
    }

    @Test
    fun `a goal with out of range progress is refused at construction`() {
        assertTrue(runCatching { Goal(GoalId("x"), "text", andre, progress = 200) }.isFailure)
    }

    @Test
    fun `resolving to open is refused at construction`() {
        val promise = Promise(PromiseId("x"), "text", andre, marinette)
        assertTrue(
            "resolving a promise to OPEN is not resolving it",
            runCatching { promise.resolve(CommitmentStatus.OPEN, StoryTime.START) }.isFailure,
        )
    }

    @Test
    fun `a consequence with no decision is refused at construction`() {
        assertTrue(
            runCatching {
                Consequence(
                    id = ConsequenceId("x"),
                    decision = "  ",
                    decidedBy = andre,
                    outcome = "something",
                )
            }.isFailure,
        )
    }

    @Test
    fun `resolving a promise that does not exist is refused`() {
        rejected(payload = PromiseResolved(PromiseId("p-nope"), CommitmentStatus.KEPT))
    }

    @Test
    fun `firing a consequence that does not exist is refused`() {
        rejected(payload = ConsequenceFired(ConsequenceId("c-nope")))
    }
}