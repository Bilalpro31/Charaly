package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRuntime
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PersonaBinding
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.SeedEvent
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryThread
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.WorldClock
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.WorldState
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.domain.events.BeliefFormed
import dev.charaly.runtime.domain.events.CharacterActivityChanged
import dev.charaly.runtime.domain.events.CharacterMoved
import dev.charaly.runtime.domain.events.CharacterObserved
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.events.MisconceptionFormed
import dev.charaly.runtime.domain.events.SuspicionRaised
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.domain.knowledge.KnowledgeStore
import dev.charaly.runtime.domain.memory.MemoryStore
import dev.charaly.runtime.model.ModelBinding

/**
 * How a new story is opened.
 *
 * Everything the New Story flow collects (scenario, persona, cast, opening place,
 * model) arrives here as one value object. The factory turns it into a
 * [StoryInstance]. The UI never writes world fields directly.
 */
data class StoryCreationOptions(
    val instanceId: StoryInstanceId,
    /** The user's name for this playthrough. */
    val title: String = "",
    val scenario: StartingScenario? = null,
    val persona: PersonaBinding = PersonaBinding.EMPTY,
    /** Opening place. Falls back to the scenario's, then to the focus character. */
    val startLocationId: LocationId? = null,
    /** Who the player is talking to first. */
    val focusCharacterId: CharacterId? = null,
    /** Cast the player chose: these characters are placed at the opening location. */
    val castCharacterIds: List<CharacterId> = emptyList(),
    /** Model configuration resolved by the caller (reproducibility). */
    val modelBinding: ModelBinding = ModelBinding.EMPTY,
    val nowEpochMs: Long = 0L,
) {
    companion object {
        /** The old, minimal entry point: start a pack with everything at defaults. */
        fun defaults(pack: StoryPack, instanceId: StoryInstanceId): StoryCreationOptions =
            StoryCreationOptions(instanceId = instanceId)
    }
}

/**
 * Creates a running [StoryInstance] from a static [StoryPack].
 *
 * Initial state is installed through explicit events rather than by writing
 * fields directly, so there is exactly ONE path that mutates the world and
 * pack-authored behaviour is identical to runtime behaviour.
 */
object StoryInstanceFactory {

    /** Backwards-compatible entry point used by existing tests. */
    fun create(
        pack: StoryPack,
        instanceId: StoryInstanceId = StoryInstanceId("story-${pack.id.value}"),
    ): StoryInstance = create(pack, StoryCreationOptions(instanceId = instanceId))

