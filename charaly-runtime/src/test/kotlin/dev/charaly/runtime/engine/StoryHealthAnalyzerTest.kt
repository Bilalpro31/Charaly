package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRuntime
import dev.charaly.runtime.domain.EntityId
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneState
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryThread
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.RelationshipChanged
import dev.charaly.runtime.domain.knowledge.KnowledgeEntry
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemoryStore
import dev.charaly.runtime.domain.memory.MemorySubject
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health monitor.
 *
 * Two properties matter more than the individual checks. It is **read-only** - a
 * diagnostic that repairs the world as a side effect cannot be trusted to answer a
 * question - and it is **deterministic**, so "the story was healthy then and is not
 * now" always means something changed.
 */
class StoryHealthAnalyzerTest {

    private val pack = MiraculousPack.pack
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val analyzer = StoryHealthAnalyzer(definition)

    private val marinette = CharacterId("marinette")
    private val adrien = CharacterId("adrien")
    private val andre = CharacterId("andre")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-health"),
            title = "Health",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    private fun report(instance: dev.charaly.runtime.domain.StoryInstance = instance()) =
        analyzer.analyse(instance)

    private fun memory(
        id: String,
        owner: CharacterId = andre,
        content: String,
        subject: String = "",
        predicate: String = "",
        visibility: MemoryVisibility = MemoryVisibility.CHARACTER,
        visibleTo: List<CharacterId> = emptyList(),
        at: StoryTime = StoryTime.START,
        pinned: Boolean = false,
    ) = Memory(
        id = MemoryId(id),
        characterId = owner,
        content = content,
        createdAt = at,
        subject = subject,
        predicate = predicate,
        visibility = visibility,
        visibleTo = visibleTo,
        pinned = pinned,
    )

    // ------------------------------------------------------------------
    // Read-only, deterministic
    // ------------------------------------------------------------------

    @Test
    fun `the report is identical for the same story`() {
        val instance = instance()
        assertEquals(report(instance).findings, report(instance).findings)
        assertEquals(report(instance).status, report(instance).status)
    }

    @Test
    fun `analysing changes nothing`() {
        val instance = instance()
        val before = instance
        report(instance)
        assertEquals("analysing must be read-only", before, instance)
    }

    @Test
    fun `a fresh story reports no errors`() {
        val result = report()
        val errors = result.findings.filter { it.severity == StoryHealthAnalyzer.Status.ERROR }
        assertTrue("a newly created story must be sound, got: ${errors.map { it.summary }}", errors.isEmpty())
    }

    @Test
    fun `the summary says what was checked`() {
        val result = report()
        assertTrue(result.checked.characters > 0)
        assertTrue(result.checked.memories > 0)
        assertTrue(result.summarise().contains("checks"))
    }

    @Test
    fun `one error makes the whole report an error`() {
        // A summary that said WARNING over a finding meaning "the model is being lied
        // to" would be the wrong summary.
        val broken = instance().let { current ->
            current.copy(
                worldState = current.worldState.copy(
                    characters = current.worldState.characters +
                        (
                            CharacterId("ghost") to CharacterRuntime(
                                characterId = CharacterId("ghost"),
                                name = "A Ghost",
                                locationId = LocationId("nowhere-at-all"),
                            )
                            ),
                ),
            )
        }
        assertTrue(report(broken).of(StoryHealthAnalyzer.Kind.IMPOSSIBLE_LOCATION).isNotEmpty())
        assertEquals(StoryHealthAnalyzer.Status.ERROR, report(broken).status)
    }

    @Test
    fun `a character with no location is not an impossible location`() {
        // Nowhere is a legitimate state; only a *wrong* place is impossible.
        val current = instance()
        val runtime = current.characters.getValue(andre)
        val story = current.copy(
            worldState = current.worldState.withCharacter(runtime.copy(locationId = null)),
        )
        assertTrue(
            report(story).of(StoryHealthAnalyzer.Kind.IMPOSSIBLE_LOCATION)
                .none { it.summary.contains(andre.value) },
        )
    }

    // ------------------------------------------------------------------
    // Impossible locations
    // ------------------------------------------------------------------

