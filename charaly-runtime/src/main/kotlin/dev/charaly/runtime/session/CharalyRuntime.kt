package dev.charaly.runtime.session

import dev.charaly.runtime.context.ContextBuilder
import dev.charaly.runtime.director.ActionReview
import dev.charaly.runtime.director.ActionValidator
import dev.charaly.runtime.director.ProposedActionParser
import dev.charaly.runtime.director.SceneDirector
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole
import dev.charaly.runtime.domain.TranscriptStyle
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.EventPayload
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.ScheduleResult
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.inference.GenerationParams
import dev.charaly.runtime.inference.InferenceEngine
import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelInfo
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.inference.StopReason
import dev.charaly.runtime.persistence.CharalyRepository
import dev.charaly.runtime.persistence.RestoredWorld
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    override fun toString(): String = this::class.simpleName ?: "CharalyError"
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
     * Deterministic time travel + event drain. Never touches the model.
     *
     * IMPORTANT: [instance] must be the latest snapshot. The story instance is
     * immutable, so passing a snapshot from before a generation would overwrite
     * the transcript that generation just persisted. Use [loadStory] (or the
     * value returned by the last mutating call) when in doubt.
     */
    suspend fun advance(instance: StoryInstance, by: StoryDuration): StoryInstance {
        val result = EventEngine(resolver.forInstance(instance)).advanceClock(instance, by)
        return save(result.instance)
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

    suspend fun scheduleAndProcess(instance: StoryInstance, payload: EventPayload): StoryInstance {
        val engineForWorld = EventEngine(resolver.forInstance(instance))
        return when (val result = engineForWorld.scheduleAfter(instance, payload, StoryDuration.ZERO)) {
            is ScheduleResult.Scheduled -> {
                val applied = engineForWorld.applyEvent(result.instance, result.events.first())
                val updated = (applied as? EventApplication.Applied)?.instance ?: result.instance
                save(updated)
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
        val speaker = characterId ?: instance.focusOrFirst()
        if (speaker == null) {
            emit(GenerationUpdate.Failed(CharalyError.NoCharacter()))
            return@flow
        }
        if (!engine.isLoaded()) {
            emit(GenerationUpdate.Failed(CharalyError.ModelNotLoaded()))
            return@flow
        }

        val definition = resolver.forInstance(instance)
        val director = SceneDirector(definition)
        val builder = ContextBuilder(definition)

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
            params = params,
        )

        val accumulated = StringBuilder()
        var tokens = 0
        var stopReason = StopReason.COMPLETED

        try {
            engine.stream(request).collect { chunk ->
                if (chunk.done) {
                    stopReason = StopReason.COMPLETED
                } else {
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
                    text = reply.ifBlank { "(no reply)" },
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
        ProposedActionParser.parse(reply).forEach { action ->
            val review = validator.toPayload(action)
            if (review is ActionReview.Rejected) {
                emit(GenerationUpdate.RejectedAction(review))
                return@forEach
            }
            val accepted = review as ActionReview.Accepted
            when (val applied = eventEngine.applyImmediately(current, accepted.payload)) {
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
                    emit(GenerationUpdate.AppliedAction(accepted, applied.changes))
                }
            }
        }

        runCatching { repository.saveInstance(current) }
            .onSuccess { onPersist(current) }
            .onFailure { emit(GenerationUpdate.Failed(CharalyError.Persistence(it.message ?: "unknown error"))) }

        emit(
            GenerationUpdate.Finished(
                text = reply,
                stopReason = stopReason,
                completionTokens = tokens,
                instance = current,
            ),
        )
    }

    /** Stops an in-flight generation (UI "stop" button). */
    fun stop() = engine.stop()

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