    fun create(pack: StoryPack, options: StoryCreationOptions): StoryInstance {
        val definition = WorldDefinition(pack.characters, pack.locations)
        val engine = EventEngine(definition)
        val scenario = options.scenario
        val startTime = scenario?.startTime ?: pack.initialWorldState.startTime

        val startLocations = resolveStartLocations(pack, options)
        val focusId = options.focusCharacterId
            ?: scenario?.focusCharacterId
            ?: pack.characters.firstOrNull()?.id

        val openingLocation = options.startLocationId
            ?: scenario?.startLocationId
            ?: startLocations[focusId]
            ?: pack.locations.firstOrNull()?.id

        val startActivities = buildMap {
            putAll(pack.initialWorldState.startActivities)
            putAll(scenario?.startActivities.orEmpty())
        }

        val threads = pack.initialStoryThreads
            .map { thread -> scenario?.threadStages?.get(thread.id.value)?.let { thread.copy(stage = it) } ?: thread }
            .associateBy { it.id }

        val variables = pack.initialWorldState.variables.associateBy { it.key }

        var instance = StoryInstance(
            id = options.instanceId,
            storyPackId = pack.id,
            packTitle = pack.title,
            title = options.title.ifBlank { defaultStoryTitle(pack, scenario) },
            scenarioId = scenario?.id.orEmpty(),
            persona = options.persona,
            modelBinding = options.modelBinding,
            worldState = WorldState(
                worldClock = WorldClock(startTime),
                locations = pack.locations.associateBy { it.id },
                variables = variables,
                characters = pack.characters.associate { it.id to initialRuntime(it, pack, startLocations, startActivities) },
                relationships = pack.initialRelationships.associateBy { it.key() },
                storyThreads = threads,
            ),
            knowledge = KnowledgeStore.EMPTY.withFacts(pack.initialKnowledge.facts),
            memories = MemoryStore.EMPTY.addAll(pack.initialKnowledge.authoredMemories),
            focusCharacterId = focusId,
            createdAt = startTime,
            sessionMeta = dev.charaly.runtime.domain.SessionMeta(
                createdAtEpochMs = options.nowEpochMs,
                lastPlayedAtEpochMs = options.nowEpochMs,
            ),
        )

        // The cast the player chose is placed at the opening location. This is an
        // event like any other, so the audit trail and validation are unchanged.
        instance = applyCastPlacement(instance, openingLocation, options, scenario)

        // Seed knowledge: only ids the pack actually declared.
        pack.initialKnowledge.characterKnowledge.forEach { (characterId, rawFactIds) ->
            rawFactIds.forEach { raw ->
                val factId = runCatching { dev.charaly.runtime.domain.FactId(raw) }.getOrNull() ?: return@forEach
                if (instance.knowledge.fact(factId) == null) return@forEach
                if (definition.character(characterId) == null) return@forEach
                instance = engine.applyImmediately(
                    instance,
                    KnowledgeDiscovered(characterId, factId, via = "story pack start"),
                    EventOrigin.STORY_PACK,
                ).applied()
            }
        }

        // Seed minds through the engine rather than writing them straight into the
        // store. An authored belief is still world state, and seeding it by direct
        // assignment would mean the one thing that is supposed to make this engine
        // trustworthy - everything arrives as a validated, replayable event - did not
        // hold for a character's starting mind.
        pack.initialKnowledge.characterMinds.forEach { (characterId, mind) ->
            if (definition.character(characterId) == null) return@forEach
            val via = "story pack start"
            mind.observations.forEach { observation ->
                instance = engine.applyImmediately(
                    instance,
                    CharacterObserved(
                        characterId = characterId,
                        description = observation.description,
                        locationId = observation.locationId,
                        witnesses = observation.witnesses,
                    ),
                    EventOrigin.STORY_PACK,
                ).applied()
            }
            mind.beliefs.forEach { belief ->
                instance = engine.applyImmediately(
                    instance,
                    BeliefFormed(
                        characterId = characterId,
                        subject = belief.subject,
                        claim = belief.claim,
                        confidence = belief.confidence,
                        via = belief.via.ifBlank { via },
                    ),
                    EventOrigin.STORY_PACK,
                ).applied()
            }
            mind.suspicions.forEach { suspicion ->
                instance = engine.applyImmediately(
                    instance,
                    SuspicionRaised(
                        characterId = characterId,
                        subject = suspicion.subject,
                        claim = suspicion.claim,
                        strength = suspicion.strength,
                        via = suspicion.via.ifBlank { via },
                    ),
                    EventOrigin.STORY_PACK,
                ).applied()
            }
            mind.misconceptions.forEach { error ->
                instance = engine.applyImmediately(
                    instance,
                    MisconceptionFormed(
                        characterId = characterId,
                        subject = error.subject,
                        claim = error.claim,
                        truth = error.truth,
                        via = error.via.ifBlank { via },
                    ),
                    EventOrigin.STORY_PACK,
                ).applied()
            }
        }

        (pack.initialWorldState.startActivities + scenario?.startActivities.orEmpty())
            .forEach { (characterId, activity) ->
                instance = engine.applyImmediately(
                    instance,
                    CharacterActivityChanged(characterId, activity),
                    EventOrigin.STORY_PACK,
                ).applied()
            }

        // Scenario variables the pack did not declare are applied as events, so they
        // appear in the audit trail like every other world change.
        val seeds = openingSeedEvents(pack, scenario, instance) +
            EventProgram.compile(pack, scenario, instance)
        seeds.forEach { seed ->
            val scheduled = engine.scheduleEvent(
                instance = instance,
                payload = seed.payload,
                at = startTime.plusMinutes(seed.delayMinutes),
                origin = EventOrigin.STORY_PACK,
                note = seed.note.ifBlank { "from story pack" },
            )
            instance = when (scheduled) {
                is ScheduleResult.Scheduled -> {
                    // Record the authored event id so cooldowns and one-shot
                    // triggers behave exactly like runtime-scheduled events.
                    val key = seed.note.removePrefix(EventProgram.EVENT_NOTE_PREFIX)
                    if (key.isNotBlank() && seed.note.startsWith(EventProgram.EVENT_NOTE_PREFIX)) {
                        scheduled.instance.copy(
                            firedEvents = scheduled.instance.firedEvents + (key to startTime),
                        )
                    } else {
                        scheduled.instance
                    }
                }
                is ScheduleResult.Rejected -> instance
            }
        }

        var created = engine.processEventsUntil(instance, startTime).instance

        // A thread with progress is by definition in play.
        val normalizedWorld = created.worldState.copy(
            storyThreads = created.worldState.storyThreads.mapValues { (_, thread) ->
                if (thread.status == StoryThreadStatus.DORMANT && thread.stage > 0) {
                    thread.copy(status = StoryThreadStatus.ACTIVE)
                } else {
                    thread
                }
            },
        )
        created = created.copy(
            worldState = normalizedWorld,
            focusCharacterId = created.focusCharacterId
                ?: created.worldState.characters.keys.minByOrNull { it.value },
        )

        // Scenario variables that the pack did not declare are applied as events so
        // they appear in the event log like everything else.
        val withChapters = if (openingLocation != null) {
            val opening = ChapterPlanner.openingChapter(
                instance = created,
                definition = definition,
                locationId = openingLocation,
                focusCharacterId = created.focusCharacterId,
                at = startTime,
            )
            created.copy(chapters = listOf(opening), chapterCounter = 1)
        } else {
            created
        }

        return ChapterPlanner.derive(withChapters, definition)
    }

