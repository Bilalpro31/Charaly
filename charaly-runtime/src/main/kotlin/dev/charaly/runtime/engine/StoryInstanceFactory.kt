package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterRuntime
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.WorldClock
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.WorldState
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.domain.events.CharacterActivityChanged
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.KnowledgeDiscovered
import dev.charaly.runtime.domain.knowledge.KnowledgeStore
import dev.charaly.runtime.domain.memory.MemoryStore

/**
 * Creates a running [StoryInstance] from a static [StoryPack].
 *
 * Initial state is installed through explicit events rather than by writing
 * fields directly, so there is exactly ONE path that mutates the world and
 * pack-authored behaviour is identical to runtime behaviour.
 */
object StoryInstanceFactory {

    fun create(
        pack: StoryPack,
        instanceId: StoryInstanceId = StoryInstanceId("story-${pack.id.value}"),
    ): StoryInstance {
        val definition = WorldDefinition(pack.characters, pack.locations)
        val engine = EventEngine(definition)
        val startTime = pack.initialWorldState.startTime

        var instance = StoryInstance(
            id = instanceId,
            storyPackId = pack.id,
            packTitle = pack.title,
            worldState = WorldState(
                worldClock = WorldClock(startTime),
                locations = pack.locations.associateBy { it.id },
                variables = pack.initialWorldState.variables.associateBy { it.key },
                characters = pack.characters.associate { it.id to initialRuntime(it, pack) },
                relationships = pack.initialRelationships.associateBy { it.key() },
                storyThreads = pack.initialStoryThreads.associateBy { it.id },
            ),
            knowledge = KnowledgeStore.EMPTY.withFacts(pack.initialKnowledge.facts),
            memories = MemoryStore.EMPTY.addAll(pack.initialKnowledge.authoredMemories),
            focusCharacterId = pack.characters.firstOrNull()?.id,
        )

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

        pack.initialWorldState.startActivities.forEach { (characterId, activity) ->
            instance = engine.applyImmediately(
                instance,
                CharacterActivityChanged(characterId, activity),
                EventOrigin.STORY_PACK,
            ).applied()
        }

        // Authored events: delay 0 fires now, later ones wait in the queue.
        pack.initialEvents.forEach { seed ->
            val scheduled = engine.scheduleEvent(
                instance = instance,
                payload = seed.payload,
                at = startTime.plusMinutes(seed.delayMinutes),
                origin = EventOrigin.STORY_PACK,
                note = seed.note.ifBlank { "from story pack" },
            )
            instance = when (scheduled) {
                is ScheduleResult.Scheduled -> scheduled.instance
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

        return created
    }

    private fun EventApplication.applied(): StoryInstance =
        (this as? EventApplication.Applied)?.instance
            ?: error("story pack start event was rejected: $this")

    private fun initialRuntime(character: CharacterDefinition, pack: StoryPack): CharacterRuntime =
        CharacterRuntime(
            characterId = character.id,
            name = character.name,
            locationId = pack.startLocationOf(character.id),
            activity = pack.initialWorldState.startActivities[character.id] ?: CharacterActivity.IDLE,
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
        id = dev.charaly.runtime.domain.LocationId("library"),
        name = "The Library",
        description = "Dusty shelves, a locked cabinet, warm lamplight.",
        connections = listOf(dev.charaly.runtime.domain.LocationId("square")),
    )

    val square: Location = Location(
        id = dev.charaly.runtime.domain.LocationId("square"),
        name = "Market Square",
        description = "Noise, fruit stalls, and a fountain.",
        connections = listOf(dev.charaly.runtime.domain.LocationId("library")),
    )

    val alice: CharacterDefinition = CharacterDefinition(
        id = dev.charaly.runtime.domain.CharacterId("alice"),
        name = "Alice",
        description = "A bookseller with ink-stained fingers.",
        personality = "dry, observant, allergic to small talk",
        background = "Runs the only bookshop in town.",
        goals = listOf("Recover the ledger her father hid"),
        greeting = "Careful. Some pages here are not for sale.",
    )

    val bob: CharacterDefinition = CharacterDefinition(
        id = dev.charaly.runtime.domain.CharacterId("bob"),
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
                startTime = dev.charaly.runtime.domain.StoryTime(day = 1, hour = 21, minute = 0),
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
                dev.charaly.runtime.domain.StoryThread(
                    id = dev.charaly.runtime.domain.ThreadId("thread-ledger"),
                    title = "The hidden ledger",
                    status = StoryThreadStatus.ACTIVE,
                    stage = 1,
                    description = "Someone hid Alice's father's ledger.",
                    involvedCharacterIds = listOf(alice.id),
                    relevantLocationIds = listOf(library.id),
                ),
                dev.charaly.runtime.domain.StoryThread(
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
