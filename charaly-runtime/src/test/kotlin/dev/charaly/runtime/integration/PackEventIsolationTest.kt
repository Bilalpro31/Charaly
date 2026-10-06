package dev.charaly.runtime.integration

import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.SeedEvent
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.CharacterEnteredScene
import dev.charaly.runtime.domain.events.CharacterLeftScene
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.domain.events.EventPayload
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.KnowledgeRevoked
import dev.charaly.runtime.domain.events.LocationEntered
import dev.charaly.runtime.domain.events.PromiseMade
import dev.charaly.runtime.domain.events.PromiseResolved
import dev.charaly.runtime.domain.events.RelationshipChanged
import dev.charaly.runtime.domain.events.SceneEnded
import dev.charaly.runtime.domain.events.SceneStarted
import dev.charaly.runtime.domain.events.StoryThreadAdvanced
import dev.charaly.runtime.engine.EventProgram
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.pack.LastKingdomPack
import dev.charaly.runtime.pack.MiraculousPack
import dev.charaly.runtime.pack.NeonDistrictPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pack-scoped event programs.
 *
 * ## What "pack-scoped" has to mean
 *
 * The brief requires that a pack's events cannot leak into another pack. That is
 * stronger than "event ids differ", and it is worth being precise about the three layers:
 *
 * ```
 *   EventEngine          shared infrastructure. One deterministic reducer for all worlds.
 *   EventProgram         compiled per pack. Asked for pack A, it reads only pack A.
 *   PackEventDefinition  the data. Belongs to exactly one pack.
 * ```
 *
 * Sharing the engine is correct and intended - a single pure reducer is what makes the
 * whole thing testable on a plain JVM. What must not be shared is the *data*, and the
 * two realistic risks are:
 *
 *  * something caches compiled payloads, so compiling pack A changes what pack B yields;
 *  * an event that compiles successfully while referring to an entity in another pack,
 *    producing a plausible-looking change in the wrong universe.
 *
 * These tests attack both.
 */
class PackEventIsolationTest {

    private val packs = DemoStoryPacks.all

    private fun idsOf(pack: StoryPack): Set<String> = pack.events.map { it.id }.toSet()

    private fun instanceFor(pack: StoryPack, id: String) =
        StoryInstanceFactory.create(pack, StoryInstanceId(id))

    // ------------------------------------------------------------------
    // The data
    // ------------------------------------------------------------------

    /**
     * Every event belongs to exactly one pack.
     *
     * Asserted across all pairs rather than for one pair, because a three-pack triangle
     * catches a shared mutable registry that a two-pack check would miss.
     */
    @Test
    fun `event ids are unique across all packs`() {
        val allIds = packs.flatMap { idsOf(it) }
        val duplicates = allIds.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("duplicate event ids across packs: $duplicates", allIds.size, allIds.distinct().size)
    }

    /**
     * An event with a blank id would be unreachable and untraceable in the causal graph.
     */
    @Test
    fun `every event has a usable id and title`() {
        for (pack in packs) {
            for (event in pack.events) {
                assertTrue("${pack.title}: an event has a blank id", event.id.isNotBlank())
                assertTrue("${pack.title}: event '${event.id}' has no title", event.title.isNotBlank())
            }
        }
    }