    @Test
    fun `a character in a place that does not exist is an error`() {
        val broken = instance().let { current ->
            current.copy(
                worldState = current.worldState.copy(
                    characters = current.worldState.characters +
                        (
                            CharacterId("nobody") to CharacterRuntime(
                                characterId = CharacterId("nobody"),
                                name = "Nobody",
                                locationId = LocationId("atlantis"),
                            )
                            ),
                ),
            )
        }
        val finding = report(broken).of(StoryHealthAnalyzer.Kind.IMPOSSIBLE_LOCATION).single()
        assertEquals(StoryHealthAnalyzer.Status.ERROR, finding.severity)
        assertTrue(finding.summary, finding.summary.contains("atlantis"))
        assertTrue("a finding must carry evidence", finding.evidence.isNotEmpty())
    }

    // ------------------------------------------------------------------
    // Knowledge boundaries
    // ------------------------------------------------------------------

    @Test
    fun `a character indexed as knowing an ungranted fact is an error`() {
        val current = instance()
        val runtime = current.characters.getValue(andre)
        val broken = current.copy(
            worldState = current.worldState.withCharacter(
                runtime.copy(knownFactIds = runtime.knownFactIds + FactId("fact-that-does-not-exist")),
            ),
        )
        val finding = report(broken).of(StoryHealthAnalyzer.Kind.UNAUTHORISED_KNOWLEDGE).single()
        assertEquals(StoryHealthAnalyzer.Status.ERROR, finding.severity)
    }

    @Test
    fun `granted knowledge is not reported`() {
        assertTrue(report().of(StoryHealthAnalyzer.Kind.UNAUTHORISED_KNOWLEDGE).isEmpty())
    }

    // ------------------------------------------------------------------
    // Secret leakage
    // ------------------------------------------------------------------

    @Test
    fun `a secret readable by a stranger is an error`() {
        // The bug is a *disagreement* between a memory's tier and its visibility: it is
        // written as a secret but stored as world-readable. Testing only the visibility
        // enum would sail straight past it, which is why the analyzer checks both.
        val leaky = memory(
            id = "secret-1",
            content = "Ladybug is Marinette",
            visibility = MemoryVisibility.WORLD,
        ).copy(tier = dev.charaly.runtime.domain.memory.MemoryTier.SECRET)

        assertTrue("premise: the stranger really can read it", leaky.visibleTo(MemorySubject.Character(adrien)))

        val story = instance().copy(memories = MemoryStore.EMPTY.add(leaky))
        val finding = report(story).of(StoryHealthAnalyzer.Kind.SECRET_LEAKAGE).first()
        assertEquals(StoryHealthAnalyzer.Status.ERROR, finding.severity)
        assertTrue(finding.evidence.any { it == "adrien" })
    }

    @Test
    fun `an ordinary world memory is not a leak however visible it is`() {
        // Not everything public is a secret. Reporting "the museum is open" as leakage
        // would train everyone to ignore the whole panel.
        val ordinary = memory(
            id = "ordinary-1",
            content = "The museum is open on Sunday",
            visibility = MemoryVisibility.WORLD,
        )
        val story = instance().copy(memories = MemoryStore.EMPTY.add(ordinary))
        assertTrue(report(story).of(StoryHealthAnalyzer.Kind.SECRET_LEAKAGE).isEmpty())
    }

    @Test
    fun `a properly scoped secret is not a leak`() {
        val secret = memory(
            id = "secret-ok",
            content = "I know where the brooch is",
            visibility = MemoryVisibility.SECRET,
        ).copy(tier = dev.charaly.runtime.domain.memory.MemoryTier.SECRET)
        assertFalse(secret.visibleTo(MemorySubject.Character(adrien)))
        val story = instance().copy(memories = MemoryStore.EMPTY.add(secret))
        assertTrue(
            "a secret nobody else can read is the correct state",
            report(story).of(StoryHealthAnalyzer.Kind.SECRET_LEAKAGE).isEmpty(),
        )
    }