    // ------------------------------------------------------------------

    private fun defaultStoryTitle(pack: StoryPack, scenario: StartingScenario?): String =
        scenario?.title?.let { "${pack.title} · $it" } ?: pack.title

    private fun resolveStartLocations(
        pack: StoryPack,
        options: StoryCreationOptions,
    ): Map<CharacterId, LocationId> {
        val base = pack.initialWorldState.startLocations.toMutableMap()
        val opening = options.startLocationId
            ?: options.scenario?.startLocationId
        if (opening != null) {
            // The opening cast *starts* in the opening scene. That is declared state,
            // not a movement: issuing CharacterMoved here would either be rejected by
            // adjacency validation or invent a journey that never happened.
            (options.castCharacterIds + listOfNotNull(options.focusCharacterId, options.scenario?.focusCharacterId))
                .distinct()
                .forEach { base[it] = opening }
        }
        return base
    }

    /**
     * Nothing to do at runtime any more.
     *
     * The opening cast is placed by [resolveStartLocations] as declared start state,
     * which is both cheaper and more honest than emitting a movement event for a
     * journey that happens before the story begins.
     */
    private fun applyCastPlacement(
        instance: StoryInstance,
        openingLocation: LocationId?,
        options: StoryCreationOptions,
        scenario: StartingScenario?,
    ): StoryInstance = instance

    /**
     * Authored variable defaults from the scenario that the pack did not declare.
     * They are applied as events (never as a direct field write).
     */
    private fun openingSeedEvents(
        pack: StoryPack,
        scenario: StartingScenario?,
        instance: StoryInstance,
    ): List<SeedEvent> {
        if (scenario == null) return emptyList()
        return scenario.startVariables
            .filterNot { instance.worldState.variables.containsKey(it.key) }
            .map { variable ->
                SeedEvent(
                    payload = WorldVariableSet(variable.key, variable.value),
                    note = "scenario:${scenario.id}",
                )
            }
    }

    private fun EventApplication.applied(): StoryInstance =
        (this as? EventApplication.Applied)?.instance
            ?: error("story pack start event was rejected: $this")

    private fun initialRuntime(
        character: CharacterDefinition,
        pack: StoryPack,
        startLocations: Map<CharacterId, LocationId>,
        startActivities: Map<CharacterId, CharacterActivity>,
    ): CharacterRuntime = CharacterRuntime(
        characterId = character.id,
        name = character.name,
        locationId = startLocations[character.id]
            ?: character.startingLocationId
            ?: pack.startLocationOf(character.id),
        activity = startActivities[character.id]
            ?: character.startingActivity
            ?: CharacterActivity.IDLE,
        activeGoals = pack.initialWorldState.startGoals[character.id] ?: character.goals,
        lastUpdatedAt = pack.initialWorldState.startTime,
    )
}

/**
 * Canonical Charaly demo/test world.
 *
 * The fixture exists to prove knowledge separation: the hidden ledger is in the
 * library, Alice does not know, Bob does.
 */
object SampleWorlds {