    /**
     * No event refers to an entity that is not in its own pack.
     *
     * ## What this is actually testing
     *
     * Distinct ids are table stakes. The interesting failure is an event that *works* -
     * compiles, validates, applies - while naming something in another universe. That
     * produces a world change nobody can explain and no id-comparison test catches.
     *
     * So this walks every authored effect of every event and resolves each referenced id
     * against that same pack's content. A failure is an authoring bug: the event could
     * not do what it claims to.
     */
    @Test
    fun `no event references an entity from another pack`() {
        for (pack in packs) {
            val known = knownIdsOf(pack)

            for (event in pack.events) {
                // Declared participants and location: typed ids, so `.value` is the
                // string the author wrote.
                for (participant in event.participants) {
                    assertTrue(
                        "${pack.title}: event '${event.id}' lists participant " +
                            "'${participant.value}', which is not a character in this pack",
                        participant.value in known.characters,
                    )
                }
                event.locationId?.let { location ->
                    assertTrue(
                        "${pack.title}: event '${event.id}' is located at " +
                            "'${location.value}', which is not a location in this pack",
                        location.value in known.locations,
                    )
                }

                for (effect in event.effects) {
                    for (ref in idReferencesIn(effect)) {
                        assertTrue(
                            "${pack.title}: event '${event.id}' effect references '${ref.value}', " +
                                "which is not a ${ref.kind} in this pack",
                            known.owns(ref.value),
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Compilation
    // ------------------------------------------------------------------

    /**
     * Compiling one pack does not affect the next.
     *
     * `EventProgram` is a stateless object, which is correct - it holds nothing between
     * calls. This asserts that empirically: compiling pack A, then pack B, then pack A
     * again yields identical payloads both times for A. Cached compiled state keyed too
     * loosely would show up here.
     */
    @Test
    fun `compiling one pack does not affect the next`() {
        val aFirst = EventProgram.compile(packs[0], null, instanceFor(packs[0], "story-a1"))
        EventProgram.compile(packs[1], null, instanceFor(packs[1], "story-b1"))
        val aSecond = EventProgram.compile(packs[0], null, instanceFor(packs[0], "story-a2"))

        assertEquals(
            "compiling the same pack twice produced different payloads",
            aFirst.map { describe(it) },
            aSecond.map { describe(it) },
        )
    }

    /**
     * Seed payloads reference only their own pack's content.
     *
     * Checked on the compiled payloads rather than the metadata, so it covers what
     * actually gets applied to a world.
     */
    @Test
    fun `seed payloads reference only their own pack's entities`() {
        for (pack in packs) {
            val known = knownIdsOf(pack)
            val seeds = EventProgram.compile(pack, null, instanceFor(pack, "story-${pack.id.value}"))

            assertTrue("${pack.title} seeded nothing", seeds.isNotEmpty())
            for (seed in seeds) {
                // Only payloads that carry a *name* are checked, and only against the
                // right set for that name. A payload's other fields are prose and
                // numbers; scraping its rendering for hyphen-case words flags English.
                for (ref in idReferencesIn(seed.payload)) {
                    assertTrue(
                        "${pack.title}: a seed payload references '${ref.value}', " +
                            "which is not a ${ref.kind} in this pack",
                        known.owns(ref.value),
                    )
                }
            }
        }
    }

    /**
     * Every pack compiles to seeds.
     *
     * The baseline the isolation assertions rest on: if a pack cannot compile at all,
     * "its events do not leak" is true for the wrong reason.
     */
    @Test
    fun `every pack compiles to seeds`() {
        for (pack in packs) {
            val seeds = EventProgram.compile(pack, null, instanceFor(pack, "story-compile-${pack.id.value}"))
            assertTrue("${pack.title} compiled to no seeds", seeds.isNotEmpty())
        }
    }

    /**
     * The three packs declare genuinely different events.
     *
     * A copy-paste regression guard: two packs with identical event ids would still be
     * isolated, but the packs would be the same world wearing two names.
     */
    @Test
    fun `the three packs have different event programs`() {
        val miraculous = idsOf(MiraculousPack.pack)
        val neon = idsOf(NeonDistrictPack.pack)
        val kingdom = idsOf(LastKingdomPack.pack)

        assertTrue("Miraculous needs events", miraculous.isNotEmpty())
        assertTrue("Neon needs events", neon.isNotEmpty())
        assertTrue("The Last Kingdom needs events", kingdom.isNotEmpty())

        assertTrue("Miraculous and Neon share event ids", miraculous.intersect(neon).isEmpty())
        assertTrue("Miraculous and the Kingdom share event ids", miraculous.intersect(kingdom).isEmpty())
        assertTrue("Neon and the Kingdom share event ids", neon.intersect(kingdom).isEmpty())
    }

    /**
     * A pack's world definition is built only from its own content.
     *
     * The constructor call in `StoryInstanceFactory` is what makes a story's world; if a
     * pack could smuggle another pack's characters in through here, every other
     * isolation guarantee would be cosmetic.
     */
    @Test
    fun `a story's world is built from its own pack alone`() {
        for (pack in packs) {
            val instance = instanceFor(pack, "story-world-${pack.id.value}")
            val mine = pack.characters.map { it.id.value }.toSet()
            assertEquals(
                "${pack.title}'s story has a cast that differs from the pack's",
                mine,
                instance.worldState.characters.keys.map { it.value }.toSet(),
            )
            // And the definition the engine resolves for it carries nothing extra.
            val definition = WorldDefinition(pack.characters, pack.locations)
            assertEquals(mine.size, definition.characters.size)
        }
    }

    // ------------------------------------------------------------------
    // Resolving a reference
    // ------------------------------------------------------------------

    /**
     * Everything a pack legitimately owns, in several senses.
     *
     * The sets differ, and knowing which is which is the point: an effect naming a
     * thread is not a bug, and an effect naming a character id from a different pack is.
     */
    private data class KnownIds(
        val characters: Set<String>,
        val locations: Set<String>,
        val threads: Set<String>,
        val facts: Set<String>,
        val events: Set<String>,
        /** Scenes a pack names when it opens one. See [scenes]. */
        val scenes: Set<String>,
        /**
         * Any value the pack's authored content names in a `field="value"` position.
         *
         * Deliberately permissive. It is the set of names an author actually wrote, so
         * anything matching it is legal to reference; over-inclusion here can only mask a
         * leak in an id *no author wrote*, which could not resolve in the engine either.
         */
        val authored: Set<String>,
    ) {
        val all: Set<String>
            get() = characters + locations + threads + facts + events + scenes + authored
    }

    /**
     * Whether a token refers to something this pack owns.
     *
     * ## Derived ids
     *
     * Some ids are minted at runtime from a pack-owned id:
     *
     * ```
     *   scene-<eventId>-<minute>   a scene an event opens, named after it
     *   mem-<eventId>-<n>-0        a memory an event writes
     *   evt-N                      an instance-local counter
     * ```
     *
     * The first two cannot name anything outside the pack that minted them, because their
     * stem is an id this pack owns. So a recognised derived prefix is stripped and the
     * remainder checked. `evt-N` is accepted on sight: it is a per-story counter with no
     * cross-story meaning.
     *
     * ## Why this took three attempts
     *
     * Whole-string equality rejected valid references: `scene-museum-breach` is a scene
     * the pack declares, `mem-event-x-1-0` derives from a valid event. Requiring an id
     * *namespace* prefix then rejected every real character, because the packs use plain
     * slugs like `"elara"`. Matching authored field values is what actually separates an
     * id from a hyphenated English phrase - `third-floor` in a `note` is prose, and it is
     * not in the authored set.
     */
    private fun KnownIds.owns(token: String): Boolean {
        if (token in all) return true
        for (prefix in DERIVED_PREFIXES) {
            if (!token.startsWith(prefix)) continue
            val rest = token.removePrefix(prefix)
            if (prefix == COUNTER_PREFIX && rest.all(Char::isDigit)) return true
            if (all.any { candidate -> rest == candidate || rest.startsWith("$candidate-") }) return true
        }
        return false
    }

    private fun knownIdsOf(pack: StoryPack): KnownIds = KnownIds(
        characters = pack.characters.map { it.id.value }.toSet(),
        locations = pack.locations.map { it.id.value }.toSet(),
        threads = pack.initialStoryThreads.map { it.id.value }.toSet(),
        facts = pack.initialKnowledge.facts.map { it.id.value }.toSet(),
        // An event may schedule another event in the same pack.
        events = pack.events.map { it.id }.toSet(),
        // Scenes are not static content - they exist only while running - so there is no
        // catalogue. A pack names one when an event opens it and names it again when it
        // closes it, which makes it a real cross-reference worth checking.
        scenes = pack.events
            .flatMap { event -> SCENE_TOKEN.findAll(event.toString()).map { it.groupValues[1] } }
            .toSet(),
        authored = authoredFieldValuesOf(pack),
    )

    /**
     * Every `field="value"` pair in the pack's authored content.
     *
     * The data-class rendering of these objects spells each authored reference as
     * `field="value"`, which is namespace-independent and therefore works for plain slugs
     * and prefixed ids alike.
     */
    private fun authoredFieldValuesOf(pack: StoryPack): Set<String> =
        FIELD_VALUE.findAll(
            buildString {
                append(pack.events)
                append(pack.initialStoryThreads)
                append(pack.initialKnowledge.facts)
                append(pack.characters)
                append(pack.locations)
                append(pack.scenarios)
                append(pack.factions)
                append(pack.lore)
            },
        ).map { it.groupValues[1] }.toSet()

    /**
     * The ids an effect names, read from its *typed* fields.
     *
     * ## Why this reads the type instead of scraping the rendering
     *
     * Two earlier versions scanned `toString()` for hyphen-case tokens, and both were
     * wrong in the same way: an effect's `note` is free text. `"the third-floor seal is up
     * for renewal"` contains `third-floor`, which is indistinguishable from a location id
     * by shape - so the test reported correct content as a cross-pack leak. The second
     * attempt tried requiring an id namespace prefix, which then rejected every real
     * reference, because the packs use plain slugs like `"elara"`.
     *
     * Matching on the sealed hierarchy removes the ambiguity entirely: an id is a field
     * whose type *is* an id, and prose is a `String`. The one cost is that a new effect
     * type needs a line here - which is the right trade, because forgetting it means a
     * missing check rather than a false alarm.
     */
    private fun idReferencesIn(effect: EventEffect): List<IdReference> = when (effect) {
        is EventEffect.MoveCharacter -> listOf(
            IdReference(effect.characterId.value, "character"),
            IdReference(effect.toLocationId.value, "location"),
        )
        is EventEffect.ChangeActivity -> listOf(IdReference(effect.characterId.value, "character"))
        is EventEffect.ChangeRelationship -> listOf(
            IdReference(effect.sourceId.value, "character"),
            IdReference(effect.targetId.value, "character"),
        )
        is EventEffect.AdvanceThread -> listOf(IdReference(effect.threadId.value, "thread"))
        is EventEffect.GrantKnowledge -> listOf(
            IdReference(effect.characterId.value, "character"),
            IdReference(effect.factId.value, "fact"),
        )
        is EventEffect.RevokeKnowledge -> listOf(
            IdReference(effect.characterId.value, "character"),
            IdReference(effect.factId.value, "fact"),
        )
        is EventEffect.CreateMemory -> listOf(IdReference(effect.characterId.value, "character"))
        is EventEffect.StartScene -> buildList {
            add(IdReference(effect.locationId.value, "location"))
            effect.threadIds.forEach { add(IdReference(it.value, "thread")) }
        }
        is EventEffect.EndScene -> listOf(IdReference(effect.sceneId.value, "scene"))
        // `ScheduleEvent` names another event in this pack, which is legal and useful
        // (an event chaining into the next beat), so it is checked against the pack's
        // event ids rather than skipped.
        is EventEffect.ScheduleEvent -> listOf(IdReference(effect.eventId, "event"))
        // `SetVariable` carries an arbitrary string key, which is a world variable name
        // and not an entity reference at all.
        is EventEffect.SetVariable -> emptyList()
        is EventEffect.AdvanceTime -> emptyList()
    }

    /** A named reference, with the kind of thing it should name. For error messages. */
    private data class IdReference(val value: String, val kind: String)

    /**
     * The ids a compiled payload names, read from its typed fields.
     *
     * Same reasoning as [idReferencesIn]: a payload's `note` and `reason` are prose, and
     * a rendering scan cannot tell them from an id. So only fields whose type is an id
     * are read.
     */
    private fun idReferencesIn(payload: EventPayload): List<IdReference> = when (payload) {
        is CharacterMoved -> listOf(
            IdReference(payload.characterId.value, "character"),
            IdReference(payload.to.value, "location"),
        )
        is CharacterEnteredScene -> listOf(IdReference(payload.characterId.value, "character"))
        is CharacterLeftScene -> listOf(IdReference(payload.characterId.value, "character"))
        is RelationshipChanged -> listOf(
            IdReference(payload.sourceId.value, "character"),
            IdReference(payload.targetId.value, "character"),
        )
        is KnowledgeDiscovered -> listOf(
            IdReference(payload.characterId.value, "character"),
            IdReference(payload.factId.value, "fact"),
        )
        is KnowledgeRevoked -> listOf(IdReference(payload.characterId.value, "character"))
        is StoryThreadAdvanced -> listOf(IdReference(payload.threadId.value, "thread"))
        is SceneStarted -> listOf(IdReference(payload.locationId.value, "location"))
        is SceneEnded -> listOf(IdReference(payload.sceneId.value, "scene"))
        is PromiseMade -> listOf(
            IdReference(payload.keeperId.value, "character"),
            IdReference(payload.beneficiaryId.value, "character"),
        )
        // `PromiseResolved` carries no character: it names a promise, whose id is minted
        // from a promise the pack already authored.
        is LocationEntered -> listOf(IdReference(payload.locationId.value, "location"))
        // Remaining payloads carry counters, values, moods and prose only - none of which
        // is an entity reference - so there is nothing to check in them.
        else -> emptyList()
    }

    /**
     * A stable description of a seed, for comparing two compiles of the same pack.
     *
     * The payload's data-class rendering is exactly right: two compiles that produce
     * equal payloads must render identically.
     */
    private fun describe(seed: SeedEvent): String =
        "${seed.payload}|${seed.delayMinutes}|${seed.note}"

    private companion object {
        const val COUNTER_PREFIX = "evt-"

        /** Prefixes derived from a pack-owned id at runtime. */
        val DERIVED_PREFIXES = listOf("scene-", "mem-", COUNTER_PREFIX)

        val ID_TOKEN = Regex("""\b([a-z][a-z0-9]*(?:-[a-z0-9]+)+)\b""")
        val FIELD_VALUE = Regex("""\w+="([a-z][a-z0-9-]*)"""")
        val SCENE_TOKEN = Regex("""\b(scene-[a-z0-9-]+)\b""")
    }
}
