package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.inference.ChatMessage
import dev.charaly.runtime.inference.InferenceRequest
import dev.charaly.runtime.inference.GenerationParams

/**
 * Selects what goes into the prompt, and refuses to include what the character
 * must not know.
 *
 * This is the only class allowed to translate world state into text for the
 * model. It performs selection and budgeting; it never invents state and it
 * never asks the model anything.
 */
class ContextBuilder(
    private val definition: WorldDefinition,
    private val budget: ContextBudget = ContextBudget(),
) {

    fun buildContext(
        instance: StoryInstance,
        scene: Scene,
        characterId: CharacterId,
        userInput: String = "",
        transcriptTurns: Int = budget.transcriptTurns,
    ): InferenceContext {
        val character = definition.character(characterId)
            ?: error("unknown character $characterId (not in WorldDefinition)")
        val runtime = instance.characters[characterId]
            ?: error("character $characterId has no runtime state in this instance")

        val knowledge = instance.knowledge.visibleTo(characterId)
        val memories = instance.memories.select(
            characterId = characterId,
            limit = budget.memories,
            atLocation = scene.locationId,
            involving = scene.participantSet() - characterId,
        )
        val others = scene.participants.filter { it != characterId }
        // Only relationships the character actually has are reported. Inventing a
        // default entry for every bystander would tell the model more than the
        // world knows about that relationship.
        val relationships = others.mapNotNull { other -> instance.worldState.relationship(characterId, other) }
        val objective = deriveObjective(instance, scene)
        val transcript = instance.conversation.lastTurns(transcriptTurns)
        val facts = knowledge.filter { it.locationId == null || it.locationId == scene.locationId }
        val location = definition.location(scene.locationId)

        return InferenceContext(
            character = character,
            runtime = runtime,
            scene = scene,
            locationDescription = location?.description.orEmpty(),
            knowledge = knowledge,
            memories = memories,
            relationships = relationships,
            sceneObjective = objective,
            facts = facts,
            recentTranscript = transcript,
            userInput = userInput,
            worldFacts = observableVariables(instance, scene.locationId),
        )
    }

    /** Context -> engine request. The ContextBuilder is the only prompt builder. */
    fun buildRequest(
        context: InferenceContext,
        params: GenerationParams = GenerationParams(),
    ): InferenceRequest {
        val messages = buildList {
            context.recentTranscript.forEach { entry -> add(entry.toMessage()) }
            // The user line is frequently already persisted as the last transcript
            // entry. Emitting it twice makes small models answer twice, so dedupe.
            val alreadySaid = context.recentTranscript.lastOrNull()?.let {
                it.role == dev.charaly.runtime.domain.TranscriptRole.USER && it.text == context.userInput
            } == true
            if (context.userInput.isNotBlank() && !alreadySaid) {
                add(ChatMessage.user(context.userInput))
            }
        }
        return InferenceRequest(
            systemPrompt = context.systemPrompt(),
            messages = messages,
            params = params,
            speakerId = context.character.id,
        )
    }

    fun buildRequest(
        instance: StoryInstance,
        scene: Scene,
        characterId: CharacterId,
        userInput: String = "",
        params: GenerationParams = GenerationParams(),
    ): InferenceRequest = buildRequest(buildContext(instance, scene, characterId, userInput), params)

    /** Scene objective: what the scene is currently about, derived from threads. */
    private fun deriveObjective(instance: StoryInstance, scene: Scene): String {
        val threads = scene.activeThreadIds.mapNotNull { instance.storyThreads[it] }
        return when {
            threads.isNotEmpty() -> threads.joinToString("; ") { "${it.title} (stage ${it.stage})" }
            else -> "an ongoing conversation in ${definition.location(scene.locationId)?.name ?: scene.locationId.value}"
        }
    }

    /** Only variables the character could plausibly observe at this location. */
    private fun observableVariables(instance: StoryInstance, locationId: dev.charaly.runtime.domain.LocationId): Map<String, String> =
        instance.worldState.variables.values
            .filter { it.description.isBlank() || it.description.contains(locationId.value, ignoreCase = true) }
            .sortedBy { it.key }
            .associate { it.key to it.value }

    private fun TranscriptEntry.toMessage(): ChatMessage = when (role) {
        dev.charaly.runtime.domain.TranscriptRole.USER -> ChatMessage.user(text)
        dev.charaly.runtime.domain.TranscriptRole.CHARACTER -> ChatMessage.assistant(text, speakerId?.value)
        dev.charaly.runtime.domain.TranscriptRole.NARRATION -> ChatMessage.assistant(text, "Narration")
        dev.charaly.runtime.domain.TranscriptRole.SYSTEM -> ChatMessage.system(text)
    }
}

/** Explicit context budget. Mobile models have small context windows. */
data class ContextBudget(
    /** How many past turns to include. */
    val transcriptTurns: Int = 6,
    /** How many memories to include. */
    val memories: Int = 6,
    /** Rough character ceiling for the assembled prompt. */
    val maxChars: Int = 6000,
) {
    companion object {
        val DEFAULT = ContextBudget()
        val TINY = ContextBudget(transcriptTurns = 2, memories = 2, maxChars = 2000)
        val GENEROUS = ContextBudget(transcriptTurns = 12, memories = 10, maxChars = 16000)
    }
}
