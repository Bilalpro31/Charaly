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
    // ---- story identity (never world truth) ------------------------------
    /** The user's name for this playthrough. Falls back to the pack title. */
    val title: String = "",
    /** Which opening this story used. */
    val scenarioId: String = "",
    /** The role the player took inside the world. */
    val persona: PersonaBinding = PersonaBinding.EMPTY,
    /**
     * The *resolved* model configuration for this story.
     *
     * Stored per instance on purpose: changing a global default model afterwards
     * must not silently change what an existing story does.
     */
    val modelBinding: dev.charaly.runtime.model.ModelBinding =
        dev.charaly.runtime.model.ModelBinding.EMPTY,
    /** Story-shaped chapters, derived from scenes as the story progresses. */
    val chapters: List<StoryChapter> = emptyList(),
    val chapterCounter: Int = 0,
    /** Set when this story was branched or duplicated from another. */
    val branchOrigin: BranchOrigin = BranchOrigin.NONE,
    /**
     * What this playthrough did to canon.
     *
     * The counterweight to the pack's [dev.charaly.runtime.domain.CanonBible]. Canon is
     * what the author asserted; this is what *this* story is doing instead, and who it
     * affects. Keeping them apart is what stops a story from quietly rewriting the
     * setting and having no way to notice that it has.
     */
    val canonDeviations: CanonLedger = CanonLedger.EMPTY,
    /** Where a branched story came from, when there is one. */
    val branchedFrom: BranchOriginInfo? = null,
    /** packEventId -> story time it last fired. Drives "already fired" and cooldowns. */
    val firedEvents: Map<String, StoryTime> = emptyMap(),
    /** Wall-clock bookkeeping for "last played 12 min ago". Never world state. */
    val sessionMeta: SessionMeta = SessionMeta(),
) {
    // ---- convenience projections (still owned by worldState) ---------------
    val worldClock: WorldClock get() = worldState.worldClock
    val characters: Map<CharacterId, CharacterRuntime> get() = worldState.characters
    val relationships: Map<RelationshipKey, Relationship> get() = worldState.relationships
    val storyThreads: Map<ThreadId, StoryThread> get() = worldState.storyThreads
    val locations: Map<LocationId, Location> get() = worldState.locations

    /** The name to show on a card: the user's title, or the pack title. */
    val displayTitle: String get() = title.ifBlank { packTitle }

    /** The location the player is standing in right now, if any. */
    fun currentLocation(): Location? = currentScene()?.let { scene -> worldState.location(scene.locationId) }
        ?: focusCharacter()?.locationId?.let { worldState.location(it) }

    fun currentChapter(): StoryChapter? = chapters.lastOrNull { it.isCurrent } ?: chapters.lastOrNull()

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
        chapters: List<StoryChapter> = this.chapters,
        chapterCounter: Int = this.chapterCounter,
        firedEvents: Map<String, StoryTime> = this.firedEvents,
    ): StoryInstance = copy(
        worldState = worldState,
        knowledge = knowledge,
        memories = memories,
        eventQueue = eventQueue,
        conversation = conversation,
        currentSceneId = currentSceneId,
        chapters = chapters,
        chapterCounter = chapterCounter,
        firedEvents = firedEvents,
        updatedAt = worldState.worldClock.now,
    )

    fun summarize(): String = buildString {
        appendLine("StoryInstance ${id.value} (pack ${storyPackId.value})")
        appendLine("Title: $displayTitle")
        appendLine(worldState.describe())
        appendLine("knowledge: ${knowledge.factCount} facts / ${knowledge.knowledge.size} characters")
        appendLine("memories: ${memories.size}")
        appendLine("transcript: ${conversation.size} lines")
    }
}

/** The role the player chose, captured at story creation. */
@Serializable
data class PersonaBinding(
    val id: String = "",
    val name: String = "",
    val tagline: String = "",
    val rolePrompt: String = "",
    val userDisplayName: String = "",
) {
    val isSet: Boolean get() = name.isNotBlank() || rolePrompt.isNotBlank()

    companion object {
        val EMPTY = PersonaBinding()

        fun from(template: PersonaTemplate, userName: String = ""): PersonaBinding =
            PersonaBinding(
                id = template.id,
                name = template.name,
                tagline = template.tagline,
                rolePrompt = template.rolePrompt,
                userDisplayName = userName.ifBlank { template.name },
            )
    }
}

/**
 * Wall-clock metadata used by the session UI ("last played 12 min ago").
 *
 * This is the one place Charaly stores real time, and it is deliberately
 * *outside* the deterministic world: story time never depends on it, and a
 * replay produces identical world state regardless of these values.
 */
@Serializable
data class SessionMeta(
    val createdAtEpochMs: Long = 0L,
    val lastPlayedAtEpochMs: Long = 0L,
    val totalTurns: Int = 0,
)

/** Where a branched story came from. */
@Serializable
enum class BranchOrigin {
    NONE,
    DUPLICATED,
    BRANCHED,
}

@Serializable
data class BranchOriginInfo(
    val sourceInstanceId: StoryInstanceId,
    val sourceTitle: String = "",
    val sourceTurn: Int = 0,
    val createdAtEpochMs: Long = 0L,
)
