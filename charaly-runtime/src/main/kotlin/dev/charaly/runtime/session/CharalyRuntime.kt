package dev.charaly.runtime.session

import dev.charaly.runtime.context.ContextBudget
import dev.charaly.runtime.context.ContextBudgetInspector
import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.director.ActionReview
import dev.charaly.runtime.director.ActionValidator
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRuntime
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole
import dev.charaly.runtime.domain.TranscriptStyle
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.EventOrigin
import dev.charaly.runtime.domain.events.EventPayload
import dev.charaly.runtime.engine.ChapterPlanner
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.BranchEngine
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.EventProgram
import dev.charaly.runtime.engine.ObjectiveEngine
import dev.charaly.runtime.engine.ImportedCastMerger
import dev.charaly.runtime.engine.PresenceEngine
import dev.charaly.runtime.engine.ScheduleResult
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.engine.StoryHealthAnalyzer
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.model.toGenerationParams
import dev.charaly.runtime.domain.memory.MemoryConsolidator
import dev.charaly.runtime.domain.memory.MemorySource
import dev.charaly.runtime.domain.memory.MemoryTier
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.domain.memory.MemoryWriter
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.RestoredWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Everything a UI needs for one turn, emitted as it happens. */
sealed interface GenerationUpdate {
    data class Started(val scene: Scene, val characterId: CharacterId) : GenerationUpdate
    data class Chunk(val text: String, val accumulated: String) : GenerationUpdate
    data class AppliedAction(val review: ActionReview.Accepted, val changes: List<String>) : GenerationUpdate
    data class RejectedAction(val review: ActionReview.Rejected) : GenerationUpdate
    data class Finished(
        val text: String,
        val stopReason: StopReason,
        val completionTokens: Int = 0,
        /**
         * The authoritative story state after this turn, already persisted.
         * Callers continue from here; this is the only snapshot that is current.
         */
        val instance: StoryInstance? = null,
        /**
         * How much story time this turn cost, in minutes.
         *
         * Decided by [TurnClockPolicy] from engine-side facts, never from the model's
         * prose. A UI may show it ("eight minutes pass"), and a test may assert it, but
         * nothing outside the runtime can change it.
         */
        val elapsedStoryMinutes: Long = 0L,
        /**
         * Where this turn's time actually went, or null when the engine cannot measure.
         *
         * Present so a developer-mode readout can be built from real clocks rather than
         * from a stopwatch around the whole screen. Never rendered to a normal player.
         */
        val perf: TurnPerf? = null,
    ) : GenerationUpdate

    data class Failed(val error: CharalyError) : GenerationUpdate
}

/** Explicit failure taxonomy surfaced to the UI. Never leaks engine internals. */
sealed class CharalyError(message: String) : Exception(message) {
    class ModelNotLoaded : CharalyError("No model loaded. Load a GGUF model to generate.")
    class NoCharacter : CharalyError("This story has no character to speak.")
    class NoScene : CharalyError("Cannot determine a scene: the character has no location.")
    class Generation(detail: String) : CharalyError("Generation failed: $detail")
    class Cancelled : CharalyError("Generation cancelled.")
    class Persistence(detail: String) : CharalyError("Could not save the story: $detail")

    /**
     * The story's pinned pack edition is not on this device.
     *
     * Distinct from a model problem on purpose: the generation machinery is fine, the
     * *content* this story is written against is missing. Generating anyway would run the
     * turn against a different edition's canon - a silent rewrite of somebody's story.
     */
    class PackUnavailable(detail: String) : CharalyError("Story pack edition unavailable: $detail")

    override fun toString(): String = this::class.simpleName ?: "CharalyError"
}

/**
 * Where one story turn's wall-clock time went.
 *
 * Built from real clocks on the runtime's own path plus the engine's native timings, so a
 * developer can see the difference between "the model is slow" and "the client spent two
 * seconds rebuilding context". [ttftMillis] is the one a reader feels: send to first word.
 *
 * The engine-side figures are nullable because a scripted engine cannot measure them; a
 * missing number is shown as absent, never as zero.
 */
data class TurnPerf(
    /** Send -> [GenerationUpdate.Finished]. */
    val totalMillis: Long,
    /** Send -> the first streamed token. */
    val ttftMillis: Long,
    /** Resolving the model into native memory (0 when already resident). */
    val residencyMillis: Long,
    /** SceneDirector + ContextBuilder + prompt assembly. */
    val contextBuildMillis: Long,
    /** Engine's own prompt tokenize + evaluation, when measurable. */
    val promptEvalMillis: Long?,
    /**
     * PHASE 10: of [promptEvalMillis], the part that was tokenizing the prompt.
     *
     * Separate because they have separate fixes - tokenizing is a tokenizer/template
     * cost, evaluating is a KV-cache cost - and one combined number hides which of the
     * two a slow turn is actually paying for. Absent, never zero, when unmeasured.
     */
    val tokenizeMillis: Long? = null,
    /**
     * PHASE 10: of [promptEvalMillis], the part that evaluated the prompt into the KV
     * cache. This is the half prefix reuse removes, so it is the half worth watching.
     */
    val evalMillis: Long? = null,
    /**
     * PHASE 10: the runtime's own hop from the start of the turn to the moment the engine
     * was called - dispatch, residency and prompt assembly together.
     *
     * This is phase 1 of the five the brief asks for, measured on the runtime's clock.
     * The turn does not begin at `respond()` - it begins at the Send button - so a client
     * that has its own clock should prefer its own number; this one is never missing.
     */
    val dispatchMillis: Long = 0L,
    /** Engine's own decode window, when measurable. */
    val generationMillis: Long?,
    /** ProposedActionParser + ActionValidator + EventEngine application. */
    val parserMillis: Long,
    /** Conditional events, clock advance and chapter derivation. */
    val eventsMillis: Long,
    /** Atomic save(s) at the end of the turn. */
    val persistenceMillis: Long,
    val contextChars: Int,
    val promptTokens: Int?,
    val generatedTokens: Int?,
    /** Leading prompt tokens served from the previous turn's cache. */
    val reusedTokens: Int?,
    val tokensPerSecond: Double?,
    val promptTokensPerSecond: Double?,
    val modelLoadMillis: Long?,
    /**
     * PHASE 10: the engine's two thread pools, when measurable.
     *
     * [threadsDecode] decodes one token; [threadsPrompt] evaluates the prompt. They are
     * different pools inside llama.cpp and they are the difference between a first token
     * in a second and a first token in three minutes, so both are named rather than the
     * one that happens to be the bottleneck on a workstation.
     */
    val threadsDecode: Int? = null,
    val threadsPrompt: Int? = null,
) {
    /** One multiline summary, in the format the developer-mode log expects. */
    fun summary(): String = buildString {
        appendLine("STORY TURN PERF")
        appendLine("total=${totalMillis}ms")
        appendLine("ttft=${ttftMillis}ms")
        appendLine("context_chars=$contextChars")
        appendLine("prompt_tokens=${promptTokens ?: "?"}")
        appendLine("generated_tokens=${generatedTokens ?: "?"}")
        appendLine("reused_tokens=${reusedTokens ?: "?"}")
        appendLine("model_load=${modelLoadMillis ?: 0}ms")
        appendLine("dispatch_to_inference_call=${dispatchMillis}ms")
        appendLine("context_build=${contextBuildMillis}ms")
        appendLine("residency=${residencyMillis}ms")
        appendLine("prompt_eval=${promptEvalMillis ?: "?"}ms")
        appendLine("  tokenize=${tokenizeMillis ?: "?"}ms")
        appendLine("  eval=${evalMillis ?: "?"}ms")
        appendLine("threads_decode=${threadsDecode ?: "?"} threads_prompt=${threadsPrompt ?: "?"}")
        appendLine("generation=${generationMillis ?: "?"}ms")
        appendLine("parser=${parserMillis}ms")
        appendLine("events=${eventsMillis}ms")
        appendLine("persistence=${persistenceMillis}ms")
        appendLine("tokens_per_sec=${tokensPerSecond?.let { format1(it) } ?: "?"}")
        append("prompt_tokens_per_sec=${promptTokensPerSecond?.let { format1(it) } ?: "?"}")
    }

    private fun format1(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)
}

