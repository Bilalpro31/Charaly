package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemoryConsolidator
import dev.charaly.runtime.domain.memory.MemorySubject
import dev.charaly.runtime.domain.memory.MemoryTier
import dev.charaly.runtime.domain.memory.MemoryVisibility

/**
 * The memory panel, as a story feature.
 *
 * ## The problem this fixes
 *
 * "Memory is now a first-class subsystem" and "do not expose raw database records" pull
 * in opposite directions. A layered store is genuinely five tiers with visibility
 * rules; a memory panel that renders all of that is a database viewer with nicer
 * typography, and nobody reads it.
 *
 * The resolution is that a user does not care about tiers - they care about four
 * questions: what just happened, what mattered, who remembers what, and what is this
 * story's larger shape. So the panel is organised around those, and the tier is shown
 * as a short human label where it helps ("What she knows about you") rather than as the
 * storage concept it is.
 */
data class MemoryTab(
    val id: MemoryTabId,
    val label: String,
    /** Shown when this tab has nothing in it. */
    val emptyLabel: String,
)

enum class MemoryTabId {
    /** Everything, newest first. */
    RECENT,

    /** The memories that shaped the story. */
    IMPORTANT,

    /** Grouped by who remembers. */
    CHARACTER,

    /** The durable shape: canon and the live threads. */
    STORY,
}

data class MemoryCard(
    val id: String,
    val text: String,
    /** 1..5 as a short word, never a bare number. */
    val importanceLabel: String,
    val importance: Int,
    /** Who remembers it. */
    val ownerName: String,
    val ownerId: String,
    val timeLabel: String,
    /** Plain-language provenance: "from a conversation", "from an event". */
    val sourceLabel: String,
    /** Plain-language tier: "part of the world", "what she knows about you". */
    val tierLabel: String,
    /** Secrets are shown as such rather than as ordinary memories. */
    val isSecret: Boolean,
    val isPinned: Boolean,
    val canForget: Boolean,
    /** The event that produced this, when there is one. */
    val sourceEventId: String = "",
)

data class MemoryGroup(
    val title: String,
    val subtitle: String,
    val cards: List<MemoryCard>,
)

