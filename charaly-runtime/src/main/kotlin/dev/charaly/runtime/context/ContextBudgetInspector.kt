package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.WorldDefinition

/**
 * Context Engine 2.0: what goes into the prompt, and why.
 *
 * ## Why sections
 *
 * The previous budget was three numbers - transcript turns, memory count, character
 * ceiling. That cannot express the thing that actually goes wrong: **the right thing
 * being crowded out by the wrong thing.**
 *
 * A scene with forty NPCs in the location graph produces a location description that
 * names all of them, and that paragraph is larger than the memory that tells the
 * character why they are there. The character still sees the location, still answers
 * coherently, and has quietly forgotten why the scene matters. Nothing crashes and
 * nothing looks wrong.
 *
 * So every part of the prompt is a *section* with a priority and its own ceiling, and
 * the assembler spends the budget in priority order. A low-priority section that does
 * not fit is dropped, and the drop is reported rather than hidden - a developer can
 * see "4 memories were cut" instead of wondering why the character has amnesia.
 *
 * ## The order
 *
 * Descending priority:
 *
 * ```
 *  1  system instructions   never cut; the rules that make the model safe
 *  2  model protocol       never cut; how proposals are emitted
 *  3  current scene        where and when, plus the objective
 *  4  participants         who is actually here
 *  5  location             the place itself
 *  6  character knowledge  what this character is allowed to know
 *  7  active threads       what is going on
 *  8  relationships        who matters and how
 *  9  memories             what this character remembers
 * 10  recent conversation  the transcript
 * 11  user input           what was just said
 * ```
 *
 * Knowledge outranks memories deliberately. A character who has forgotten a
 * conversation is recoverable; a character who acts on something they were never told
 * is not.
 *
 * ## Determinism
 *
 * Pure, and a function of (definition, instance, character). No randomness, no clock
 * beyond the story clock already in the instance. The same turn always assembles the
 * same prompt, which is what makes "the model received X" checkable.
 */
