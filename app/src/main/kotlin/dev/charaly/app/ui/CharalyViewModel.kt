package dev.charaly.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.charaly.app.CharalyApplication
import dev.charaly.app.model.ModelEntry
import dev.charaly.app.model.ModelManager
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the whole app.
 *
 * The ViewModel owns NO world state: it holds the latest snapshot handed back by
 * [CharalyRuntime] and renders projections of it. Every authoritative change
 * went through the event engine before it got here.
 */
data class CharalyUiState(
    val loading: Boolean = true,
    val packs: List<StoryPack> = emptyList(),
    val instances: List<StoryInstance> = emptyList(),
    val models: List<ModelEntry> = emptyList(),
    val story: StoryInstance? = null,
    val transcript: List<dev.charaly.runtime.domain.TranscriptEntry> = emptyList(),
    val pendingReply: String = "",
    val generating: Boolean = false,
    val focusCharacterId: CharacterId? = null,
    val engineStatus: EngineStatus = EngineStatus.Unknown,
    val error: String? = null,
    val notice: String? = null,
) {
    val canGenerate: Boolean get() = engineStatus is EngineStatus.Ready && !generating && story != null
}

/** What the model situation is, in terms the UI can act on. */
sealed interface EngineStatus {
    data object Unknown : EngineStatus
    data object Loading : EngineStatus
    data class Ready(val name: String) : EngineStatus
    data class Failed(val reason: String) : EngineStatus
}

