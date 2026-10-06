package dev.charaly.runtime.integration

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.EventProgram
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.pack.LastKingdomPack
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.pack.NeonDistrictPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Story pack isolation, tested as an adversarial scenario rather than as a property.
 *
 * ## The scenario
 *
 * The brief asks for exactly this walkthrough, and it is the right one:
 *
 * ```
 *   1. create a Miraculous story
 *   2. trigger a Miraculous event
 *   3. create a second story in a different pack
 *   4. assert the Miraculous event is not in it
 * ```
 *
 * The step that makes it worth writing is step 2. Isolation between two *untouched* packs
 * is easy - two fresh objects share nothing. Isolation between one pack that has been
 * played and a fresh one is the real property, because anything cached, hoisted to a
 * companion object, or held in a static would show up here and nowhere else.
 *
 * So each test plays the first story, changes everything it can, and then checks that
 * none of it reached the second.
 */
class StoryIsolationTest {

    private val packA = MiraculousPack.pack
    private val packB = NeonDistrictPack.pack
    private val packC = LastKingdomPack.pack

    private fun start(pack: dev.charaly.runtime.domain.StoryPack, id: String) =
        StoryInstanceFactory.create(pack, StoryInstanceId(id))

    /**
     * Fires every seed event the pack's program produces for a fresh story.
     *
     * This is the pack's own compilation path - the same `EventProgram.compile` the
     * factory uses - rather than a hand-rolled loop. Using the real path is the point:
     * the isolation claim is about what the engine does, and a test with its own event
     * application would be testing itself.
     *
     * Returns the number of payloads applied, so a test cannot pass vacuously on a pack
     * that seeded nothing.
     */
    private fun fireStartEvents(
        instance: dev.charaly.runtime.domain.StoryInstance,
        pack: dev.charaly.runtime.domain.StoryPack,
    ): Int {
        val definition = dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations)
        val engine = EventEngine(definition)
        val program = EventProgram
        // A null scenario: the factory's default path, so this compiles exactly what a
        // plain "start story" would seed.
        val seeds = program.compile(pack, null, instance)
        var current = instance
        var fired = 0
        for (seed in seeds) {
            when (val applied = engine.applyImmediately(current, seed.payload)) {
                is EventApplication.Applied -> {
                    current = applied.instance
                    fired++
                }
                // A rejected seed is the engine refusing something, which is a valid
                // outcome and not something this test asserts about. It simply does not
                // count as "fired".
                is EventApplication.Rejected -> Unit
            }
        }
        return fired
    }

    // ------------------------------------------------------------------
    // The core walkthrough
    // ------------------------------------------------------------------

    /**
     * Step 1-4: play Miraculous, then prove Neon knows nothing about it.
     *
     * This is the headline test of the whole isolation design.
     */
    @Test
    fun `a Miraculous event does not exist in a Neon District story`() {
        // 1. Create a Miraculous story.
        val mir = start(packA, "story-mir-1")

        // 2. Trigger Miraculous events.
        val fired = fireStartEvents(mir, packA)
        assertTrue("the Miraculous pack should fire start events; none did", fired > 0)

        // Give the world a distinctive, irreversible marker in *every* subsystem, so a
        // leak in any one of them is detectable.
        val marked = mir.copy(
            worldState = mir.worldState.copy(
                variables = mir.worldState.variables + ("mir-marker" to dev.charaly.runtime.domain.WorldVariable(
                    key = "mir-marker",
                    value = "written-by-test",
                )),
            ),
            memories = mir.memories.add(dev.charaly.runtime.domain.memory.Memory(
                id = dev.charaly.runtime.domain.MemoryId("mir-secret"),
                characterId = CharacterId(MiraculousPack.MARINETTE),
                content = "The akuma is a corrupted butterfly. Nobody knows but Marinette.",
                importance = 5,
                visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.SECRET,
                visibleTo = listOf(CharacterId(MiraculousPack.MARINETTE)),
            )),
        )

        // 3. Create a second story in a different pack.
        val neon = start(packB, "story-neon-1")

        // 4. Assert nothing crossed over.
        //
        // ## Why this compares `firedEvents` and not `eventLog`
        //
        // `worldState.eventLog` holds instance-local ids: `evt-1`, `evt-2`, ... generated
        // from a per-story counter. Two independent stories therefore both contain
        // "evt-1" - and comparing the two logs would pass even if the worlds were deeply
        // entangled. An earlier draft of this test did exactly that, which is worth
        // recording because it is the kind of assertion that looks rigorous and is not.
        //
        // `firedEvents` is keyed by the *pack* event id, which is a real cross-story
        // identity. So that is what this checks: which pack's events a story believes
        // have fired.
        val mirFired = marked.firedEvents.keys
        assertTrue("the played story should have fired its pack events", mirFired.isNotEmpty())

        val neonFired = neon.firedEvents.keys
        val mirEventIds = packA.events.map { it.id }.toSet()
        val neonEventIds = packB.events.map { it.id }.toSet()

        val leaked = mirFired.filter { it in mirEventIds && it in neonFired }
        assertTrue(
            "Miraculous events were recorded as fired in a Neon District story: $leaked",
            leaked.isEmpty(),
        )
        assertTrue(
            "a Neon District story fired a Miraculous event",
            neonFired.none { it in mirEventIds },
        )
        // And the reverse is not vacuous: the Neon story has its own fired events.
        assertTrue(
            "the Neon story should have fired its own pack events",
            neonFired.any { it in neonEventIds } || neonEventIds.isEmpty(),
        )

        // The causal graph is keyed by the same instance-local ids, so it is checked for
        // structural integrity rather than by cross-story comparison.
        assertTrue(
            "causal links should point at events this story has applied",
            neon.worldState.causality.isEmpty() ||
                neon.worldState.causality.keys.all { id -> id in neon.worldState.eventLog },
        )

        assertNull(
            "Miraculous world variable leaked",
            neon.worldState.variables["mir-marker"],
        )
        assertEquals("written-by-test", marked.worldState.variables["mir-marker"]?.value)

        assertTrue(
            "Miraculous memory leaked",
            neon.memories.current().none { it.content.contains("corrupted butterfly") },
        )

        assertTrue(
            "a Miraculous character exists in a Neon District story",
            neon.worldState.characters.keys.none {
                it.value == MiraculousPack.MARINETTE || it.value == MiraculousPack.ADRIEN
            },
        )
    }

    /**
     * The reverse direction.
     *
     * One direction passing is not isolation - it could be an ordering artefact, where
     * the first story built simply wins a cache.
     */
    @Test
    fun `a Neon District event does not exist in a Miraculous story`() {
        val neon = start(packB, "story-neon-2")
        assertTrue("the Neon pack should fire start events", fireStartEvents(neon, packB) > 0)
        val neonFired = neon.firedEvents.keys
        val neonEventIds = packB.events.map { it.id }.toSet()
        val mirEventIds = packA.events.map { it.id }.toSet()

        val mir = start(packA, "story-mir-2")

        assertTrue("the Neon story should have fired its own events", neonFired.any { it in neonEventIds })
        assertTrue(
            "a Neon event was recorded as fired in a Miraculous story",
            mir.firedEvents.keys.none { it in neonEventIds },
        )
        assertTrue(
            "the Miraculous story fired a Neon event",
            neonFired.none { it in mirEventIds },
        )
    }

    /**
     * Every pair, in both directions.
     *
     * Two packs is not enough evidence for a structural claim; three gives the triangle
     * that catches a shared static.
     */
    @Test
    fun `no pack shares ids with any other`() {
        val packs = listOf(packA, packB, packC)
        val characters = packs.map { it.characters.map { c -> c.id.value }.toSet() }
        val locations = packs.map { it.locations.map { l -> l.id.value }.toSet() }
        val threads = packs.map { it.initialStoryThreads.map { t -> t.id.value }.toSet() }
        val events = packs.map { it.events.map { e -> e.id }.toSet() }
        val scenarios = packs.map { it.scenarios.map { s -> s.id }.toSet() }

        for (i in packs.indices) {
            for (j in packs.indices) {
                if (i == j) continue
                val name = "${packs[i].title} vs ${packs[j].title}"
                assertTrue("$name share character ids", characters[i].intersect(characters[j]).isEmpty())
                assertTrue("$name share location ids", locations[i].intersect(locations[j]).isEmpty())
                assertTrue("$name share thread ids", threads[i].intersect(threads[j]).isEmpty())
                assertTrue("$name share event ids", events[i].intersect(events[j]).isEmpty())
                assertTrue("$name share scenario ids", scenarios[i].intersect(scenarios[j]).isEmpty())
            }
        }
    }

    // ------------------------------------------------------------------
    // Subsystem-by-subsystem
    // ------------------------------------------------------------------

    @Test
    fun `relationships do not cross packs`() {
        val mir = start(packA, "story-mir-rel")
        val mirRelationships = mir.worldState.relationships.keys.map { "${it.sourceId.value}/${it.targetId.value}" }

        val neon = start(packB, "story-neon-rel")
        val neonRelationships = neon.worldState.relationships.keys.map { "${it.sourceId.value}/${it.targetId.value}" }

        assertTrue(mirRelationships.isNotEmpty())
        assertTrue(
            "relationship pairs leaked: " + mirRelationships.filter { it in neonRelationships },
            mirRelationships.none { it in neonRelationships },
        )
    }

    @Test
    fun `threads do not cross packs`() {
        val mir = start(packA, "story-mir-thread")
        val mirThreadIds = mir.storyThreads.keys.map { it.value }

        val neon = start(packB, "story-neon-thread")
        val neonThreadIds = neon.storyThreads.keys.map { it.value }

        assertTrue(mirThreadIds.isNotEmpty())
        assertTrue(mirThreadIds.none { it in neonThreadIds })
        // And the Neon story has its own threads, so this is not vacuous.
        assertTrue(neonThreadIds.isNotEmpty())
    }

    /**
     * Canon is per pack, and a fresh story deviates from nothing.
     *
     * The ledger starts empty in every story. That is the "canon lock" property from the
     * pack's point of view: playing cannot silently rewrite the author's canon, because
     * only an explicit, recorded deviation can - and nothing has recorded one.
     */
    @Test
    fun `canon starts identical to canon in every pack`() {
        val mir = start(packA, "story-mir-canon")
        val neon = start(packB, "story-neon-canon")
        val kingdom = start(packC, "story-king-canon")

        for (instance in listOf(mir, neon, kingdom)) {
            assertEquals(
                "${instance.displayTitle} should have no canon deviations",
                0,
                instance.canonDeviations.deviations.size,
            )
            assertTrue(
                "${instance.displayTitle} should have no active deviations",
                instance.canonDeviations.activeDeviations().isEmpty(),
            )
        }

        // Each pack carries its own canon bible, and their asserted facts do not collide.
        val mirCanon = packA.canon.factIds.map { it.value }.toSet()
        val neonCanon = packB.canon.factIds.map { it.value }.toSet()
        assertTrue(
            "canon fact ids overlap between packs: " + mirCanon.intersect(neonCanon),
            mirCanon.intersect(neonCanon).isEmpty(),
        )
        // And each pack's canon actually points at facts that exist in its own world.
        val mirWorldFacts = packA.initialKnowledge.facts.map { it.id.value }.toSet()
        assertTrue(
            "Miraculous canon references unknown facts: " +
                mirCanon.filterNot { it in mirWorldFacts },
            mirCanon.all { it in mirWorldFacts },
        )
    }

    @Test
    fun `a pack's locations are unreachable from another pack's story`() {
        val mir = start(packA, "story-mir-loc")
        val neon = start(packB, "story-neon-loc")

        val mirLocations = mir.locations.keys.map { it.value }
        val neonLocations = neon.locations.keys.map { it.value }

        assertTrue(mirLocations.isNotEmpty())
        assertTrue(mirLocations.none { it in neonLocations })
    }

    /**
     * Knowledge does not cross.
     *
     * A Miraculous secret is a Miraculous character's business. This is the boundary
     * that matters most in the whole engine, so it is asserted here at the pack level in
     * addition to the character-level tests.
     */
    @Test
    fun `knowledge grants do not cross packs`() {
        val mir = start(packA, "story-mir-know")
        val neon = start(packB, "story-neon-know")

        val mirFacts = mir.knowledge.truth.keys.map { it.value }.toSet()
        val neonFacts = neon.knowledge.truth.keys.map { it.value }.toSet()

        if (mirFacts.isNotEmpty() && neonFacts.isNotEmpty()) {
            assertTrue(
                "fact ids overlap between packs: " + mirFacts.intersect(neonFacts),
                mirFacts.intersect(neonFacts).isEmpty(),
            )
        }
    }

    /**
     * Sequential use in one process.
     *
     * Isolation tests that each build one story can pass with a bug that only appears on
     * the *second* story of a pack - a mutated companion object, a lazily initialised
     * cache keyed too loosely. So this runs several stories in sequence and checks the
     * later ones are pristine.
     */
    @Test
    fun `a second story of the same pack starts clean`() {
        val first = start(packA, "story-mir-seq-1")
        fireStartEvents(first, packA)

        val polluted = first.copy(
            memories = first.memories.add(dev.charaly.runtime.domain.memory.Memory(
                id = dev.charaly.runtime.domain.MemoryId("seq-secret"),
                characterId = CharacterId(MiraculousPack.MARINETTE),
                content = "Written during the first playthrough.",
                importance = 4,
            )),
        )
        assertTrue(polluted.memories.current().any { it.content.contains("first playthrough") })

        // A brand new story of the same pack, made afterwards in the same process.
        val second = start(packA, "story-mir-seq-2")
        assertTrue(
            "the first playthrough's memory leaked into a new story of the same pack",
            second.memories.current().none { it.content.contains("first playthrough") },
        )

        // `firedEvents` is keyed by pack event id, which is a genuine cross-story
        // identity; `eventLog` is not, since both stories restart at `evt-1`. So the
        // check is that the fresh story fired only what its own pack defines, and has
        // no state the first one built.
        assertTrue(
            "a fresh story fired an event id its pack does not define",
            second.firedEvents.keys.all { it in packA.events.map { e -> e.id } },
        )
        assertTrue(
            "the fresh story carries a world variable from the first playthrough",
            second.worldState.variables.keys.none { it == "mir-marker" },
        )
    }

    /**
     * A pack's own event programs are pack-scoped, not shared infrastructure.
     *
     * EventEngine is common; the *definitions* are not. So a Neon District story must
     * not recognise a Miraculous trigger, even though both are `PackEventDefinition`s
     * compiled by the same compiler.
     */
    @Test
    fun `event programs are pack-scoped`() {
        val mirEventIds = packA.events.map { it.id }.toSet()
        val neonEventIds = packB.events.map { it.id }.toSet()
        assertTrue(mirEventIds.isNotEmpty())
        assertTrue(neonEventIds.isNotEmpty())
        assertTrue(
            "Miraculous triggers leak into the Neon program",
            mirEventIds.intersect(neonEventIds).isEmpty(),
        )

        // And the compiler, asked for the Neon pack, produces only Neon seeds.
        // Compilation is a pure function of the pack it is handed, so a Miraculous
        // definition has no route into a Neon program: there is no call that takes one
        // pack's definitions and another pack's instance.
        val neonStory = start(packB, "story-neon-program")
        val seeds = EventProgram.compile(packB, null, neonStory)
        assertTrue("the Neon pack should seed at least one event", seeds.isNotEmpty())

        val prefix = EventProgram.EVENT_NOTE_PREFIX
        for (seed in seeds) {
            if (seed.note.startsWith(prefix)) {
                val referenced = seed.note.removePrefix(prefix)
                assertTrue(
                    "a Neon seed referenced a Miraculous event '$referenced'",
                    referenced in neonEventIds,
                )
            }
        }
    }

    @Test
    fun `all three demo packs are the ones this suite assumes`() {
        assertEquals(3, DemoStoryPacks.all.size)
        assertNotNull(DemoStoryPacks.byId(packA.id.value))
        assertNotNull(DemoStoryPacks.byId(packB.id.value))
        assertNotNull(DemoStoryPacks.byId(packC.id.value))
        assertNull(DemoStoryPacks.byId("pack-that-does-not-exist"))
    }

    /**
     * The player location variable is per story.
     *
     * `player_location` is a well-known key name, so it is the most likely thing to be
     * hoisted into a static by accident. Two stories of different packs in one process
     * must not see each other's.
     */
    @Test
    fun `player location does not leak between stories`() {
        val mir = start(packA, "story-mir-pl")
        val neon = start(packB, "story-neon-pl")

        val mirLocation = EventEngine.currentPlayerLocation(mir)
        val neonLocation = EventEngine.currentPlayerLocation(neon)

        // Whatever each resolves to, it must resolve within its own pack.
        mirLocation?.let {
            assertTrue(
                "Miraculous player location $it is not a Miraculous location",
                packA.locations.any { l -> l.id == it },
            )
        }
        neonLocation?.let {
            assertTrue(
                "Neon player location $it is not a Neon location",
                packB.locations.any { l -> l.id == it },
            )
        }
        if (mirLocation != null && neonLocation != null) {
            assertFalse(
                "both stories resolved to the same location id",
                mirLocation == neonLocation,
            )
        }
    }
}