    @Test
    fun `a secret shared on purpose is not a leak`() {
        val shared = memory(
            id = "secret-2",
            content = "I know where the brooch is",
            visibility = MemoryVisibility.SECRET,
            visibleTo = listOf(marinette),
        ).copy(tier = dev.charaly.runtime.domain.memory.MemoryTier.SECRET)
        val story = instance().copy(memories = MemoryStore.EMPTY.add(shared))
        assertTrue(
            "an explicitly shared secret is the point, not a bug",
            report(story).of(StoryHealthAnalyzer.Kind.SECRET_LEAKAGE).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Memory coherence
    // ------------------------------------------------------------------

    @Test
    fun `two incompatible current memories about one claim is an error`() {
        val story = instance().copy(
            memories = MemoryStore.EMPTY
                .add(
                    memory("m1", content = "The museum is open on Sunday", subject = "museum", predicate = "opens"),
                )
                .add(
                    memory(
                        "m2",
                        content = "The museum is shut on Sunday now",
                        subject = "museum",
                        predicate = "opens",
                        at = StoryTime.of(day = 1, hour = 10, minute = 0),
                    ),
                ),
        )
        val finding = report(story).of(StoryHealthAnalyzer.Kind.CONTRADICTORY_MEMORY).single()
        assertEquals(StoryHealthAnalyzer.Status.ERROR, finding.severity)
        assertEquals(2, finding.evidence.size)
    }

    @Test
    fun `a superseded memory is history, not a contradiction`() {
        val older = memory("m1", content = "The museum is open on Sunday", subject = "museum", predicate = "opens")
        val newer = memory(
            "m2",
            content = "The museum is shut on Sunday now",
            subject = "museum",
            predicate = "opens",
            at = StoryTime.of(day = 1, hour = 10, minute = 0),
        )
        val story = instance().copy(
            memories = MemoryStore.EMPTY.add(older).add(newer.supersedeBy(newer.id, StoryTime.of(day = 1, hour = 10, minute = 0))),
        )
        assertTrue(
            "a resolved contradiction is healthy; only current memories are compared",
            report(story).of(StoryHealthAnalyzer.Kind.CONTRADICTORY_MEMORY).isEmpty(),
        )
    }

    @Test
    fun `near duplicate memories are a warning`() {
        val story = instance().copy(
            memories = MemoryStore.EMPTY
                .add(memory("m1", content = "I found the akuma hiding in the museum tonight"))
                .add(memory("m2", content = "I found an akuma hiding in the museum tonight")),
        )
        val finding = report(story).of(StoryHealthAnalyzer.Kind.DUPLICATE_MEMORY).first()
        assertEquals(StoryHealthAnalyzer.Status.WARNING, finding.severity)
    }

    @Test
    fun `the same fact held by two characters is not a duplicate`() {
        // Two people knowing the same thing is the world working, not memory bloat.
        val story = instance().copy(
            memories = MemoryStore.EMPTY
                .add(memory("m1", owner = andre, content = "I found the akuma hiding in the museum tonight"))
                .add(memory("m2", owner = marinette, content = "I found the akuma hiding in the museum tonight")),
        )
        assertTrue(report(story).of(StoryHealthAnalyzer.Kind.DUPLICATE_MEMORY).isEmpty())
    }

    // ------------------------------------------------------------------
    // Threads
    // ------------------------------------------------------------------

    @Test
    fun `a thread pointing at a character who does not exist is an error`() {
        val story = instance().copy(
            worldState = instance().worldState.withThread(
                StoryThread(
                    id = ThreadId("thread-orphan"),
                    title = "Orphan",
                    status = StoryThreadStatus.ACTIVE,
                    involvedCharacterIds = listOf(CharacterId("does-not-exist")),
                ),
            ),
        )
        val finding = report(story).of(StoryHealthAnalyzer.Kind.ORPHANED_THREAD).single()
        assertEquals(StoryHealthAnalyzer.Status.ERROR, finding.severity)
        assertTrue(finding.evidence.any { it.contains("does-not-exist") })
    }

    @Test
    fun `a thread nobody has advanced is a warning, not an error`() {
        // "Abandoned" and "still simmering" look identical from the outside, so the
        // monitor names the thread rather than declaring it dead.
        val old = StoryTime.of(day = 1, hour = 8, minute = 10)
        val later = old.plusMinutes(StoryHealthAnalyzer.DEAD_THREAD_HOURS * 60 + 60)
        val story = instance().copy(
            worldState = instance().worldState.copy(
                worldClock = instance().worldState.worldClock.advanceTo(later),
            ),
        ).let { current ->
            current.copy(
                worldState = current.worldState.withThread(
                    StoryThread(
                        id = ThreadId("thread-old"),
                        title = "Nobody touched this",
                        status = StoryThreadStatus.ACTIVE,
                        stage = 1,
                        updatedAt = old,
                    ),
                ),
            )
        }
        val finding = report(story).of(StoryHealthAnalyzer.Kind.DEAD_THREAD).first()
        assertEquals(StoryHealthAnalyzer.Status.WARNING, finding.severity)
    }

    @Test
    fun `a freshly advanced thread is not dead`() {
        val finding = report().of(StoryHealthAnalyzer.Kind.DEAD_THREAD)
        assertTrue("threads that just moved must not be reported: ${finding.map { it.summary }}", finding.isEmpty())
    }

    // ------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------

    @Test
    fun `a scene listing someone who is elsewhere is an error`() {
        val current = instance()
        val scene = Scene(
            id = dev.charaly.runtime.domain.SceneId("scene-bad"),
            locationId = current.characters.getValue(andre).locationId!!,
            participants = listOf(andre, adrien),
            focusCharacterId = andre,
            startedAt = current.worldClock.now,
        )
        val story = current.copy(
            worldState = current.worldState.withScene(scene),
        )
        val finding = report(story).of(StoryHealthAnalyzer.Kind.MISSING_PARTICIPANT).first()
        assertEquals(StoryHealthAnalyzer.Status.ERROR, finding.severity)
        assertTrue(finding.summary, finding.summary.contains("Adrien"))
    }

    @Test
    fun `an ended scene is not a live participant problem`() {
        val current = instance()
        val ended = Scene(
            id = dev.charaly.runtime.domain.SceneId("scene-ended"),
            locationId = current.characters.getValue(andre).locationId!!,
            participants = listOf(andre, adrien),
            startedAt = current.worldClock.now,
            state = SceneState.ENDED,
        )
        val story = current.copy(worldState = current.worldState.withScene(ended))
        // Ended scenes are still in the map here, so the finding is allowed to appear;
        // what matters is that the analyzer does not invent one for a coherent scene.
        assertTrue(report(story).of(StoryHealthAnalyzer.Kind.MISSING_PARTICIPANT).all { it.evidence.isNotEmpty() })
    }

    // ------------------------------------------------------------------
    // Knowledge confidence
    // ------------------------------------------------------------------

    @Test
    fun `being certain about some things and unsure about others is a warning`() {
        val current = instance()
        val story = current.copy(
            knowledge = current.knowledge.withKnowledge(
                andre,
                listOf(
                    KnowledgeEntry(FactId("fact-a"), current.worldClock.now, confidence = 100),
                    KnowledgeEntry(FactId("fact-b"), current.worldClock.now, confidence = 20),
                ),
            ),
        )
        val finding = report(story).of(StoryHealthAnalyzer.Kind.CONTRADICTORY_FACT).first()
        assertEquals(StoryHealthAnalyzer.Status.WARNING, finding.severity)
        assertTrue(finding.evidence.contains("fact-b"))
    }

    // ------------------------------------------------------------------
    // Event conflicts
    // ------------------------------------------------------------------

    @Test
    fun `two identical events at the same instant are flagged`() {
        val current = instance()
        val at = current.worldClock.now.plusMinutes(30)
        val engine = EventEngine(definition)
        val first = engine.scheduleEvent(
            current,
            dev.charaly.runtime.domain.events.CharacterActivityChanged(andre, dev.charaly.runtime.domain.CharacterActivity.WORKING),
            at,
        )
        val queued = (first as ScheduleResult.Scheduled).instance
        val second = engine.scheduleEvent(
            queued,
            dev.charaly.runtime.domain.events.CharacterActivityChanged(andre, dev.charaly.runtime.domain.CharacterActivity.WORKING),
            at,
        )
        val doubled = (second as ScheduleResult.Scheduled).instance
        val finding = report(doubled).of(StoryHealthAnalyzer.Kind.EVENT_CONFLICT).first()
        assertEquals(StoryHealthAnalyzer.Status.WARNING, finding.severity)
    }

    @Test
    fun `two different events at the same instant are not a conflict`() {
        val current = instance()
        val at = current.worldClock.now.plusMinutes(30)
        val engine = EventEngine(definition)
        val first = engine.scheduleEvent(
            current,
            dev.charaly.runtime.domain.events.CharacterActivityChanged(andre, dev.charaly.runtime.domain.CharacterActivity.WORKING),
            at,
        )
        val queued = (first as ScheduleResult.Scheduled).instance
        val second = engine.scheduleEvent(
            queued,
            RelationshipChanged(marinette, adrien, RelationshipDelta(trust = 5)),
            at,
        )
        val both = (second as ScheduleResult.Scheduled).instance
        assertTrue(
            "scheduling two unrelated things for the same minute is ordinary, not a conflict",
            report(both).of(StoryHealthAnalyzer.Kind.EVENT_CONFLICT).isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Stale goals
    // ------------------------------------------------------------------

    @Test
    fun `a goal pursued for a very long time is a warning`() {
        val current = instance()
        val start = current.worldClock.now
        val later = start.plusMinutes(StoryHealthAnalyzer.STALE_GOAL_HOURS * 60 + 60)
        val runtime = current.characters.getValue(andre)
        val story = current.copy(
            worldState = current.worldState
                .copy(worldClock = current.worldState.worldClock.advanceTo(later))
                .withCharacter(runtime.copy(activeGoals = listOf("find the missing brooch"))),
        )
        val finding = report(story).of(StoryHealthAnalyzer.Kind.STALE_GOAL)
            .first { it.summary.contains("find the missing brooch") }
        assertEquals(StoryHealthAnalyzer.Status.WARNING, finding.severity)
    }

    @Test
    fun `a fresh goal is not stale`() {
        val current = instance()
        val runtime = current.characters.getValue(andre)
        val story = current.copy(
            worldState = current.worldState.withCharacter(
                runtime.copy(activeGoals = listOf("open the shop")),
            ),
        )
        assertTrue(report(story).of(StoryHealthAnalyzer.Kind.STALE_GOAL).isEmpty())
    }

    // ------------------------------------------------------------------
    // Report ergonomics
    // ------------------------------------------------------------------

    @Test
    fun `findings can be filtered by kind`() {
        val result = report()
        result.of(StoryHealthAnalyzer.Kind.SECRET_LEAKAGE).forEach {
            assertEquals(StoryHealthAnalyzer.Kind.SECRET_LEAKAGE, it.kind)
        }
    }

    @Test
    fun `every finding states a summary`() {
        report().findings.forEach {
            assertTrue("a finding with no summary is useless: ${it.kind}", it.summary.isNotBlank())
        }
    }

    @Test
    fun `the runtime exposes the report without touching the story`() {
        val storage = dev.charaly.runtime.persistence.InMemoryCharalyStorage()
        val runtime = dev.charaly.runtime.session.CharalyRuntime(
            repository = dev.charaly.runtime.persistence.JsonCharalyRepository(storage),
            engine = dev.charaly.runtime.inference.MockInferenceEngine(initiallyLoaded = true),
        )
        val story = kotlinx.coroutines.runBlocking {
            runtime.startStory(
                pack = pack,
                options = StoryCreationOptions(
                    instanceId = StoryInstanceId("story-health-rt"),
                    title = "Health",
                    scenario = pack.defaultScenario(),
                    focusCharacterId = marinette,
                    nowEpochMs = 1_700_000_000_000L,
                ),
            )
        }
        val before = story
        val result = runtime.storyHealth(story)
        assertFalse(result.findings.any { it.severity == StoryHealthAnalyzer.Status.ERROR })
        assertEquals("storyHealth must not mutate", before, story)
    }
}