class ContextBudgetInspector(
    private val definition: WorldDefinition,
    private val budget: ContextBudget = ContextBudget(),
) {

    /**
     * One part of the prompt.
     *
     * [rendered] is exactly what would be sent, so the inspector shows the model the
     * real thing rather than a summary of it.
     */
    data class ContextSection(
        val id: String,
        val title: String,
        val priority: Int,
        val rendered: String,
        val itemCount: Int,
        val dropped: Int,
        val alwaysIncluded: Boolean = false,
    ) {
        val chars: Int get() = rendered.length

        val isEmpty: Boolean get() = rendered.isBlank()

        /** This section wanted more room than it got. */
        val wasTruncated: Boolean get() = dropped > 0
    }

    /** The full picture, for the developer inspector. */
    data class AssembledContext(
        val sections: List<ContextSection>,
        val systemPrompt: String,
        val totalChars: Int,
        val budgetChars: Int,
    ) {
        val withinBudget: Boolean get() = totalChars <= budgetChars

        val overflow: Int get() = maxOf(0, totalChars - budgetChars)

        val droppedItems: Int get() = sections.sumOf { it.dropped }

        fun section(id: String): ContextSection? = sections.firstOrNull { it.id == id }

        /** One line per section, in priority order, for a compact readout. */
        fun describe(): String = buildString {
            appendLine("total: $totalChars / $budgetChars chars" + if (withinBudget) "" else "  OVERFLOW +$overflow")
            sections.forEach { section ->
                val cut = if (section.wasTruncated) "  (-${section.dropped} dropped)" else ""
                appendLine(
                    "[${section.priority.toString().padStart(2)}] ${section.title}: " +
                        "${section.chars} chars, ${section.itemCount} items$cut",
                )
            }
        }
    }

    /**
     * Builds every section, then spends the budget in priority order.
     *
     * Sections marked [ContextSection.alwaysIncluded] are kept regardless of budget.
     * There are exactly two of them - system instructions and the action protocol -
     * because a prompt that drops its own rules produces a model that narrates the
     * user's next line, and no amount of saved context is worth that.
     */
    fun assemble(
        instance: StoryInstance,
        characterId: CharacterId,
        userInput: String = "",
        transcriptTurns: Int = budget.transcriptTurns,
    ): AssembledContext {
        val scene = dev.charaly.runtime.director.SceneDirector(definition)
            .directScene(instance, characterId)
            ?: error("cannot build context for $characterId: no scene")
        val context = ContextBuilder(definition, budget)
            .buildContext(instance, scene, characterId, userInput, transcriptTurns)

        val candidates = sectionsOf(context, instance, characterId)
        val fixed = candidates.filter { it.alwaysIncluded }
        val flexible = candidates.filterNot { it.alwaysIncluded }.sortedByDescending { it.priority }

        val fixedChars = fixed.sumOf { it.chars }
        var remaining = budget.maxChars - fixedChars

        val kept = mutableListOf<ContextSection>()
        flexible.forEach { section ->
            // Sections are admitted whole or not at all. Truncating one mid-sentence
            // would produce a prompt that reads as though the story stopped halfway,
            // which is worse for a small model than the section simply being absent.
            if (section.chars <= remaining) {
                remaining -= section.chars
                kept += section
            }
        }

        val finalSections = (fixed + kept).sortedWith(compareBy({ it.priority }, { it.id }))
        val total = finalSections.sumOf { it.chars }
        return AssembledContext(
            sections = finalSections,
            systemPrompt = context.systemPrompt(),
            totalChars = total,
            budgetChars = budget.maxChars,
        )
    }

    /**
     * The section breakdown only, for a UI that wants the shape and not the text.
     *
     * Each section is trimmed to a placeholder so the sizes are indicative without
     * dumping a whole prompt into a list row.
     */
    fun sections(instance: StoryInstance, characterId: CharacterId): List<ContextSection> =
        assemble(instance, characterId).sections

    private fun sectionsOf(
        context: InferenceContext,
        instance: StoryInstance,
        characterId: CharacterId,
    ): List<ContextSection> {
        val scene = context.scene
        val location = definition.location(scene.locationId)

        return listOf(
            ContextSection(
                id = "system",
                title = "System instructions",
                priority = 1,
                rendered = context.systemPrompt(),
                itemCount = 1,
                dropped = 0,
                alwaysIncluded = true,
            ),
            ContextSection(
                id = "protocol",
                title = "Action protocol",
                priority = 2,
                rendered = dev.charaly.runtime.director.ProposedActionParser.INSTRUCTIONS,
                itemCount = 1,
                dropped = 0,
                alwaysIncluded = true,
            ),
            ContextSection(
                id = "scene",
                title = "Current scene",
                priority = 3,
                rendered = buildString {
                    appendLine("scene: ${scene.id.value} at ${scene.locationId.value}")
                    appendLine("story time: ${instance.worldClock.now.format()}")
                    if (context.sceneObjective.isNotBlank()) appendLine("objective: ${context.sceneObjective}")
                    if (context.runtime.activity != dev.charaly.runtime.domain.CharacterActivity.IDLE) {
                        appendLine("doing: ${context.runtime.activity.name.lowercase()}")
                    }
                }.trimEnd(),
                itemCount = 1,
                dropped = 0,
            ),
            ContextSection(
                id = "participants",
                title = "Present",
                priority = 4,
                // Only the people actually in the scene. This is the section the old
                // location description used to swallow: a big city with many NPCs in it
                // must not become a prompt that names all of them.
                rendered = scene.participants.joinToString("\n") { participant ->
                    val runtime = instance.characters[participant]
                    val name = runtime?.name ?: definition.character(participant)?.name ?: participant.value
                    val relationship = instance.worldState.relationship(characterId, participant)
                    val note = relationship?.describe(characterId)?.takeIf { it.isNotBlank() } ?: "not really known yet"
                    "- $name: $note"
                },
                itemCount = scene.participants.size,
                dropped = 0,
            ),
            ContextSection(
                id = "location",
                title = "Location",
                priority = 5,
                rendered = location?.description.orEmpty(),
                itemCount = if (location == null) 0 else 1,
                dropped = 0,
            ),
            ContextSection(
                id = "knowledge",
                title = "What you know",
                priority = 6,
                rendered = context.knowledge.joinToString("\n") { "- ${it.render()}" },
                itemCount = context.knowledge.size,
                dropped = 0,
            ),
            ContextSection(
                id = "threads",
                title = "Active threads",
                priority = 7,
                rendered = scene.activeThreadIds.mapNotNull { instance.storyThreads[it] }
                    .joinToString("\n") { thread ->
                        "- ${thread.title} (stage ${thread.stage}): ${thread.description}"
                    },
                itemCount = scene.activeThreadIds.size,
                dropped = 0,
            ),
            ContextSection(
                id = "relationships",
                title = "Relationships",
                priority = 8,
                rendered = context.relationships.joinToString("\n") { rel ->
                    "- ${otherName(context, rel)}: ${rel.describeBriefly().ifBlank { rel.describe(context.runtime.characterId) }}"
                },
                itemCount = context.relationships.size,
                dropped = 0,
            ),
            ContextSection(
                id = "memories",
                title = "Memories",
                priority = 9,
                rendered = context.memories.joinToString("\n") { "- (${it.importance}/5) ${it.content}" },
                itemCount = context.memories.size,
                dropped = 0,
            ),
            ContextSection(
                id = "history",
                title = "Recent conversation",
                priority = 10,
                rendered = context.recentTranscript.joinToString("\n") { "${it.role}: ${it.text}" },
                itemCount = context.recentTranscript.size,
                dropped = 0,
            ),
            ContextSection(
                id = "input",
                title = "What you were just told",
                priority = 11,
                rendered = context.userInput,
                itemCount = if (context.userInput.isBlank()) 0 else 1,
                dropped = 0,
            ),
        )
    }

    private fun otherName(context: InferenceContext, rel: dev.charaly.runtime.domain.Relationship): String =
        if (context.runtime.characterId == rel.sourceId) rel.targetId.value else rel.sourceId.value

    companion object {
        /** Priorities, named, so the table above is not just a comment. */
        const val PRIORITY_SYSTEM = 1
        const val PRIORITY_PROTOCOL = 2
        const val PRIORITY_SCENE = 3
        const val PRIORITY_PARTICIPANTS = 4
        const val PRIORITY_LOCATION = 5
        const val PRIORITY_KNOWLEDGE = 6
        const val PRIORITY_THREADS = 7
        const val PRIORITY_RELATIONSHIPS = 8
        const val PRIORITY_MEMORIES = 9
        const val PRIORITY_HISTORY = 10
        const val PRIORITY_INPUT = 11
    }
}