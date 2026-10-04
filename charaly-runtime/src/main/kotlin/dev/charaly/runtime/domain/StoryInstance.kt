package dev.charaly.runtime.domain

import dev.charaly.runtime.domain.events.EventQueue
import dev.charaly.runtime.domain.knowledge.KnowledgeStore
import dev.charaly.runtime.domain.memory.MemoryStore
import kotlinx.serialization.Serializable

/**
 * A RUNNING story: one playthrough of one [StoryPack].
 *
 * Ownership (section 33 of the Charaly architecture):
 * ```
 * StoryInstance      owns world state, knowledge, memories, queue, transcript
 *   └ WorldState     owns clock, locations, variables, character runtimes,
 *                    relationships, threads, scenes
 *   ├ KnowledgeStore owns world truth + per-character knowledge
 *   ├ MemoryStore    owns persistent memories
 *   ├ EventQueue     owns pending events
 *   └ Conversation   owns the transcript (NOT world truth)
 * ```
 * The UI owns none of this; it renders projections produced by the runtime.
 */
@Serializable
data class StoryInstance(
    val id: StoryInstanceId,
    val storyPackId: StoryPackId,
    val packTitle: String,
    val worldState: WorldState,
    val knowledge: KnowledgeStore = KnowledgeStore.EMPTY,
    val memories: MemoryStore = MemoryStore.EMPTY,
    val eventQueue: EventQueue = EventQueue.EMPTY,
    val conversation: ConversationHistory = ConversationHistory.EMPTY,
    val currentSceneId: SceneId? = null,
    val focusCharacterId: CharacterId? = null,
    val createdAt: StoryTime = StoryTime.START,
    val updatedAt: StoryTime = StoryTime.START,
    val idCounter: Long = 0L,
) {
    // ---- convenience projections (still owned by worldState) ---------------
    val worldClock: WorldClock get() = worldState.worldClock
    val characters: Map<CharacterId, CharacterRuntime> get() = worldState.characters
    val relationships: Map<RelationshipKey, Relationship> get() = worldState.relationships
    val storyThreads: Map<ThreadId, StoryThread> get() = worldState.storyThreads
    val locations: Map<LocationId, Location> get() = worldState.locations

    fun character(id: CharacterId?): CharacterRuntime? = id?.let { worldState.characters[it] }

    fun characterNames(): List<String> =
        worldState.characters.values.sortedBy { it.characterId.value }.map { it.name }

    fun currentScene(): Scene? = currentSceneId?.let { worldState.activeScenes[it] }

    /** The character this instance is focused on, if any. */
    fun focusCharacter(): CharacterRuntime? = character(focusCharacterId)

    /**
     * Applies a state transition produced by the event engine. The instance is
     * immutable, so a failed event can never leave a half-applied world behind.
     */
    fun evolved(
        worldState: WorldState = this.worldState,
        knowledge: KnowledgeStore = this.knowledge,
        memories: MemoryStore = this.memories,
        eventQueue: EventQueue = this.eventQueue,
        conversation: ConversationHistory = this.conversation,
        currentSceneId: SceneId? = this.currentSceneId,
    ): StoryInstance = copy(
        worldState = worldState,
        knowledge = knowledge,
        memories = memories,
        eventQueue = eventQueue,
        conversation = conversation,
        currentSceneId = currentSceneId,
        updatedAt = worldState.worldClock.now,
    )

    fun summarize(): String = buildString {
        appendLine("StoryInstance ${id.value} (pack ${storyPackId.value})")
        appendLine("Title: $packTitle")
        appendLine(worldState.describe())
        appendLine("knowledge: ${knowledge.factCount} facts / ${knowledge.knowledge.size} characters")
        appendLine("memories: ${memories.size}")
        appendLine("transcript: ${conversation.size} lines")
    }
}
