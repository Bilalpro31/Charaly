package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterRuntime
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.domain.memory.Memory

/**
 * A *selected* slice of story state for exactly one character in exactly one
 * moment.
 *
 * Everything here has already been chosen by the ContextBuilder. Nothing in this
 * class reaches back into the world, and nothing in it is allowed to be omniscient.
 */
data class InferenceContext(
    val character: CharacterDefinition,
    val runtime: CharacterRuntime,
    val scene: Scene,
    val locationDescription: String,
    val knowledge: List<Fact>,
    val memories: List<Memory>,
    val relationships: List<Relationship>,
    val sceneObjective: String = "",
    val facts: List<Fact> = emptyList(),
    val recentTranscript: List<TranscriptEntry> = emptyList(),
    val userInput: String = "",
    val worldFacts: Map<String, String> = emptyMap(),
) {
    /**
     * Approximate context cost. Deliberately a character-count estimate: mobile
     * tokenizers differ, and an estimate is enough to enforce a budget.
     */
    fun estimatedChars(): Int {
        var total = systemPrompt().length
        total += character.personaBlock().length
        total += knowledge.sumOf { it.render().length }
        total += memories.sumOf { it.content.length }
        total += relationships.size * 80
        total += scene.describe().length
        total += recentTranscript.sumOf { it.text.length }
        total += userInput.length
        return total
    }

    /**
     * The system prompt for the selected character.
     *
     * Two rules are encoded here and they are the whole point of the class:
     *  1. the model is a LANGUAGE GENERATOR, not the world simulator;
     *  2. the model may only speak from this character's knowledge.
     */
    fun systemPrompt(): String = buildString {
        appendLine("You are ${character.name}, a character inside a living story world.")
        appendLine()
        appendLine("WORLD RULES")
        appendLine("- You do not run the world. Locations, time, relationships and memories are managed outside you.")
        appendLine("- Only state what ${character.name} personally knows or perceives right now. Never invent facts about")
        appendLine("  places, other people, or earlier events unless they appear below.")
        appendLine("- If you do not know something, say so in character instead of guessing.")
        appendLine("- Never speak for the user, and never output system markers.")
        appendLine()
        appendLine("YOUR IDENTITY")
        append(character.personaBlock())
        appendLine()
        appendLine("WHAT YOU KNOW")
        if (knowledge.isEmpty()) {
            appendLine("- nothing beyond what is said in this conversation")
        } else {
            knowledge.forEach { appendLine("- ${it.render()}") }
        }
        if (worldFacts.isNotEmpty()) {
            appendLine()
            appendLine("WORLD FACTS YOU CAN OBSERVE")
            worldFacts.forEach { (key, value) -> appendLine("- $key: $value") }
        }
        if (memories.isNotEmpty()) {
            appendLine()
            appendLine("WHAT YOU REMEMBER")
            memories.forEach { appendLine("- (${it.importance}/5) ${it.content}") }
        }
        if (relationships.isNotEmpty()) {
            appendLine()
            appendLine("PEOPLE YOU KNOW")
            relationships.forEach { rel ->
                val name = otherName(rel)
                appendLine("- $name: ${rel.describe(runtime.characterId)}")
            }
        }
        appendLine()
        appendLine("RIGHT NOW")
        appendLine("- scene: ${scene.id.value} at ${scene.locationId.value}")
        if (locationDescription.isNotBlank()) appendLine("- place: $locationDescription")
        if (runtime.activity != CharacterActivity.IDLE) {
            appendLine("- you are ${runtime.activity.name.lowercase()}")
        }
        if (scene.participants.isNotEmpty()) {
            appendLine("- present: ${scene.participants.joinToString { it.value }}")
        }
        if (sceneObjective.isNotBlank()) appendLine("- current situation: $sceneObjective")
        appendLine()
        appendLine("Stay in character. Reply as ${character.name} would, in the present tense.")
    }

    private fun otherName(rel: Relationship): String = when (runtime.characterId) {
        rel.sourceId -> rel.targetId.value
        else -> rel.sourceId.value
    }
}
