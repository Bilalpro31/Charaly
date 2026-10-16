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
    /**
     * The memory section, already rendered to text.
     *
     * Set by [ContextBuilder] when a cache is configured. `null` here means "render it
     * yourself", which is the correct behaviour for a hand-constructed context - the
     * developer inspector builds one directly - so the field is optional rather than
     * required.
     */
    val preRenderedMemories: String? = null,
    val relationships: List<Relationship>,
    val sceneObjective: String = "",
    val facts: List<Fact> = emptyList(),
    val recentTranscript: List<TranscriptEntry> = emptyList(),
    val userInput: String = "",
    /**
     * The language this reply must be written in. See [ReplyLanguage].
     *
     * PHASE 10. This used to be one English sentence in the middle of [systemPromptStable],
     * which is a request rather than a rule: surrounded by two thousand characters of
     * English world rules and an English character sheet, a 4B model followed its own
     * prior and answered a Turkish player in English. It is now decided outside the
     * prose, rendered as the FIRST block the model reads, and repeated as five words at
     * the end of the prompt.
     *
     * Defaults to the product language rather than to "undetermined", so a context built
     * without an explicit decision - the developer inspector, a test - still tells the
     * model to speak Turkish rather than leaving the question open.
     */
    val replyLanguage: ReplyLanguage = ReplyLanguage.DEFAULT,
    val worldFacts: Map<String, String> = emptyMap(),
    /**
     * What this character has observed, believes and suspects - including what they
     * are wrong about.
     *
     * Owned by the knowledge store and reachable only for [runtime]'s own character,
     * so putting it here cannot leak one character's mind into another's prompt. It is
     * what makes a character able to be *wrong*: facts alone give a model either
     * knowledge or silence, and silence is not an interesting thing to act on.
     */
    val mind: dev.charaly.runtime.domain.knowledge.CharacterMind? = null,
    /**
     * What this character has promised, and what they are still trying to do.
     *
     * Read from the world's commitment ledger rather than derived from memory, because
     * the interesting question is not "what do they remember promising" but "what are
     * they on the hook for *right now*" - and a character who has forgotten their
     * promise still owes it.
     */
    val commitments: List<String> = emptyList(),
    /** Promises made to this character by somebody else. */
    val owedToThem: List<String> = emptyList(),
    /**
     * The world's own clock reading at the moment this context was built.
     *
     * ## Why the model is told the time at all
     *
     * The model does not *own* time - `WorldClock` moves through `EventEngine.advanceTime`
     * and `TurnClockPolicy`, never through prose. But a character who does not know what
     * time it is cannot write a scene that obeys it, so the reading has to be handed over
     * as fact. Without this line the "22:40 -> event -> 22:52" transition was invisible to
     * the model: the world moved and the next prompt looked exactly like the last one.
     */
    val worldTime: dev.charaly.runtime.domain.StoryTime = dev.charaly.runtime.domain.StoryTime.START,
    /**
     * What actually happened recently, as the event engine recorded it. Oldest first.
     *
     * Drawn from `WorldState.causality` - the world's own account of what changed and why -
     * not summarised from the transcript and not invented here. That is what makes "what
     * just happened" a fact the next turn can rely on rather than a recollection.
     */
    val recentEvents: List<String> = emptyList(),
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
     * The system text for the selected character.
     *
     * Two rules are encoded here and they are the whole point of the class:
     *  1. the model is a LANGUAGE GENERATOR, not the world simulator;
     *  2. the model may only speak from this character's knowledge.
     *
     * It is the concatenation of [systemPromptStable] and [systemPromptVolatile]. The split
     * is by CHURN RATE, because churn rate is what the KV prefix cache cares about:
     * everything that can change on an ordinary turn travels in the volatile tail, which
     * `ContextBuilder.buildRequest` renders AFTER the transcript - so a moving clock or a
     * fresh memory no longer throws away the cached keys for the whole conversation behind
     * it. Measured in Phase 6 (`PrefixBreakpointDiagnosticTest`, char level, plus the
     * real-weight `InferenceProfileTest` KV figures): with the old order the shared prefix
     * stopped dead at "WHAT JUST HAPPENED"/"story time:" on every single turn, and warm
     * prefix reuse averaged 0.34. Moving the volatile tail behind the transcript and the
     * constant action protocol into the stable block is the change that lifts it.
     */
    fun systemPrompt(): String = systemPromptStable() + systemPromptVolatile()

    /**
     * The leading, rarely-changing part of the system text: rules, identity, knowledge,
     * world facts, relationships and promises - the things that do not move on an ordinary
     * turn.
     *
     * Named for the cache experiment, and kept because the boundary is real and useful to
     * document. It is the part of the prompt the KV cache keeps turn after turn.
     */
    fun systemPromptStable(): String = buildString {
        appendLine("You are ${character.name}, a character inside a living story world.")
        // PHASE 10: the language rule leads. It used to be the sixth line of WORLD RULES,
        // buried under English prose in a prompt that is otherwise entirely English, and a
        // 4B model followed the surrounding text instead of it. Position is the cheap half
        // of the fix and the half nothing else can supply.
        if (replyLanguage != ReplyLanguage.UNKNOWN) {
            appendLine()
            append(replyLanguage.promptBlock(character.name))
        }
        appendLine()
        appendLine("WORLD RULES")
        appendLine("- You do not run the world. Locations, time, relationships and memories are managed outside you.")
        appendLine("- Only state what ${character.name} personally knows or perceives right now. Never invent facts about")
        appendLine("  places, other people, or earlier events unless they appear below.")
        appendLine("- If you do not know something, say so in character instead of guessing.")
        appendLine("- Never speak for the user, and never output system markers.")
        appendLine("- Match the user's language. The LANGUAGE block above is the one that decides it.")
        appendLine("- Never use meta headings such as \"Final Answer\", \"Final Thought\", \"Yarn\", or any")
        appendLine("  section label from this prompt as part of your reply. Do not restate these rules.")
        appendLine("- Answer the user's message directly. Do not give generic advice, change the subject,")
        appendLine("  or narrate the user in the third person.")
        appendLine("- The user is the person you are talking to, right now. Address them as \"you\" and")
        appendLine("  answer what they just asked. Do not answer to someone else in the scene.")
        appendLine("- Write only your own words. No dialogue for other characters, no stage directions,")
        appendLine("  no lists, no narration of the room.")
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
        if (relationships.isNotEmpty()) {
            appendLine()
            appendLine("PEOPLE YOU KNOW")
            relationships.forEach { rel ->
                val name = otherName(rel)
                appendLine("- $name: ${rel.describe(runtime.characterId)}")
            }
        }
        if (commitments.isNotEmpty()) {
            appendLine()
            appendLine("WHAT YOU ARE ON THE HOOK FOR")
            commitments.forEach { appendLine("- $it") }
            // Commitments are the one thing a character must act on unprompted. A
            // promise nobody mentions again is not a promise, it is a plot note.
            appendLine("If the conversation gives you a chance, keep these in mind.")
        }
        if (owedToThem.isNotEmpty()) {
            appendLine()
            appendLine("WHAT OTHERS PROMISED YOU")
            owedToThem.forEach { appendLine("- $it") }
            // Not a suggestion: a character who has been promised something is entitled
            // to ask after it, which is most of what people actually do.
            appendLine("You are entitled to ask after these.")
        }
    }

    /**
     * The trailing part of the system text: what the character remembers and has worked
     * out, what just happened, the clock and scene, the pace instruction and the closing
     * instruction.
     *
     * These are the blocks that move on an ordinary turn - a memory is written after every
     * turn, the clock advances every turn, the change feed grows whenever the world moves -
     * so they travel together at the END of the rendered prompt, after the transcript. Every
     * line is exactly the text this class always produced; only its position changed.
     */
    fun systemPromptVolatile(): String = buildString {
        // The memory block. It is the part of the prompt most likely to be *large* and most
        // likely to *change*, which is exactly why it sits in the tail: a new memory then
        // costs one tail re-evaluation instead of the whole conversation.
        val rendered = preRenderedMemories
        if (rendered != null) {
            appendLine()
            appendLine(rendered)
        } else if (memories.isNotEmpty()) {
            appendLine()
            appendLine("WHAT YOU REMEMBER")
            memories.forEach { appendLine("- (${it.importance}/5) ${it.content}") }
        }
        val mindLines = mind?.promptLines().orEmpty()
        if (mindLines.isNotEmpty()) {
            appendLine()
            appendLine("WHAT YOU HAVE SEEN AND WORKED OUT")
            mindLines.forEach { appendLine("- $it") }
            // The instruction matters as much as the data. Without it a model reads
            // "believes (wrongly)" and simply corrects itself mid-answer, which quietly
            // destroys the entire point of holding a misconception.
            appendLine()
            appendLine("Act on these as your own. Do not correct them, and do not learn anything from them.")
        }
        if (recentEvents.isNotEmpty()) {
            appendLine()
            appendLine("WHAT JUST HAPPENED")
            recentEvents.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("RIGHT NOW")
        // The clock, as fact. See [worldTime] for why the model is told rather than asked.
        appendLine("- story time: ${worldTime.format()}")
        appendLine("- scene: ${scene.id.value} at ${scene.locationId.value}")
        if (locationDescription.isNotBlank()) appendLine("- place: $locationDescription")
        if (runtime.activity != CharacterActivity.IDLE) {
            appendLine("- you are ${runtime.activity.name.lowercase()}")
        }
        // CharacterRuntime is a per-character state machine, not a scene property: mood,
        // goals and activity belong to THIS character and move on validated events. They
        // are rendered from `runtime` and nowhere else, which is what makes a
        // CharacterActivityChanged / GoalUpdated event visible in the NEXT prompt - the
        // property under test in "before context != after context".
        if (runtime.mood.isNotBlank()) appendLine("- you feel: ${runtime.mood}")
        if (runtime.activeGoals.isNotEmpty()) {
            appendLine("- you are working toward: ${runtime.activeGoals.joinToString("; ")}")
        }
        if (scene.participants.isNotEmpty()) {
            appendLine("- present: ${scene.participants.joinToString { it.value }}")
        }
        if (sceneObjective.isNotBlank()) appendLine("- current situation: $sceneObjective")
        if (scene.mood.isNotBlank()) {
            appendLine("- how this feels: ${scene.mood}")
            // Derived by the director, so it is stated as fact rather than suggestion.
            // Without that instruction a model treats a mood as a suggestion and
            // cheerfully writes a jolly scene in an empty street at three in the morning.
            appendLine("Write the scene so that it feels like that.")
        }
        if (scene.possibleArrivals.isNotEmpty()) {
            appendLine("- who might turn up: ${scene.possibleArrivals.joinToString { it.value }}")
            appendLine("They are not here yet. Do not narrate them as present.")
        }
        if (scene.possibleDepartures.isNotEmpty()) {
            appendLine("- who is likely to leave soon: ${scene.possibleDepartures.joinToString { it.value }}")
        }
        // The tempo instruction is the one part of this prompt that exists to stop
        // something rather than to start it. A small model handed an open scene reliably
        // escalates every turn - two people talk, an alarm goes off, someone confesses,
        // the roof collapses - because nothing tells it that an uneventful scene is
        // allowed. This does.
        appendLine()
        appendLine("PACE")
        append(scene.tempo.instruction)
        appendLine()
        appendLine("Stay in character. Reply as ${character.name} would, in the present tense.")
        // PHASE 10: the same instruction again, five words, immediately before the model's
        // turn opens. The block at the top of the prompt is the strong instruction; this is
        // the one still in working memory when the player reads it.
        replyLanguage.closingReminder().takeIf { it.isNotBlank() }?.let {
            appendLine(it)
        }
    }

    private fun otherName(rel: Relationship): String = when (runtime.characterId) {
        rel.sourceId -> rel.targetId.value
        else -> rel.sourceId.value
    }
}