    val library: Location = Location(
        id = LocationId("library"),
        name = "The Library",
        description = "Dusty shelves, a locked cabinet, warm lamplight.",
        connections = listOf(LocationId("square")),
    )

    val square: Location = Location(
        id = LocationId("square"),
        name = "Market Square",
        description = "Noise, fruit stalls, and a fountain.",
        connections = listOf(LocationId("library")),
    )

    val alice: CharacterDefinition = CharacterDefinition(
        id = CharacterId("alice"),
        name = "Alice",
        description = "A bookseller with ink-stained fingers.",
        personality = "dry, observant, allergic to small talk",
        background = "Runs the only bookshop in town.",
        goals = listOf("Recover the ledger her father hid"),
        greeting = "Careful. Some pages here are not for sale.",
    )

    val bob: CharacterDefinition = CharacterDefinition(
        id = CharacterId("bob"),
        name = "Bob",
        description = "A lamplighter who never sleeps.",
        personality = "cheerfully blunt",
        background = "Lights the lamps across the lower city.",
        goals = listOf("Find out why the lamps keep going out"),
        greeting = "Evening! You look like someone who needs light.",
    )

    fun libraryPack(id: dev.charaly.runtime.domain.StoryPackId = dev.charaly.runtime.domain.StoryPackId("pack-library")): StoryPack {
        val ledgerFact = dev.charaly.runtime.domain.knowledge.Fact(
            id = dev.charaly.runtime.domain.FactId("fact-ledger"),
            subject = "ledger",
            predicate = "is hidden in",
            description = "Alice's father's ledger is locked inside the cabinet in The Library.",
            locationId = library.id,
            involvedCharacters = listOf(alice.id),
            secret = true,
        )
        val lampFact = dev.charaly.runtime.domain.knowledge.Fact(
            id = dev.charaly.runtime.domain.FactId("fact-lamps"),
            subject = "lamps",
            predicate = "keep going out",
            description = "The lower-city lamps keep going out at the same hour every night.",
        )
        return StoryPack(
            id = id,
            title = "The Lamplighter's Ledger",
            description = "A small town, one hidden ledger, two people who know different halves of it.",
            author = "Charaly",
            characters = listOf(alice, bob),
            locations = listOf(library, square),
            initialWorldState = dev.charaly.runtime.domain.InitialWorldState(
                startTime = StoryTime(day = 1, hour = 21, minute = 0),
                variables = listOf(
                    WorldVariable(
                        key = "library_lamp",
                        type = dev.charaly.runtime.domain.WorldVariableType.BOOLEAN,
                        value = "on",
                        description = "Whether the library lamp burns (library)",
                    ),
                ),
                startLocations = mapOf(alice.id to library.id, bob.id to square.id),
                startActivities = mapOf(bob.id to CharacterActivity.WORKING),
                startGoals = mapOf(alice.id to listOf("Close up the shop")),
            ),
            initialRelationships = listOf(
                Relationship(
                    sourceId = alice.id,
                    targetId = bob.id,
                    relationshipType = dev.charaly.runtime.domain.RelationshipType.ACQUAINTANCE,
                    familiarity = 40,
                    trust = 45,
                ),
            ),
            // WORLD TRUTH: both facts exist. CHARACTER KNOWLEDGE: Alice only knows
            // about the lamps; only Bob knows where the ledger is.
            initialKnowledge = dev.charaly.runtime.domain.InitialKnowledge(
                facts = listOf(ledgerFact, lampFact),
                characterKnowledge = mapOf(
                    bob.id to listOf(ledgerFact.id.value),
                    alice.id to listOf(lampFact.id.value),
                ),
            ),
            initialStoryThreads = listOf(
                StoryThread(
                    id = dev.charaly.runtime.domain.ThreadId("thread-ledger"),
                    title = "The hidden ledger",
                    status = StoryThreadStatus.ACTIVE,
                    stage = 1,
                    description = "Someone hid Alice's father's ledger.",
                    involvedCharacterIds = listOf(alice.id),
                    relevantLocationIds = listOf(library.id),
                ),
                StoryThread(
                    id = dev.charaly.runtime.domain.ThreadId("thread-lamps"),
                    title = "The lamps that go out",
                    status = StoryThreadStatus.DORMANT,
                    stage = 0,
                    involvedCharacterIds = listOf(bob.id),
                    relevantLocationIds = listOf(square.id),
                ),
            ),
        )
    }
}