data class MemoryScreenSnapshot(
    val tabs: List<MemoryTab>,
    val activeTab: MemoryTabId,
    val groups: List<MemoryGroup>,
    val totalCount: Int,
    val tabCounts: Map<MemoryTabId, Int>,
    val emptyState: EmptyState?,
) {
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Builds the memory panel.
 *
 * Pure: the panel is a function of the story's memory store, and rendering it changes
 * nothing. "Forget" is offered as an action, but performing it is a runtime operation
 * through a validated path - the view cannot delete world state.
 */
object MemoryPanelPresenter {

    /** How many memories each tab shows before it becomes a wall again. */
    const val MAX_PER_TAB = 24

    /** Pins are surfaced first and never buried under recency. */
    const val MAX_PINNED_SHOWN = 5

    fun build(
        instance: StoryInstance,
        pack: StoryPack?,
        activeTab: MemoryTabId = MemoryTabId.RECENT,
    ): MemoryScreenSnapshot {
        val definition = dev.charaly.runtime.domain.WorldDefinition(
            pack?.characters.orEmpty().ifEmpty {
                instance.worldState.characters.values.map {
                    dev.charaly.runtime.domain.CharacterDefinition(id = it.characterId, name = it.name)
                }
            },
            pack?.locations.orEmpty().ifEmpty { instance.worldState.locations.values.toList() },
        )

        // Only current memories: a superseded claim is history, and showing it beside its
        // replacement would tell the user two contradictory things are true.
        val current = instance.memories.current()
        fun card(memory: Memory) = toCard(memory, definition.nameOf(memory.characterId), memory.characterId.value)

        val groups = when (activeTab) {
            MemoryTabId.RECENT -> listOfNotNull(
                group(
                    title = "Pinned",
                    subtitle = "Kept no matter what",
                    cards = current.filter { it.pinned }.take(MAX_PINNED_SHOWN).map(::card),
                ),
                group(
                    title = "Recent",
                    subtitle = "What happened lately",
                    cards = current.asReversed().take(MAX_PER_TAB).map(::card),
                ),
            )

            MemoryTabId.IMPORTANT -> listOfNotNull(
                group(
                    title = "Defining",
                    subtitle = "The moments this story turns on",
                    cards = current.filter { it.importance >= 5 }.sortedByDescending { it.createdAt }
                        .take(MAX_PER_TAB).map(::card),
                ),
                group(
                    title = "Important",
                    subtitle = "Still shaping things",
                    cards = current.filter { it.importance in 3..4 }.sortedByDescending { it.createdAt }
                        .take(MAX_PER_TAB).map(::card),
                ),
            )

            MemoryTabId.CHARACTER -> current
                .groupBy { it.characterId }
                .entries
                .sortedByDescending { it.value.size }
                .take(MAX_CHARACTER_GROUPS)
                .map { (characterId, memories) ->
                    MemoryGroup(
                        title = definition.nameOf(characterId),
                        subtitle = describeCount(memories.size, "memory", "memories"),
                        cards = memories.sortedWith(
                            compareByDescending<Memory> { it.pinned }
                                .thenByDescending { it.createdAt },
                        ).take(MAX_PER_TAB).map(::card),
                    )
                }

            MemoryTabId.STORY -> listOfNotNull(
                group(
                    title = "Part of the world",
                    subtitle = "Settled facts nobody has to relive",
                    cards = current.filter { it.tier == MemoryTier.CANON }
                        .sortedByDescending { it.createdAt }.take(MAX_PER_TAB).map(::card),
                ),
                group(
                    title = "Still moving",
                    subtitle = "Things that could still change",
                    cards = current.filter { it.tier == MemoryTier.EPISODIC || it.tier == MemoryTier.SCENE }
                        .sortedByDescending { it.createdAt }.take(MAX_PER_TAB).map(::card),
                ),
            )
        }.filter { it.cards.isNotEmpty() }

        return MemoryScreenSnapshot(
            tabs = tabs(current),
            activeTab = activeTab,
            groups = groups,
            totalCount = current.size,
            tabCounts = tabCounts(current),
            emptyState = if (current.isEmpty()) emptyState() else null,
        )
    }

    /** The memory the player can see but no character shares. */
    fun playerOnlyCount(instance: StoryInstance): Int =
        instance.memories.current().count { it.characterId !in instance.worldState.characters.keys }

    private fun toCard(
        memory: Memory,
        ownerName: String,
        ownerId: String,
    ) = MemoryCard(
        id = memory.id.value,
        text = memory.content,
        importance = memory.importance,
        importanceLabel = importanceLabel(memory.importance),
        ownerName = ownerName,
        ownerId = ownerId,
        timeLabel = memory.createdAt.storyLabel(),
        sourceLabel = sourceLabel(memory.source),
        tierLabel = tierLabel(memory.tier),
        isSecret = memory.visibility == MemoryVisibility.SECRET || memory.visibility == MemoryVisibility.PRIVATE,
        isPinned = memory.pinned,
        canForget = true,
        sourceEventId = memory.sourceEventId?.value.orEmpty(),
    )

    private fun group(title: String, subtitle: String, cards: List<MemoryCard>): MemoryGroup? =
        if (cards.isEmpty()) null else MemoryGroup(title, subtitle, cards)

    private fun tabs(current: List<Memory>): List<MemoryTab> = listOf(
        MemoryTab(MemoryTabId.RECENT, "Recent", "Nothing has happened yet."),
        MemoryTab(MemoryTabId.IMPORTANT, "Important", "Nothing important yet."),
        MemoryTab(MemoryTabId.CHARACTER, "Character", "Nobody remembers anything yet."),
        MemoryTab(MemoryTabId.STORY, "Story", "No settled facts yet."),
    )

    private fun tabCounts(current: List<Memory>): Map<MemoryTabId, Int> = mapOf(
        MemoryTabId.RECENT to current.size,
        MemoryTabId.IMPORTANT to current.count { it.importance >= 4 },
        MemoryTabId.CHARACTER to current.map { it.characterId }.distinct().size,
        MemoryTabId.STORY to current.count { it.tier == MemoryTier.CANON },
    )

    /** 1..5 as a word. A bare number would read as a database column. */
    fun importanceLabel(importance: Int): String = when (importance) {
        5 -> "Defining"
        4 -> "Important"
        3 -> "Noted"
        2 -> "Minor"
        else -> "Trivial"
    }

    fun sourceLabel(source: dev.charaly.runtime.domain.memory.MemorySource): String =
        when (source) {
            dev.charaly.runtime.domain.memory.MemorySource.EVENT -> "From an event"
            dev.charaly.runtime.domain.memory.MemorySource.DIALOGUE -> "From a conversation"
            dev.charaly.runtime.domain.memory.MemorySource.USER_INPUT -> "From you"
            dev.charaly.runtime.domain.memory.MemorySource.AUTHORED -> "Written into the pack"
            dev.charaly.runtime.domain.memory.MemorySource.CONSOLIDATED -> "Rolled up from earlier"
            dev.charaly.runtime.domain.memory.MemorySource.IMPORTED -> "Imported"
        }

    /**
     * The tier, in the user's language.
     *
     * This is the one place the internal concept is allowed to surface, because the
     * relationship tier genuinely tells a player something ("what she knows about you"
     * is not the same kind of fact as "part of the world").
     */
    fun tierLabel(tier: MemoryTier): String = when (tier) {
        MemoryTier.WORKING -> "Right now"
        MemoryTier.SCENE -> "From this scene"
        MemoryTier.EPISODIC -> "Something that happened"
        MemoryTier.SEMANTIC -> "What they make of it"
        MemoryTier.CANON -> "Part of the world"
        MemoryTier.RELATIONSHIP -> "What they know about you"
        MemoryTier.CHARACTER -> "About someone"
        MemoryTier.WORLD -> "How the world is"
        MemoryTier.THREAD -> "Still going on"
        MemoryTier.SECRET -> "A secret"
        MemoryTier.PROMISE -> "A promise"
        MemoryTier.GOAL -> "What they're after"
        MemoryTier.CONSEQUENCE -> "What you caused"
    }

    private fun describeCount(count: Int, singular: String, plural: String): String =
        if (count == 1) "1 $singular" else "$count $plural"

    private fun emptyState() = EmptyState(
        title = "Nothing remembered yet.",
        body = "Memories appear here as the story accumulates them - what people " +
            "experienced, and what they decided about you.",
        artSeed = "charaly-empty-memory",
    )

    const val MAX_CHARACTER_GROUPS = 8
}

/**
 * The operations the memory panel's buttons perform.
 *
 * Exposed as named operations on the runtime rather than as a free-for-all delete, so
 * "Forget" stays a domain event with a timestamp instead of a row disappearing.
 */
object MemoryOperations {

    /** Memories the player is allowed to see, for an "inspect source" view. */
    fun visibleTo(instance: StoryInstance, subject: MemorySubject = MemorySubject.Player) =
        instance.memories.visibleTo(subject)

    /** How consolidation would change the store, without changing it. */
    fun previewConsolidation(instance: StoryInstance): ConsolidationPreview {
        val before = instance.memories.current().size
        val after = MemoryConsolidator.consolidate(instance.memories, instance.worldClock.now)
            .current()
            .size
        return ConsolidationPreview(
            before = before,
            after = after,
            mergedAway = (before - after).coerceAtLeast(0),
        )
    }
}

/** What consolidation would do, so the UI can explain it before it happens. */
data class ConsolidationPreview(
    val before: Int,
    val after: Int,
    /** Duplicates and superseded claims that would leave the current set. */
    val mergedAway: Int,
) {
    val changesAnything: Boolean get() = mergedAway > 0
}