class CharalyViewModel(
    private val runtime: CharalyRuntime,
    private val models: ModelManager,
) : ViewModel() {

    private val _state = MutableStateFlow(CharalyUiState())
    val state: StateFlow<CharalyUiState> = _state.asStateFlow()

    private var generationJob: Job? = null

    init {
        refresh()
    }

    /** Offline restore: read local documents, seed the demo story if empty. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val restored = runCatching { runtime.restore() }.getOrElse { error ->
                _state.update {
                    it.copy(loading = false, error = error.message ?: "could not read local stories")
                }
                return@launch
            }

            var packs = restored.packs
            if (packs.isEmpty()) {
                // A first run needs something to play with. It is authored
                // locally, so this still works in airplane mode.
                runCatching { runtime.createPack(SampleWorlds.libraryPack()) }
                packs = runCatching { runtime.listPacks() }.getOrDefault(packs)
            }

            val selected = models.selectedModel()
            val engineState = if (selected == null) {
                EngineStatus.Failed("No model loaded. Import a GGUF model to generate replies.")
            } else {
                // A model was selected before: reload it on launch.
                loadModel(selected)
            }

            _state.update {
                it.copy(
                    loading = false,
                    packs = packs,
                    instances = restored.instances,
                    models = models.managedModels(),
                    engineStatus = engineState,
                    notice = if (restored.failures.isEmpty()) {
                        null
                    } else {
                        "${restored.failures.size} story file(s) could not be read and were skipped."
                    },
                )
            }
        }
    }

    fun openStory(instance: StoryInstance) {
        _state.update {
            it.copy(
                story = instance,
                transcript = instance.conversation.entries,
                focusCharacterId = instance.focusCharacterId,
                pendingReply = "",
                error = null,
            )
        }
    }

    /** Continues the most recent playthrough of a pack, or starts a new one. */
    fun startOrContinue(pack: StoryPack) {
        viewModelScope.launch {
            val existing = runCatching { runtime.continueStory(pack.id) }.getOrNull()
            val instance = existing ?: runCatching {
                runtime.startStory(
                    pack = pack,
                    instanceId = StoryInstanceId("story-${System.currentTimeMillis()}"),
                )
            }.getOrElse { error ->
                _state.update { it.copy(error = error.message ?: "could not start the story") }
                return@launch
            }
            openStory(instance)
        }
    }

    fun selectCharacter(characterId: CharacterId) {
        _state.update { it.copy(focusCharacterId = characterId) }
    }

    fun send(text: String) {
        val story = _state.value.story ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val speaker = _state.value.focusCharacterId ?: story.focusCharacterId
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update { it.copy(generating = true, pendingReply = "", error = null) }
            try {
                runtime.respond(story, trimmed, speaker).collect { update ->
                    when (update) {
                        is GenerationUpdate.Started -> Unit
                        is GenerationUpdate.Chunk ->
                            _state.update { it.copy(pendingReply = update.accumulated) }
                        is GenerationUpdate.AppliedAction ->
                            _state.update {
                                it.copy(notice = "World updated: ${update.changes.joinToString("; ")}")
                            }
                        is GenerationUpdate.RejectedAction ->
                            _state.update {
                                it.copy(notice = "Ignored an invalid world action: ${update.review.reason}")
                            }
                        is GenerationUpdate.Finished -> {
                            _state.update {
                                it.copy(
                                    story = update.instance ?: it.story,
                                    transcript = update.instance?.conversation?.entries ?: it.transcript,
                                    pendingReply = "",
                                    generating = false,
                                )
                            }
                        }
                        is GenerationUpdate.Failed -> {
                            _state.update {
                                it.copy(generating = false, error = update.error.message)
                            }
                        }
                    }
                }
            } finally {
                _state.update { it.copy(generating = false) }
            }
        }
    }

    /** The user pressed stop. Aborts native generation immediately. */
    fun stopGeneration() {
        generationJob?.cancel()
        runtime.stop()
        _state.update { it.copy(generating = false, pendingReply = "") }
    }

    /** Deterministic time travel from the inspector. Never touches the model. */
    fun advanceClock(minutes: Long) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val advanced = runCatching {
                runtime.advance(runtime.loadStory(story.id) ?: story, StoryDuration(minutes))
            }.getOrNull() ?: return@launch
            _state.update {
                it.copy(
                    story = advanced,
                    transcript = advanced.conversation.entries,
                    notice = "Clock advanced to ${advanced.worldClock.now.format()}",
                )
            }
        }
    }

    // ---- model management ---------------------------------------------

    fun importModel(uri: android.net.Uri) {
        viewModelScope.launch {
            models.importFrom(uri)
                .onSuccess { entry ->
                    models.select(entry)
                    _state.update {
                        it.copy(models = models.managedModels(), notice = "Imported ${entry.displayName}")
                    }
                    loadModel(entry)
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "import failed") }
                }
        }
    }

    fun selectModel(model: ModelEntry) {
        viewModelScope.launch {
            models.select(model)
            loadModel(model)
        }
    }

    fun deleteModel(model: ModelEntry) {
        viewModelScope.launch {
            models.delete(model)
            val remaining = models.managedModels()
            val selected = models.selectedModel()
            val status = when {
                selected != null -> loadModel(selected)
                else -> EngineStatus.Failed("No model loaded. Import a GGUF model to generate replies.")
            }
            _state.update { it.copy(models = remaining, engineStatus = status) }
        }
    }

    fun refreshModels() {
        _state.update { it.copy(models = models.managedModels()) }
    }

    /**
     * Loads the model through the runtime, so the runtime's [InferenceEngine] is
     * the single owner of "which model is loaded".
     */
    private suspend fun loadModel(model: ModelEntry): EngineStatus {
        _state.update { it.copy(engineStatus = EngineStatus.Loading) }
        val status = when (
            val outcome = runCatching {
                runtime.loadModel(
                    ModelLoadRequest(path = model.absolutePath, displayName = model.displayName),
                )
            }.getOrNull()
        ) {
            is LoadOutcome.Loaded -> EngineStatus.Ready(outcome.info.displayName)
            is LoadOutcome.Failed -> EngineStatus.Failed(outcome.error.message ?: "load failed")
            null -> EngineStatus.Failed("Model could not be loaded.")
        }
        _state.update { it.copy(engineStatus = status) }
        return status
    }

    fun consumeNotice() = _state.update { it.copy(notice = null, error = null) }

    fun storyInspector(definition: WorldDefinition?): StoryInstance? = _state.value.story

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        fun factory(application: CharalyApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CharalyViewModel(application.charaly, application.modelManager) as T
            }
    }
}