/**
 * The Charaly generation pipeline, end to end:
 *
 * ```
 * user input
 *   -> SceneDirector         what scene are we in?          (no LLM)
 *   -> ContextBuilder        what may this character know?   (no LLM)
 *   -> InferenceEngine       streamed, cancellable tokens
 *   -> ProposedActionParser  explicit tags only, never prose
 *   -> ActionValidator
 *   -> EventEngine           authoritative world change
 *   -> repository            persist
 * ```
 *
 * There is no path in this class where model prose becomes world state.
 */
class CharalyRuntime(
    private val repository: CharalyRepository,
    private val engine: InferenceEngine,
    private val resolver: WorldDefinitionResolver = WorldDefinitionResolver(repository),
    private val params: GenerationParams = GenerationParams(),
    /**
     * The user's imported characters.
     *
     * A default no-op implementation rather than a required constructor argument, so every
     * existing call site and test keeps working. The app wires the real JSON-backed
     * library; a runtime built without one simply has no imported characters, which is a
     * legitimate state and not an error.
     */
    private val library: dev.charaly.runtime.persistence.CharacterLibraryRepository =
        dev.charaly.runtime.persistence.InMemoryCharacterLibraryRepository(),
    /**
     * Brings the story's bound model into native memory before a turn is generated.
     *
     * ## Why `respond` resolves residency itself
     *
     * Requirement "READY != LOADED" makes the load *lazy*, and laziness has to live at the
     * point of use or it is not lazy - it is a convention somebody has to remember. When
     * this check was `if (!engine.isLoaded()) fail`, a story turn raced every other way the
     * engine could be cold (a restart, a model switch that had just unloaded, a load the UI
     * had not got round to) and lost as "This story needs a local model before it can
     * continue." - for a model that was installed, bound and READY.
     *
     * So the runtime asks for the model by its [dev.charaly.runtime.model.ModelBinding] id
     * and lets the app-side loader do the file resolution. A cold engine is now an ordinary
     * moment that this resolves, not an error the user is blamed for.
     *
     * The default answers from the engine's own state and loads nothing, which is exactly
     * the old behaviour for a runtime built without a loader (tests, host tooling).
     */
    private val residency: dev.charaly.runtime.model.ModelResidency =
        dev.charaly.runtime.model.ModelResidency.immediate(engine),
    /**
     * Where the pack *edition* a story is pinned to comes from.
     *
     * Without it a story follows whatever edition the repository holds (the old behaviour,
     * and the right one for saves that predate pinning). With it, a story that began in
     * 1.1.0 keeps reading 1.1.0 - its events, its definitions, its openings - however many
     * newer editions are installed beside it.
     */
    private val editions: dev.charaly.runtime.storypack.PackEditionSource? = null,
    /**
     * PHASE 10: where a turn's own work runs.
     *
     * ## Why a turn needs one at all
     *
     * `respond` is a cold `flow`, so before this it executed in **the collector's**
     * context - which in the app is `viewModelScope`, i.e. the Android main thread. Only
     * the native decode was off it (the engine's own `flowOn(Dispatchers.IO)`). Everything
     * else a turn does ran on the thread that draws the screen:
     *
     * ```
     *   resolver.forInstance      reads the pack from storage
     *   SceneDirector + ContextBuilder   assembles the prompt
     *   rememberTurn              writes and consolidates memories
     *   repository.saveInstance   encodes the story and writes it to disk  (twice a turn)
     *   advance + ChapterPlanner   runs the event engine
     * ```
     *
     * None of that is "the model is slow". All of it is main-thread disk I/O and
     * allocation landing between the player pressing Send and the first word appearing,
     * and it is charged to the frame budget of the screen the player is looking at.
     *
     * `flowOn` shifts the *producer* only: `emit` still reaches the collector on the
     * collector's dispatcher, so a ViewModel's `_state.update {}` stays on main exactly
     * where it belongs, and ordering is preserved.
     *
     * Defaults to [Dispatchers.Default] (the CPU pool) rather than IO: the work is
     * CPU-bound JSON and rule evaluation, and the blocking decode keeps its own IO
     * thread. Injectable so a test can pin it.
     */
    private val turnDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
) {

    val generationParams: GenerationParams get() = params

    // ------------------------------------------------------------------
    // Story lifecycle
    // ------------------------------------------------------------------

    /** Offline restore. No network, no server probe, no migration required. */
    suspend fun restore(): RestoredWorld = repository.restoreAll()

    suspend fun listPacks(): List<StoryPack> = repository.listPacks()

    suspend fun createPack(pack: StoryPack): StoryPack {
        repository.savePack(pack)
        resolver.invalidate(pack.id)
        resolver.forPack(pack)
        return pack
    }

    suspend fun startStory(pack: StoryPack, instanceId: StoryInstanceId): StoryInstance {
        resolver.forPack(pack)
        val instance = StoryInstanceFactory.create(pack, instanceId)
        repository.saveInstance(instance)
        return instance
    }

    suspend fun loadStory(id: StoryInstanceId): StoryInstance? = repository.getInstance(id)

    /** Every running story, most recently played first. */
    suspend fun listInstances(): List<StoryInstance> = repository.listInstances()

    /**
     * Starts a story from the New Story flow.
     *
     * The options carry the scenario, persona, cast and the *resolved* model
     * binding. The runtime stores the binding inside the instance so the story
     * keeps behaving the same way even if the user's global default changes.
     */
    suspend fun startStory(pack: StoryPack, options: StoryCreationOptions): StoryInstance {
        resolver.forPack(pack)
        val instance = StoryInstanceFactory.create(pack, options)
        repository.saveInstance(instance)
        return instance
    }

    /**
     * Starts a story that includes characters the user imported.
     *
     * The import path a player actually takes: "start a story in this world, and put Ash in
     * it too". Three things happen, in order:
     *
     *  1. the pack is *copied* into a cast-merged variant with its own pack id, so this
     *     playthrough owns a world of its own and the shipped pack is not edited;
     *  2. the story is created from that copy through the ordinary factory, so every
     *     existing guarantee about starting a story still holds;
     *  3. any character who arrived with no location is placed at the opening through a
     *     validated event - because a character the engine cannot place is a character the
     *     player can never meet.
     *
     * The returned instance's [dev.charaly.runtime.domain.StoryInstance.storyPackId] points
     * at the merged copy. That is what makes a second story from the same world start clean.
     */
    suspend fun startStoryWithImported(
        pack: StoryPack,
        options: StoryCreationOptions,
        importedCharacterIds: Set<CharacterId> = emptySet(),
    ): StoryInstance {
        val imported = if (importedCharacterIds.isEmpty()) {
            emptyList()
        } else {
            importedCharacterIds.mapNotNull { id ->
                library.get(id)?.preview?.toCharacterDefinition()
            }
        }
        val merged = ImportedCastMerger.merge(pack, imported)
        if (merged.id != pack.id) {
            // Saved as its own pack so the story survives a restart. The original is left
            // exactly as it was, which is what keeps the two playthroughs independent.
            repository.savePack(merged)
            resolver.invalidate(merged.id)
        }
        resolver.forPack(merged)
        val created = StoryInstanceFactory.create(merged, options)
        val placed = ImportedCastMerger.placeUnplacedCharacters(merged, created)
        repository.saveInstance(placed)
        return placed
    }

    /** Renames a story. Story metadata, never world state. */
    suspend fun renameStory(instance: StoryInstance, title: String): StoryInstance {
        val renamed = instance.copy(title = title.trim())
        return save(renamed)
    }

    /** Records that a story was opened, so "last played" is real. */
    suspend fun touch(instance: StoryInstance, nowEpochMs: Long): StoryInstance =
        save(
            instance.copy(
                sessionMeta = instance.sessionMeta.copy(lastPlayedAtEpochMs = nowEpochMs),
            ),
        )

    /**
     * Duplicates a story into a new branch.
     *
     * This is a *snapshot* copy, which is exactly what branching means here: both
     * stories continue from the same world state, and neither can affect the other
     * afterwards, because a StoryInstance owns all of its own state.
     */
    suspend fun duplicateStory(
        instance: StoryInstance,
        newId: StoryInstanceId,
        newTitle: String = "",
        nowEpochMs: Long = 0L,
    ): StoryInstance {
        val branch = instance.copy(
            id = newId,
            title = newTitle.ifBlank { "${instance.displayTitle} (branch)" },
            branchOrigin = dev.charaly.runtime.domain.BranchOrigin.BRANCHED,
            branchedFrom = dev.charaly.runtime.domain.BranchOriginInfo(
                sourceInstanceId = instance.id,
                sourceTitle = instance.displayTitle,
                sourceTurn = instance.conversation.entries.maxOfOrNull { it.turn } ?: 0,
                createdAtEpochMs = nowEpochMs,
            ),
            sessionMeta = dev.charaly.runtime.domain.SessionMeta(
                createdAtEpochMs = nowEpochMs,
                lastPlayedAtEpochMs = nowEpochMs,
                totalTurns = 0,
            ),
        )
        return save(branch)
    }

    /**
     * Removes the last exchange - the player's line and the reply it drew - so the turn
     * can be generated again.
     *
     * The player's line goes too, even though `respond` appends it again on the way back
     * in: a regeneration RE-SENDS that line, and dropping only the reply left the transcript
     * carrying the same message twice (`…user, user, character`), which is a duplicated
     * message rendered to the player. Dropping from the earliest of the pair puts the
     * transcript back exactly as it stood before the turn, so the retry is indistinguishable
     * from a first attempt.
     *
     * This is a *transcript* operation, not a world operation: nothing in world state is
     * undone, because what the world did already happened.
     */
    suspend fun prepareRegeneration(instance: StoryInstance): Pair<StoryInstance, String?> {
        val lastCharacter = instance.conversation.lastCharacterEntry()
        val lastUser = instance.conversation.lastUserEntry()
        if (lastCharacter == null || lastUser == null) return instance to null
        val trimmed = instance.copy(
            conversation = instance.conversation.dropFrom(minOf(lastUser.turn, lastCharacter.turn)),
        )
        return save(trimmed) to lastUser.text
    }

    /** Continue the most recent playthrough of a pack (the "resume" button). */
    suspend fun continueStory(packId: dev.charaly.runtime.domain.StoryPackId): StoryInstance? =
        repository.latestFor(packId)

    suspend fun deleteStory(id: StoryInstanceId) = repository.deleteInstance(id)

    suspend fun save(instance: StoryInstance): StoryInstance {
        repository.saveInstance(instance)
        return instance
    }

    /** Static half of a running world (character identity + places). */
    suspend fun definitionFor(instance: StoryInstance): WorldDefinition = resolver.forInstance(instance)

    /**
     * The story time a completed turn costs.
     *
     * Exposed so a screen can tell the reader that time passed, and so a test can
     * assert the cost without reaching for the policy object. The value is computed from
     * the player's line and the number of validated events - see [TurnClockPolicy] for
     * why the model's own words are not an input.
     */
    fun turnCost(userInputChars: Int, appliedActions: Int): StoryDuration =
        TurnClockPolicy.minutesFor(userInputChars, appliedActions)

    /**
     * The generation parameters for one story.
     *
     * A StoryInstance that has a bound model profile runs with *its own* copy of
     * those values. That is what makes a story reproducible: changing the global
     * default afterwards cannot retroactively change an existing story.
     */
    fun paramsFor(instance: StoryInstance): GenerationParams =
        if (instance.modelBinding.isBound || instance.modelBinding.profileId.isNotBlank()) {
            instance.modelBinding.sampler.toGenerationParams()
        } else {
            params
        }

    /** The context budget for one story, from its bound profile. */
    fun contextBudgetFor(instance: StoryInstance): ContextBudget {
        val policy = instance.modelBinding.contextPolicy
        return ContextBudget(
            transcriptTurns = policy.transcriptTurns,
            memories = policy.memories,
            maxChars = policy.maxChars,
        )
    }

    /**
     * Applies a new model profile to an existing story.
     *
     * Only the binding changes: no world state is touched, so a story never
     * "becomes different" because the user tuned a sampler.
     */
    suspend fun rebindProfile(instance: StoryInstance, profile: dev.charaly.runtime.model.ModelProfile): StoryInstance =
        save(instance.copy(modelBinding = dev.charaly.runtime.model.ModelBinding.withProfile(instance.modelBinding, profile)))

    /** Points a story at a different installed model. */
    suspend fun rebindModel(
        instance: StoryInstance,
        installed: dev.charaly.runtime.model.InstalledModel,
        profile: dev.charaly.runtime.model.ModelProfile,
    ): StoryInstance = save(
        instance.copy(
            modelBinding = dev.charaly.runtime.model.ModelBinding.from(
                installedModelId = installed.id,
                modelDisplayName = installed.displayName,
                profile = profile,
                maxContextTokens = installed.effectiveContextTokens(),
                boundAtEpochMs = System.currentTimeMillis(),
            ),
        ),
    )

    /** Changes who the player is talking to. Instance metadata only. */
    suspend fun setFocus(instance: StoryInstance, characterId: CharacterId): StoryInstance =
        save(instance.copy(focusCharacterId = characterId))

    /**
     * Pins a memory so it survives consolidation and pruning.
     *
     * Exposed through the runtime rather than by mutating the store directly: pinning is
     * a decision about what the story keeps, and it belongs in the same place as
     * forgetting.
     */
    suspend fun pinMemory(instance: StoryInstance, memoryId: dev.charaly.runtime.domain.MemoryId, pinned: Boolean = true): StoryInstance {
        val pinned_ = instance.memories.pin(memoryId, pinned)
        if (pinned_ == instance.memories) return instance
        return save(instance.evolved(memories = pinned_))
    }

    /**
     * "Forget" a memory.
     *
     * Deliberately a domain operation rather than a row deletion: the memory is
     * invalidated with a timestamp and dropped out of retrieval, but the record stays so
     * the forget is auditable and the owning character's index can be repaired. Silently
     * deleting a memory would leave the world quietly inconsistent.
     */
    suspend fun forgetMemory(
        instance: StoryInstance,
        memoryId: dev.charaly.runtime.domain.MemoryId,
    ): StoryInstance {
        val forgotten = instance.memories.forget(memoryId, instance.worldClock.now)
        if (forgotten == instance.memories) return instance
        val owner = instance.memories.byId(memoryId)?.characterId
        var current = instance.evolved(memories = forgotten)
        // Keep the character's runtime index in step with the store.
        owner?.let { characterId ->
            val runtime = current.characters[characterId] ?: return@let
            current = current.evolved(
                worldState = current.worldState.withCharacter(
                    runtime.copy(memoryIds = runtime.memoryIds.filterNot { it == memoryId }),
                ),
            )
        }
        return save(current)
    }

    /**
     * Runs memory consolidation over a story.
     *
     * Merges duplicates and resolves contradictions so a long story's memory does not
     * grow without bound. Idempotent: running it twice changes nothing the second time,
     * which is what makes it safe to call after every turn.
     */
    suspend fun consolidateMemories(instance: StoryInstance): StoryInstance {
        val consolidated = MemoryConsolidator.consolidate(instance.memories, instance.worldClock.now)
        if (consolidated == instance.memories) return instance
        return save(instance.evolved(memories = consolidated))
    }

    /**
     * Evaluates conditionally triggered pack events against the current world.
     *
     * Everything that matches is scheduled and applied *through the event engine*,
     * exactly like any other world change, and the fired id is recorded so
     * one-shot triggers and cooldowns behave.
     */
    suspend fun pollConditionalEvents(instance: StoryInstance): StoryInstance {
        val pack = packFor(instance) ?: return instance
        val definition = resolver.forInstance(instance)
        val engine = EventEngine(definition)
        var current = instance
        EventProgram.conditionalCandidates(pack, current).forEach { candidate ->
            val payloads = EventProgram.payloadsFor(candidate, current)
            payloads.forEach { payload ->
                val scheduled = engine.scheduleEvent(
                    instance = current,
                    payload = payload,
                    at = current.worldClock.now,
                    origin = EventOrigin.ENGINE,
                    note = "${EventProgram.EVENT_NOTE_PREFIX}${candidate.id}",
                )
                if (scheduled is ScheduleResult.Scheduled) {
                    val applied = engine.applyEvent(scheduled.instance, scheduled.events.first())
                    if (applied is EventApplication.Applied) {
                        current = applied.instance.copy(
                            firedEvents = applied.instance.firedEvents + (candidate.id to current.worldClock.now),
                        )
                    }
                }
            }
        }
        return evaluateObjectives(if (current === instance) instance else save(current))
    }

    /**
     * The pack edition a story reads.
     *
     * Prefers the pinned edition when the story has one and it is still installed; falls
     * back to the repository's active edition otherwise, which is exactly the documented
     * `UNPINNED_LEGACY` behaviour rather than a silent migration.
     */
    suspend fun packFor(instance: StoryInstance): StoryPack? {
        val pinned = instance.packVersion
        if (pinned.isNotBlank()) {
            editions?.editionFor(instance.storyPackId.value, pinned)?.let { return it }
        }
        return repository.getPack(instance.storyPackId)
    }

    // ------------------------------------------------------------------
    // Branches and objectives
    // ------------------------------------------------------------------

    /**
     * The branches the player may take right now.
     *
     * Only branches whose conditions hold - the unavailable ones are invisible rather than
     * greyed out, because their existence is itself a spoiler.
     */
    suspend fun offeredChoices(instance: StoryInstance): List<dev.charaly.runtime.domain.OfferedChoice> {
        val pack = packFor(instance) ?: return emptyList()
        return BranchEngine.offered(instance, pack)
    }

    /** What became of an attempt to take a branch. */
    sealed interface ChoiceOutcome {
        data class Taken(val instance: StoryInstance, val note: String) : ChoiceOutcome
        data class Refused(val reason: String) : ChoiceOutcome
    }

    /**
     * Takes a branch: conditions checked, effects applied through the event engine, the
     * decision recorded, objectives re-evaluated, everything persisted.
     *
     * The same branch in the same state produces the same world change every time, which
     * is the property that makes conditional storytelling a mechanic instead of a rumour.
     */
    suspend fun takeChoice(
        instance: StoryInstance,
        sceneId: String,
        choiceId: String,
    ): ChoiceOutcome {
        val pack = packFor(instance) ?: return ChoiceOutcome.Refused("hikâye paketi bulunamadı")
        val choice = BranchEngine.choiceFor(instance, pack, sceneId, choiceId)
            ?: return ChoiceOutcome.Refused("bu seçenek artık yok")
        val at = instance.worldClock.now
        val payloads = BranchEngine.compile(instance, sceneId, choice, at)
        if (payloads.isEmpty()) {
            return ChoiceOutcome.Refused("bu seçenek şu anda açık değil")
        }
        val engine = EventEngine(resolver.forInstance(instance))
        var current = instance
        var landed = 0
        payloads.forEach { payload ->
            when (val applied = engine.applyImmediately(current, payload, EventOrigin.USER)) {
                is EventApplication.Applied -> {
                    current = applied.instance
                    landed++
                }
                // A refused effect changes nothing and is not recorded; the choice below
                // is only counted as taken when at least one effect actually landed.
                is EventApplication.Rejected -> Unit
            }
        }
        // A choice is recorded as taken only when at least one of its effects actually
        // landed. Recording a choice whose every effect the engine refused would consume
        // a `once` branch while leaving the world exactly as it was: the branch would be
        // gone, nothing would have happened, and there would be no way to take it again.
        if (landed == 0) {
            return ChoiceOutcome.Refused("bu seçenek şu anda açık değil")
        }
        current = current.copy(
            choiceLedger = current.choiceLedger + BranchEngine.record(current, sceneId, choice, at),
        )
        current = evaluateObjectives(current)
        return ChoiceOutcome.Taken(save(current), choice.outcomeNote)
    }

    /**
     * Resolves objectives against the world as it now stands.
     *
     * Completion and failure pay out through the event engine - a reward is a world change
     * like any other and is validated like any other - and the result is persisted with the
     * story, so an objective never re-resolves after a restart.
     */
    suspend fun evaluateObjectives(instance: StoryInstance): StoryInstance {
        val pack = packFor(instance) ?: return instance
        val evaluation = ObjectiveEngine.evaluate(instance, pack)
        if (!evaluation.changed) return instance
        val engine = EventEngine(resolver.forInstance(instance))
        // The payout is applied first, against the world as it stands. Only when every
        // payload has landed is the objective marked resolved. An objective whose reward
        // the engine refused has not actually completed: marking it resolved would both
        // lose the reward and stop it ever being retried, because a terminal objective is
        // never re-evaluated. Keeping it active lets the next turn try the payout again.
        var current = instance
        var allPaid = true
        evaluation.payloads.forEach { payload ->
            when (val applied = engine.applyImmediately(current, payload, EventOrigin.ENGINE)) {
                is EventApplication.Applied -> current = applied.instance
                is EventApplication.Rejected -> allPaid = false
            }
        }
        if (!allPaid) {
            // Whatever did land is a real world change and is persisted; the objective
            // itself stays active so a later turn can still complete it.
            return if (current === instance) instance else save(current)
        }
        return save(current.copy(objectives = evaluation.objectives))
    }

    /**
     * Deterministic time travel + event drain. Never touches the model.
     *
     * IMPORTANT: [instance] must be the latest snapshot. The story instance is
     * immutable, so passing a snapshot from before a generation would overwrite
     * the transcript that generation just persisted. Use [loadStory] (or the
     * value returned by the last mutating call) when in doubt.
     */
    suspend fun advance(instance: StoryInstance, by: StoryDuration): StoryInstance {
        val definition = resolver.forInstance(instance)
        // EventEngine.advanceTime does the clock, the due events and the NPC routines in
        // one deterministic pass. Pack-conditional events need the pack, so they stay
        // here and run straight afterwards - which lets an authored event override a
        // routine placement within the same turn.
        val result = EventEngine(definition).advanceTime(instance, instance.worldClock.now.plusMinutes(by.minutes))
        val polled = pollConditionalEvents(result.instance)
        return save(ChapterPlanner.derive(polled, definition))
    }

    /**
     * Reads the current authoritative snapshot before mutating, so a stale
     * handle can never silently discard a transcript or an event log.
     */
    suspend fun advanceLatest(instanceId: StoryInstanceId, by: StoryDuration): StoryInstance? {
        val current = repository.getInstance(instanceId) ?: return null
        return advance(current, by)
    }

    /** Queues an explicit user action (inspector / debug UI). */
    suspend fun schedule(
        instance: StoryInstance,
        payload: EventPayload,
        after: StoryDuration = StoryDuration.ZERO,
    ): StoryInstance {
        val engineForWorld = EventEngine(resolver.forInstance(instance))
        return when (val result = engineForWorld.scheduleAfter(instance, payload, after)) {
            is ScheduleResult.Scheduled -> save(result.instance)
            is ScheduleResult.Rejected -> instance
        }
    }

    /**
     * The player walks into a place.
     *
     * This is the move that makes a world feel inhabited: the location is applied
     * through a validated event, the people standing there are *discovered* from
     * authoritative state, and anyone the player can see is introduced. Nobody has to be
     * added to a conversation to exist here.
     *
     * Returns the updated instance with a scene open at the new location when there is
     * anyone there to meet.
     */
    suspend fun travelTo(instance: StoryInstance, locationId: LocationId): StoryInstance {
        val definition = resolver.forInstance(instance)
        val engine = EventEngine(definition)
        var current = instance

        val arrival = engine.scheduleEvent(
            instance = current,
            payload = dev.charaly.runtime.domain.events.LocationEntered(
                locationId = locationId,
                observation = definition.location(locationId)?.summaryLine.orEmpty(),
            ),
            at = current.worldClock.now,
            origin = EventOrigin.USER,
            note = "the player walked to ${definition.nameOf(locationId)}",
        )
        if (arrival is ScheduleResult.Scheduled) {
            (engine.applyEvent(arrival.instance, arrival.events.first()) as? EventApplication.Applied)
                ?.let { current = it.instance }
        }

        val present = current.worldState.charactersAt(locationId)
        if (present.isEmpty()) return save(current)

        // Meet whoever is actually standing here, and only them. Everyone else in the
        // world keeps existing somewhere else, untouched.
        val sceneId = SceneId("scene-${current.worldState.revision + 1}-${locationId.value}")
        val scene = engine.scheduleEvent(
            instance = current,
            payload = dev.charaly.runtime.domain.events.SceneStarted(
                sceneId = sceneId,
                locationId = locationId,
                participants = present.map { it.characterId },
                objective = definition.location(locationId)?.summaryLine.orEmpty(),
                activeThreadIds = current.worldState
                    .activeThreadsFor(locationId, present.map { it.characterId }.toSet())
                    .map { it.id },
            ),
            at = current.worldClock.now,
            origin = EventOrigin.USER,
            note = "arrived at ${definition.nameOf(locationId)}",
        )
        if (scene is ScheduleResult.Scheduled) {
            (engine.applyEvent(scene.instance, scene.events.first()) as? EventApplication.Applied)
                ?.let { current = it.instance }
        }

        // Introduce them: knowledge is granted per character, so learning that the
        // player is here cannot leak to anyone who is not in the room.
        present.forEach { occupant ->
            val fact = current.knowledge.fact(greetingFactId(locationId))
                ?: dev.charaly.runtime.domain.knowledge.Fact(
                    id = greetingFactId(locationId),
                    description = "${occupant.name} has just met the player at ${definition.nameOf(locationId)}.",
                    locationId = locationId,
                    involvedCharacters = listOf(occupant.characterId),
                )
            var next = current
            if (current.knowledge.fact(fact.id) == null) {
                (engine.applyImmediately(next, dev.charaly.runtime.domain.events.FactRevealed(fact)) as? EventApplication.Applied)
                    ?.let { next = it.instance }
            }
            (engine.applyImmediately(
                next,
                dev.charaly.runtime.domain.events.KnowledgeDiscovered(
                    characterId = occupant.characterId,
                    factId = fact.id,
                    via = "met in person",
                ),
            ) as? EventApplication.Applied)?.let { next = it.instance }
            current = next
        }

        return save(current)
    }

    /** The people standing in a place right now, for a location screen's Enter action. */
    fun presentAt(instance: StoryInstance, locationId: LocationId): List<CharacterRuntime> =
        instance.worldState.charactersAt(locationId)

    /** Where the player currently is, or null before they have moved. */
    fun playerLocation(instance: StoryInstance): LocationId? =
        EventEngine.currentPlayerLocation(instance)

    private fun greetingFactId(locationId: LocationId) =
        dev.charaly.runtime.domain.FactId("fact-met-${locationId.value}")

    suspend fun scheduleAndProcess(instance: StoryInstance, payload: EventPayload): StoryInstance {
        val engineForWorld = EventEngine(resolver.forInstance(instance))
        return when (val result = engineForWorld.scheduleAfter(instance, payload, StoryDuration.ZERO)) {
            is ScheduleResult.Scheduled -> {
                when (val applied = engineForWorld.applyEvent(result.instance, result.events.first())) {
                    is EventApplication.Applied -> save(applied.instance)
                    // A refused event is dropped from the queue, exactly as
                    // `processEventsUntil` drops a refused due event. Leaving it queued
                    // would mean the next clock advance finds it due and rejects it again:
                    // a stale invalid event that never goes away on its own.
                    is EventApplication.Rejected -> save(
                        result.instance.evolved(
                            eventQueue = result.instance.eventQueue.remove(result.events.first().id),
                        ),
                    )
                }
            }
            is ScheduleResult.Rejected -> instance
        }
    }

    // ------------------------------------------------------------------
    // Generation
    // ------------------------------------------------------------------

    /**
     * Streams one character reply.
     *
     * Cancelling the collecting coroutine aborts the native generation: the
     * engine's stop() is called in a finally block, so an abandoned reply never
     * leaves the model running.
     *
     * The final [GenerationUpdate.Finished] carries the authoritative snapshot,
     * so callers never have to re-read the story after a turn.
     */
    fun respond(
        instance: StoryInstance,
        userInput: String,
        characterId: CharacterId? = null,
        onPersist: (StoryInstance) -> Unit = {},
    ): Flow<GenerationUpdate> = flow {
        // Where a turn's time went, measured from the moment collection begins (which is
        // the same moment the composer's Send starts the request).
        val perfStart = System.nanoTime()

        val speaker = characterId ?: instance.focusOrFirst()
        if (speaker == null) {
            emit(GenerationUpdate.Failed(CharalyError.NoCharacter()))
            return@flow
        }

        // PINNED EDITION GUARD.
        //
        // A story that began on pack edition 1.1.0 must never generate against 1.2.0's
        // canon: its events, its references and its world-state semantics may differ, and
        // substituting them silently would rewrite the story mid-sentence. When an edition
        // store is wired (the app always wires one) and the pinned edition is gone - a
        // forced pack delete, a partial restore - the turn is refused with a reason the UI
        // can act on. Without an edition store (tests, host tooling) the repository's copy
        // is the only edition in existence and the old behaviour stands.
        val editionSource = editions
        val pinnedEdition = instance.packVersion
        if (editionSource != null && pinnedEdition.isNotBlank() &&
            editionSource.editionFor(instance.storyPackId.value, pinnedEdition) == null
        ) {
            emit(
                GenerationUpdate.Failed(
                    CharalyError.PackUnavailable("${instance.storyPackId.value}@$pinnedEdition"),
                ),
            )
            return@flow
        }
        // LAZY LOAD, at the point of use.
        //
        // The engine may legitimately hold nothing: the import finished but the weights did
        // not, the process restarted (native residency is deliberately never persisted), or
        // another model was selected a moment ago. That is a normal state, so it is resolved
        // here rather than refused - the model is READY the moment it is registered and its
        // file parses, and "not in RAM yet" is not a reason to tell a user to go and find a
        // model they already chose.
        val residencyStart = System.nanoTime()
        val wasResident = engine.isLoaded()
        if (!residency.ensureResident(instance.modelBinding)) {
            emit(GenerationUpdate.Failed(CharalyError.ModelNotLoaded()))
            return@flow
        }
        val residencyMillis = (System.nanoTime() - residencyStart) / 1_000_000L
        val modelLoadMillis = if (wasResident) 0L else residencyMillis

        val contextStart = System.nanoTime()

        val definition = resolver.forInstance(instance)
        val director = SceneDirector(definition)
        val builder = ContextBuilder(definition, contextBudgetFor(instance))
        val turnParams = paramsFor(instance)

        val scene = director.directScene(instance, speaker)
        if (scene == null) {
            emit(GenerationUpdate.Failed(CharalyError.NoScene()))
            return@flow
        }

        // A derived scene is not world state until an event says so.
        val committed = commitScene(instance, scene, speaker, definition)
        emit(GenerationUpdate.Started(committed.currentScene() ?: scene, speaker))

        val turn = committed.conversation.nextTurn()
        val withUserLine = committed.evolved(
            conversation = committed.conversation.append(
                TranscriptEntry(
                    id = "turn-$turn",
                    turn = turn,
                    role = TranscriptRole.USER,
                    text = userInput,
                    at = committed.worldClock.now,
                    sceneId = committed.currentSceneId,
                ),
            ),
        )

        val activeScene = withUserLine.currentScene() ?: scene
        val request = builder.buildRequest(
            instance = withUserLine,
            scene = activeScene,
            characterId = speaker,
            userInput = userInput,
            params = turnParams,
        )
        val contextBuildMillis = (System.nanoTime() - contextStart) / 1_000_000L
        // Everything actually handed to the model, system prompt included.
        val contextChars = request.systemPrompt.length +
            request.messages.sumOf { it.content.length }

        val accumulated = StringBuilder()
        var tokens = 0
        var stopReason = StopReason.COMPLETED
        var firstChunkNanos = 0L
        var inferenceCallNanos = 0L

        try {
            // PHASE 10: the moment the turn handed work to the engine. Everything the
            // reader waited for before this line - dispatch, residency, prompt assembly -
            // is phase 1 of the latency breakdown, and it was previously unattributed:
            // `ttftMillis` folded it together with the engine's own first token, so a
            // prompt that took four seconds to build and an engine that took four seconds
            // to answer produced the same number.
            inferenceCallNanos = System.nanoTime()
            engine.stream(request).collect { chunk ->
                if (chunk.done) {
                    stopReason = StopReason.COMPLETED
                } else {
                    if (firstChunkNanos == 0L) firstChunkNanos = System.nanoTime()
                    accumulated.append(chunk.text)
                    tokens++
                    emit(GenerationUpdate.Chunk(chunk.text, accumulated.toString()))
                }
            }
        } catch (cancelled: CancellationException) {
            stopReason = StopReason.CANCELLED
            emit(GenerationUpdate.Failed(CharalyError.Cancelled()))
            throw cancelled
        } catch (error: InferenceError) {
            if (error is InferenceError.Cancelled) {
                stopReason = StopReason.CANCELLED
                emit(GenerationUpdate.Failed(CharalyError.Cancelled()))
            } else {
                emit(GenerationUpdate.Failed(CharalyError.Generation(error.message ?: "unknown error")))
            }
            return@flow
        } finally {
            engine.stop()
        }

        val reply = accumulated.toString()
        var current = withUserLine.evolved(
            conversation = withUserLine.conversation.append(
                TranscriptEntry(
                    id = "turn-${turn + 1}",
                    turn = turn + 1,
                    role = TranscriptRole.CHARACTER,
                    // Action tags are machinery, not prose. The player reads the words
                    // around a proposal and never sees the proposal itself; storing them
                    // verbatim put `<charaly:action .../>` in the middle of a character's
                    // dialogue, which is the raw protocol on screen.
                    text = ProposedActionParser.stripActions(reply).ifBlank { "(no reply)" },
                    at = withUserLine.worldClock.now,
                    sceneId = withUserLine.currentSceneId,
                    speakerId = speaker,
                    style = TranscriptStyle.DIALOGUE,
                ),
            ),
        )
        current = currentSceneTurn(current)

        // LLM action boundary: explicit tags only, always validated.
        val eventEngine = EventEngine(definition)
        val validator = ActionValidator(definition)
        var appliedActionCount = 0
        val parserStart = System.nanoTime()
        ProposedActionParser.parse(reply).forEach { action ->
            val review = validator.toPayload(action)
            if (review is ActionReview.Rejected) {
                emit(GenerationUpdate.RejectedAction(review))
                return@forEach
            }
            val accepted = review as ActionReview.Accepted
            when (
                val applied = eventEngine.applyImmediately(
                    current,
                    accepted.payload,
                    // Attributed to the model, because it was the model's idea. The event
                    // engine records this on the change log, which is how "what did the
                    // model actually change?" becomes an answerable question instead of an
                    // impression.
                    origin = dev.charaly.runtime.domain.events.EventOrigin.MODEL_PROPOSAL,
                    note = accepted.action.reason,
                )
            ) {
                is EventApplication.Rejected -> {
                    // The proposal parsed, but the event engine refused it. The
                    // model gets no say: a rejected action leaves no trace.
                    emit(
                        GenerationUpdate.RejectedAction(
                            ActionReview.Rejected(accepted.action, applied.validation.describe()),
                        ),
                    )
                }
                is EventApplication.Applied -> {
                    current = applied.instance
                    appliedActionCount++
                    emit(GenerationUpdate.AppliedAction(accepted, applied.changes))
                }
            }
        }

        // Memory write pipeline. Runs after the action proposals have been applied,
        // so what is remembered is the world as it actually ended up, not the world as
        // the transcript reads. Everything the gates reject is simply not written.
        current = rememberTurn(current, definition, speaker, turn)
        val parserMillis = (System.nanoTime() - parserStart) / 1_000_000L

        val persistenceStart = System.nanoTime()
        val persisted = runCatching { repository.saveInstance(current) }
        val persistenceMillis = (System.nanoTime() - persistenceStart) / 1_000_000L
        if (persisted.isFailure) {
            // A turn that could not be saved is a failed turn, FULL STOP.
            //
            // Deriving conditional events, clock time and chapters from here would build on
            // a save that does not exist and then report the turn as finished: the response
            // on screen and the story on disk would tell different stories, and the next
            // turn would run against either one of them depending on which write happened
            // to land. The last persisted state is the only safe one - the transcript and
            // actions of this turn stay out of it, so a retry re-runs the whole turn and
            // applies its actions exactly once.
            emit(
                GenerationUpdate.Failed(
                    CharalyError.Persistence(persisted.exceptionOrNull()?.message ?: "unknown error"),
                ),
            )
            return@flow
        }
        onPersist(current)

        // Chapters and conditional events are derived from authoritative state
        // *after* the turn, so a screen can never show a chapter or an event the
        // engine did not actually apply.
        //
        // ONE conditional-event poll per turn, and it lives inside [advance] below - the
        // same path a debug "advance 30 minutes" takes. Polling here as well would give
        // every pack event a second chance to fire within the same player turn: a
        // `repeatable` event with `cooldownMinutes = 0` (whose condition never flips back)
        // then lands its effects twice - two world changes, two memories and a doubled
        // relationship delta. That double landing is measured in
        // `ConditionalEventExactlyOnceTest` (expected one trust step, observed two).
        // Determinism and event order are unchanged; the single poll simply runs after the
        // clock step instead of before it. If `advance` fails as a whole the turn keeps the
        // written response and skips polling entirely rather than polling twice.
        val eventsStart = System.nanoTime()

        // ---- the clock ---------------------------------------------------
        //
        // Time passes because the turn happened, and by an amount the *runtime*
        // computed from engine-side facts (see [TurnClockPolicy]). The model's prose is
        // not an input: a reply that claims an hour passed still costs two minutes,
        // because the model does not own world time.
        //
        // It runs through [advance], which is the same path a debug "advance 30 minutes"
        // takes - EventEngine.advanceTime, then pack-conditional events, then chapters.
        // That is what makes the world genuinely move on its own rather than merely
        // displaying a later time: routines fire, scheduled events come due, NPCs are
        // relocated by validated events, and consequences in the ledger mature.
        val elapsed = TurnClockPolicy.minutesFor(
            userInputChars = userInput.length,
            appliedActions = appliedActionCount,
        )
        val advanced = runCatching { advance(current, elapsed) }.getOrDefault(current)
        val finished = runCatching {
            save(ChapterPlanner.derive(advanced, definition))
        }.getOrDefault(advanced)
        val eventsMillis = (System.nanoTime() - eventsStart) / 1_000_000L

        // Engine-side clocks, when the engine can measure. A scripted engine returns null
        // and every timing stays absent rather than zero.
        val nativeTimings = (engine as? dev.charaly.runtime.model.LocalInferenceEngine)?.lastTimings()
        val nowNanos = System.nanoTime()
        val ttftMillis = if (firstChunkNanos == 0L) 0L else (firstChunkNanos - perfStart) / 1_000_000L
        val perf = TurnPerf(
            totalMillis = (nowNanos - perfStart) / 1_000_000L,
            ttftMillis = ttftMillis,
            residencyMillis = residencyMillis,
            contextBuildMillis = contextBuildMillis,
            promptEvalMillis = nativeTimings?.promptMillis,
            tokenizeMillis = nativeTimings?.tokenizeMicros?.let { it / 1000L },
            evalMillis = nativeTimings?.evalMicros?.let { it / 1000L },
            dispatchMillis = if (inferenceCallNanos > 0L) {
                (inferenceCallNanos - perfStart) / 1_000_000L
            } else {
                0L
            },
            generationMillis = nativeTimings?.generationMillis,
            parserMillis = parserMillis,
            eventsMillis = eventsMillis,
            persistenceMillis = persistenceMillis,
            contextChars = contextChars,
            promptTokens = nativeTimings?.promptTokens,
            generatedTokens = nativeTimings?.generatedTokens ?: tokens.takeIf { it > 0 },
            reusedTokens = nativeTimings?.reusedTokens,
            tokensPerSecond = nativeTimings?.tokensPerSecond,
            promptTokensPerSecond = nativeTimings?.promptTokensPerSecond,
            modelLoadMillis = modelLoadMillis,
            threadsDecode = nativeTimings?.threads?.takeIf { it > 0 },
            threadsPrompt = nativeTimings?.threadsBatch?.takeIf { it > 0 },
        )

        emit(
            GenerationUpdate.Finished(
                text = reply,
                stopReason = stopReason,
                completionTokens = tokens,
                instance = finished,
                elapsedStoryMinutes = elapsed.minutes,
                perf = perf,
            ),
        )
        // PHASE 10: the whole turn body runs off the collector's thread. See
        // [turnDispatcher] for what that moves and why it was the main thread before.
        // Placed last so it reads as the turn's outermost statement.
    }.flowOn(turnDispatcher)

    /** Stops an in-flight generation (UI "stop" button). */
    fun stop() = engine.stop()

    // ------------------------------------------------------------------
    // Memory write pipeline
    // ------------------------------------------------------------------

    /**
     * Runs the memory pipeline over one completed turn.
     *
     * Three things happen here, and the order matters:
     *
     *  1. the *world* consequences of the turn become memories - every participant
     *     witnesses what happened in the scene they were standing in;
     *  2. what was actually *said* becomes a candidate, for the speaker and for
     *     everyone else present;
     *  3. consolidation runs, so a memory that contradicts an older one is resolved
     *     in the same pass rather than lingering contradictory until someone asks.
     *
     * The speaker is deliberately included in step 2 as well as step 1: a character
     * remembers being told things, not only what happened to them.
     *
     * ## Which hour these memories are stamped with
     *
     * `instance.worldClock.now` as it stands *here*, which is the hour the exchange
     * happened at - before the turn's clock advance. The reply was produced at that
     * time, so a memory written about it must carry that time, or the transcript and
     * the memory would disagree about when something was witnessed. The advance runs
     * after this call returns.
     *
     * Every one of those writes goes through [MemoryWriter]'s gates, so this method
     * cannot itself create a memory the pipeline would have rejected. That is the
     * point of routing it rather than constructing memories inline.
     */
    private fun rememberTurn(
        instance: StoryInstance,
        definition: WorldDefinition,
        speaker: CharacterId,
        turn: Int,
    ): StoryInstance {
        val now = instance.worldClock.now
        val scene = instance.currentScene()
        val listeners = buildList {
            speaker.let(::add)
            scene?.participants?.forEach { if (it != speaker) add(it) }
        }
        if (listeners.isEmpty()) return instance

        val memoryIdPrefix = "mem-$turn"
        val known = { id: CharacterId -> definition.character(id) != null }
        val canonText = instance.knowledge.truth.values
            .map { it.render() }
            .filter { it.isNotBlank() }

        var working = instance

        // 1. Witnessed events. Kept as SCENE "what happened here" memories, which are
        // what make a returning NPC able to say "you were here last night".
        //
        // Visibility is CHARACTER, granted to the others who were *present*, and the
        // wording is deliberately owner-relative. Marking this WORLD-visible would be
        // the obvious choice and it is wrong twice over: it would hand every bystander
        // a memory about a scene they were not in, and the text ("you were here with
        // me") is written from the owner's point of view, so read by anyone else it is
        // not even a true sentence.
        val witnesses = listeners.toSet()
        val witnessed = listeners.map { listener ->
            MemoryWriter.Candidate(
                ownerId = listener,
                content = if (listeners.size == 1) {
                    "I was at ${scene?.locationId?.value ?: "somewhere"}."
                } else {
                    "We were at ${scene?.locationId?.value ?: "somewhere"} together."
                },
                tier = MemoryTier.SCENE,
                source = MemorySource.EVENT,
                relatedLocationId = scene?.locationId,
                relatedThreadIds = scene?.activeThreadIds.orEmpty(),
                sceneId = scene?.id?.value,
                visibility = MemoryVisibility.CHARACTER,
                visibleTo = (witnesses - listener).toList(),
            )
        }
        working = working.applyMemories(
            MemoryWriter.write(
                store = working.memories,
                candidates = witnessed,
                now = now,
                idPrefix = "$memoryIdPrefix-scene",
                isKnownCharacter = known,
                canonFacts = canonText,
            ).store,
        )

        // 2. What was said, as candidates for everyone who heard it. Granted to the
        // other listeners only: what one person said in a scene is not something an
        // absent third party learned.
        val said = listeners.flatMap { listener ->
            MemoryWriter.extractFrom(working, sinceTurn = turn, ownerId = listener)
                .map { it.copy(visibleTo = (witnesses - listener).toList()) }
        }
        working = working.applyMemories(
            MemoryWriter.write(
                store = working.memories,
                candidates = said,
                now = now,
                idPrefix = "$memoryIdPrefix-said",
                isKnownCharacter = known,
                canonFacts = canonText,
            ).store,
        )

        // 3. Consolidate, so contradictions raised by this turn resolve now.
        return working.evolved(memories = MemoryConsolidator.consolidate(working.memories, now))
    }

    /** Copies a new [MemoryStore] in, keeping each character's id index in step. */
    private fun StoryInstance.applyMemories(store: dev.charaly.runtime.domain.memory.MemoryStore): StoryInstance {
        if (store.size == memories.size) return this
        val indexed = worldState.characters.mapValues { (_, runtime) ->
            val owned = store.idsOf(runtime.characterId)
            if (owned == runtime.memoryIds) runtime else runtime.copy(memoryIds = owned)
        }
        return copy(
            worldState = worldState.copy(characters = indexed),
            memories = store,
            updatedAt = worldState.worldClock.now,
        )
    }

    // ------------------------------------------------------------------
    // Imported characters
    // ------------------------------------------------------------------

    /**
     * Reads a character card out of file bytes without touching storage.
     *
     * Read-only by construction: it takes a [ByteArray] and returns a preview or an error,
     * and it has no repository reference. Cancelling an import therefore cannot leave a
     * partial record, because no record is written until [commitImportedCharacter] is
     * called - which is the only way a card becomes content.
     */
    fun previewCharacterCard(bytes: ByteArray, sourceName: String = ""): Result<
        dev.charaly.runtime.compat.CharacterCardPreview,
        > = dev.charaly.runtime.compat.CharacterCardReader.read(bytes, sourceName)

    /** Every imported character the user has, for the cast picker. */
    suspend fun listImportedCharacters(): List<dev.charaly.runtime.persistence.ImportedCharacter> =
        library.list()

    suspend fun importedCharacter(id: CharacterId): dev.charaly.runtime.persistence.ImportedCharacter? =
        library.get(id)

    /**
     * Writes a confirmed card into the library.
     *
     * The one commit point in the import flow. A preview that the user cancelled never
     * reaches here, which is what makes cancelling genuinely free of side effects.
     */
    suspend fun commitImportedCharacter(
        preview: dev.charaly.runtime.compat.CharacterCardPreview,
        rawJson: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): dev.charaly.runtime.persistence.ImportedCharacter = library.saveResolvingCollisions(
        dev.charaly.runtime.persistence.ImportedCharacter(
            preview = preview,
            rawJson = rawJson,
            importedAtEpochMs = nowEpochMs,
        ),
    )

    suspend fun deleteImportedCharacter(id: CharacterId) = library.delete(id)

    // ------------------------------------------------------------------
    // Local model management
    // ------------------------------------------------------------------

    /**
     * Loads a local GGUF model through the configured [InferenceEngine].
     *
     * The runtime exposes this so the UI never has to reach past it to the
     * engine, and so "which model is loaded" has exactly one answer.
     */
    suspend fun loadModel(request: ModelLoadRequest): LoadOutcome = engine.loadModel(request)

    suspend fun unloadModel() = engine.unloadModel()

    fun isModelLoaded(): Boolean = engine.isLoaded()

    fun loadedModel(): ModelInfo? = engine.modelInfo()

    /** The engine in use, for capability checks. Never for inference calls. */
    fun engineInfo(): String = engine.modelInfo()?.displayName ?: engine.javaClass.simpleName

    // ------------------------------------------------------------------
    // Developer diagnostics
    // ------------------------------------------------------------------

    /**
     * The exact prompt ContextBuilder would send for a turn.
     *
     * This exists for the developer panel, which is hidden unless the user turns
     * Developer Mode on. It is read-only: building a request cannot change the
     * world.
     */
    fun previewContext(
        instance: StoryInstance,
        characterId: CharacterId? = null,
        userInput: String = "",
    ): dev.charaly.runtime.context.InferenceContext? {
        val definition = resolver.peek(instance)
        val speaker = characterId ?: instance.focusOrFirst() ?: return null
        val scene = SceneDirector(definition).directScene(instance, speaker) ?: return null
        return ContextBuilder(definition, contextBudgetFor(instance))
            .buildContext(instance, scene, speaker, userInput)
    }

    /** Estimated prompt size, for the developer's "token estimate" readout. */
    fun promptEstimate(context: dev.charaly.runtime.context.InferenceContext): Int =
        context.estimatedChars()

    /**
     * The story health report.
     *
     * Read-only and therefore safe to call from a developer panel that recomputes on
     * every recomposition. Never surfaced to a normal player: a user who is told their
     * story has three contradictory facts has been told something they cannot act on.
     */
    fun storyHealth(instance: StoryInstance): StoryHealthAnalyzer.Report =
        StoryHealthAnalyzer(resolver.peek(instance)).analyse(instance)

    /**
     * The ContextBuilder's section breakdown, for the developer "what did the model
     * actually receive?" inspector.
     */
    fun contextSections(
        instance: StoryInstance,
        characterId: CharacterId? = null,
    ): List<ContextBudgetInspector.ContextSection> {
        val speaker = characterId ?: instance.focusOrFirst() ?: return emptyList()
        return ContextBudgetInspector(resolver.peek(instance), contextBudgetFor(instance))
            .sections(instance, speaker)
    }

    /** The model metadata the developer panel shows. */
    fun modelDiagnostics(): String = buildString {
        appendLine("engine: ${engine.javaClass.simpleName}")
        appendLine("loaded: ${engine.isLoaded()}")
        engine.modelInfo()?.let {
            appendLine("model: ${it.displayName}")
            appendLine("arch: ${it.metadata["general.architecture"]}")
            appendLine("quant: ${it.quantLevel}")
            appendLine("ctx: ${it.contextSize}")
        }
        // The engine's own last failure, in the one place a technical detail belongs.
        //
        // A normal player never sees this: it is behind Developer Mode. But when a load or
        // a decode fails and the product sentence is necessarily vague ("this model could
        // not be loaded"), the specific reason has to exist SOMEWHERE or the report is
        // useless. `lastDiagnostic` is written for a person, not a `Throwable.toString()` -
        // no native handle, no pointer, no stack.
        val diagnostic = (engine as? dev.charaly.runtime.model.LocalInferenceEngine)
            ?.lastDiagnostic()
            .orEmpty()
        appendLine("last diagnostic: ${diagnostic.ifBlank { "(none)" }}")
        appendLine("params: $params")
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    /**
     * Scenes become authoritative only through `SceneStarted`, which the engine
     * validates (participants must actually be there).
     */
    private fun commitScene(
        instance: StoryInstance,
        scene: Scene,
        characterId: CharacterId,
        definition: WorldDefinition,
    ): StoryInstance {
        if (instance.worldState.activeScenes.containsKey(scene.id)) {
            return instance
        }
        val runtime = instance.characters[characterId] ?: return instance
        if (runtime.locationId != scene.locationId) return instance

        val applied = EventEngine(definition).applyImmediately(
            instance = instance,
            payload = dev.charaly.runtime.domain.events.SceneStarted(
                sceneId = scene.id,
                locationId = scene.locationId,
                participants = scene.participants,
                objective = scene.objective,
                activeThreadIds = scene.activeThreadIds,
            ),
            note = "derived by SceneDirector",
        )
        return (applied as? EventApplication.Applied)?.instance ?: instance
    }

    private fun currentSceneTurn(instance: StoryInstance): StoryInstance {
        val scene = instance.currentScene() ?: return instance
        return instance.evolved(worldState = instance.worldState.withScene(scene.withTurn()))
    }
}

internal fun StoryInstance.focusOrFirst(): CharacterId? =
    focusCharacterId ?: worldState.characters.keys.minByOrNull { it.value }
