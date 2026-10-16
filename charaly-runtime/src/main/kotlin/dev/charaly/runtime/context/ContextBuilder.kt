package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.inference.ChatMessage
import dev.charaly.runtime.inference.ChatRole
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
    /**
     * Optional cache for the static half of a prompt. See [ContextCache].
     *
     * Null by default so the builder's behaviour is unchanged unless a caller opts in;
     * the cache is a performance concern and never a correctness one.
     */
    private val cache: ContextCache? = null,
    /** Version counters. Paired with [cache]; also optional. */
    private val epochs: ContextEpoch? = null,
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

        // The memory block is the part of the prompt most likely to be *large* and most
        // likely to be *stable* - a scene is usually several turns long, and the same
        // top-scoring memories come back every one of them. So it is the section most
        // worth caching, and the one whose contents are declared precisely here.
        //
        // `render` is only invoked on a miss. On a hit, none of the joining and
        // formatting below happens at all.
        val memoryBlock: String = cachedSection(
            instance = instance,
            characterId = characterId,
            scene = scene,
            section = "memory",
        ) { renderMemories(memories, instance, scene, characterId) }

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
            // PHASE 10: decided here, from the turn's own line and the player's earlier
            // lines, so the rule is a value the prompt is rendered from rather than a
            // sentence buried in it. Read-only, and it adds nothing to the prompt beyond
            // the two lines it replaces - the transcript is already in context, so no
            // history is repeated to decide this.
            replyLanguage = ReplyLanguage.forTurn(
                line = userInput,
                previousPlayerLines = transcript
                    .filter { it.role == dev.charaly.runtime.domain.TranscriptRole.USER }
                    .map { it.text },
            ),
            /**
             * Pre-rendered so a cache hit does real work.
             *
             * The alternative - passing the [dev.charaly.runtime.domain.memory.Memory]
             * list and letting the prompt builder format it - would mean re-serialising
             * the same memories on every turn, which is exactly the cost this section is
             * cached to avoid.
             */
            preRenderedMemories = memoryBlock,
            worldFacts = observableVariables(instance, scene.locationId),
            // Read for this character only. There is no bulk accessor on the store
            // that returns everyone's minds, so getting this wrong requires going out
            // of the store's way on purpose.
            mind = instance.knowledge.mind(characterId).takeIf { !it.isEmpty() },
            // The world's own clock reading, read here and never reconstructed. A turn
            // that advanced the world from 22:40 to 22:52 therefore produces a genuinely
            // different prompt on the next turn - which is the property "WorldClock ->
            // ContextBuilder -> Model" has to have.
            worldTime = instance.worldClock.now,
            // What the event engine says changed, oldest first. Causal links are the one
            // retained account of a world change; the events themselves leave the queue.
            recentEvents = instance.worldState.recentChanges(budget.recentEvents),
            commitments = instance.worldState.commitments.commitmentsOf(characterId),
            owedToThem = instance.worldState.commitments.promisesOwedTo(characterId)
                .map { promise ->
                    "${promise.keeperId.value} promised you that ${promise.text}" +
                        if (promise.isOverdueAt(instance.worldClock.now)) " (you are waiting on this)" else ""
                },
        )
    }

    /** Context -> engine request. The ContextBuilder is the only prompt builder. */
    fun buildRequest(
        context: InferenceContext,
        params: GenerationParams = GenerationParams(),
    ): InferenceRequest {
        val allMessages = buildList {
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
        // ContextBudget.maxChars: trim the transcript from the oldest end when the assembled
        // prompt would exceed the character ceiling. The user's current line and the volatile
        // tail are never trimmed — they are the most important content. Trimming is
        // deterministic: oldest entries first, never splitting a single entry.
        val messages = trimToBudget(allMessages, context)
        // LAYOUT: the constant text first, the transcript next, everything that moves on an
        // ordinary turn LAST. The KV prefix cache reuses the longest identical leading token
        // run, so this ordering is worth real milliseconds: with the state block sitting
        // ahead of the transcript, "story time:" and "WHAT JUST HAPPENED" - which change on
        // EVERY turn - ended the shared prefix and the whole conversation behind them was
        // re-evaluated each time (measured warm reuse 0.34). Folding the volatile tail onto
        // the turn's own user line keeps it after the transcript for every chat template and
        // every plain-format fallback alike, so no template's system-message handling can
        // hoist it back to the front. It is rendered only; the transcript still stores the
        // player's words alone.
        val tail = context.systemPromptVolatile().trim()
        val tailFoldedIntoUserLine = tail.isNotEmpty() && messages.lastOrNull()?.role == ChatRole.USER
        val framed = if (tailFoldedIntoUserLine) {
            val last = messages.last()
            messages.dropLast(1) + last.copy(content = last.content + "\n\n" + tail)
        } else {
            // Degenerate shape (no user line at all): keep the state in the system block
            // rather than dropping it - losing it would change what the model knows.
            messages
        }
        val systemPrompt = buildString {
            // The action protocol is part of the system block: without it the model is never
            // told that it may propose a world change, so it only ever narrates. This is the
            // one place the model learns the schema; the parser and validator are unchanged.
            // It is constant text, so it belongs ahead of the transcript where the cache keeps it.
            append(context.systemPromptStable().trimEnd())
            append("\n\n")
            append(dev.charaly.runtime.director.ProposedActionParser.INSTRUCTIONS)
            if (tail.isNotEmpty() && !tailFoldedIntoUserLine) {
                append("\n\n")
                append(tail)
            }
        }
        return InferenceRequest(
            systemPrompt = systemPrompt,
            messages = framed,
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

    /**
     * Trims the transcript from the oldest end when the assembled prompt would exceed
     * [ContextBudget.maxChars]. The user's current line and the volatile tail are never
     * trimmed. Trimming is deterministic: oldest entries first, never splitting a single
     * entry. The ceiling is approximate (character count, not token count) by design —
     * mobile tokenizers differ and an estimate is enough to enforce a budget.
     */
    private fun trimToBudget(
        messages: List<ChatMessage>,
        context: InferenceContext,
    ): List<ChatMessage> {
        val tail = context.systemPromptVolatile().trim()
        // The assembled prompt is: stable block + action protocol + (transcript, with the
        // volatile tail folded into the last user line) + the tail itself when there is no
        // user line to fold it into. Both shapes cost systemLen + tailLen + every message.
        // `messages` is counted once; the tail is counted once, whether it ends up in the
        // system block or inside the final user message.
        val systemLen = context.systemPromptStable().trimEnd().length +
            dev.charaly.runtime.director.ProposedActionParser.INSTRUCTIONS.length + 2
        val tailLen = if (tail.isNotEmpty()) tail.length + 2 else 0
        val ceiling = budget.maxChars
        var total = systemLen + tailLen + messages.sumOf { it.content.length }
        if (total <= ceiling || messages.size <= 1) return messages
        // Oldest entry first, whole entries only. The last message is never dropped: it is
        // the turn's own user line, and trimming it would answer a question nobody asked.
        val trimmed = messages.toMutableList()
        while (trimmed.size > 1 && total > ceiling) {
            total -= trimmed.removeAt(0).content.length
        }
        return trimmed
    }

    /**
     * Runs [render] through the cache when one is configured.
     *
     * Falls through to a direct call when there is no cache, so an unconfigured builder
     * costs exactly what it did before this existed.
     *
     * The `sources` list is what makes the key sensitive to the real inputs. It names
     * every memory the section is built from, so a new memory, a retracted memory, or a
     * changed importance all produce a different key - without the cache having to guess
     * which of those happened.
     */
    private fun cachedSection(
        instance: StoryInstance,
        characterId: CharacterId,
        scene: Scene,
        section: String,
        render: () -> String,
    ): String {
        val cache = this.cache ?: return render()
        val sources = buildList {
            add("budget:${budget.memories}")
            add("participants:${scene.participantSet().map { it.value }.sorted().joinToString(",")}")
            // Total memory count catches growth; the per-memory ids below catch
            // everything else, including a retracted memory or a changed importance.
            add("memoryCount:${instance.memories.size}")
            instance.memories.selectedFor(characterId, scene, budget.memories).forEach {
                add("m:${it.id.value}:${it.importance}")
            }
        }
        return cache.stableSection(
            storyId = instance.id,
            characterId = characterId,
            locationId = scene.locationId,
            sceneKey = sceneKeyOf(scene),
            epoch = epochs?.current(instance.id) ?: 0L,
            sources = sources,
            render = render,
        )
    }

    /**
     * A stable identity for "this moment of this scene".
     *
     * Includes the active threads because a scene's objective is derived from them: two
     * scenes with the same participants and location but a different live thread are
     * genuinely different contexts, and caching them as one would serve the wrong prompt.
     */
    private fun sceneKeyOf(scene: Scene): String = buildString {
        append(scene.id.value)
        append('@')
        append(scene.mood)
        append(':')
        scene.activeThreadIds.map { it.value }.sorted().forEach { append(it).append('|') }
    }

    /** Renders the memory section exactly as `InferenceContext.systemPrompt` expects it. */
    private fun renderMemories(
        memories: List<dev.charaly.runtime.domain.memory.Memory>,
        instance: StoryInstance,
        scene: Scene,
        characterId: CharacterId,
    ): String = buildString {
        if (memories.isEmpty()) return@buildString
        appendLine("WHAT YOU REMEMBER")
        memories.forEach { appendLine("- (${it.importance}/5) ${it.content}") }
    }.trimEnd()

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
        // NARRATION and SYSTEM are not character speech. Rendering them as assistant turns
        // would make the model treat narration as something a character said, which is
        // semantically wrong and pollutes the conversation history.
        dev.charaly.runtime.domain.TranscriptRole.NARRATION -> ChatMessage.system(text)
        dev.charaly.runtime.domain.TranscriptRole.SYSTEM -> ChatMessage.system(text)
    }
}

/** Explicit context budget. Mobile models have small context windows. */
data class ContextBudget(
    /** How many past turns to include. */
    val transcriptTurns: Int = 6,
    /** How many memories to include. */
    val memories: Int = 6,
    /**
     * How many recent world changes (causal links) to include.
     *
     * Count-bounded like every other knob here, because "recent events" grows without
     * limit over a long session and this is the one line that has to say where it stops.
     */
    val recentEvents: Int = 4,
    /** Rough character ceiling for the assembled prompt. */
    val maxChars: Int = 6000,
) {
    companion object {
        val DEFAULT = ContextBudget()
        val TINY = ContextBudget(transcriptTurns = 2, memories = 2, maxChars = 2000)
        val GENEROUS = ContextBudget(transcriptTurns = 12, memories = 10, maxChars = 16000)
    }
}
