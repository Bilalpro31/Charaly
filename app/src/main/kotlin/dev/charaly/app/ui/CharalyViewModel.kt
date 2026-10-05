package dev.charaly.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.charaly.app.AppPreferences
import dev.charaly.app.CharalyApplication
import dev.charaly.app.model.ModelEntry
import dev.charaly.app.model.ModelManager
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PersonaBinding
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.inference.LoadOutcome
import dev.charaly.runtime.inference.ModelLoadRequest
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelCatalog
import dev.charaly.runtime.model.ModelProfile
import dev.charaly.runtime.model.ModelRegistry
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.presentation.GenerationPhase
import dev.charaly.runtime.presentation.LibrarySnapshot
import dev.charaly.runtime.presentation.MemoryPanelSnapshot
import dev.charaly.runtime.presentation.ModelDetailSnapshot
import dev.charaly.runtime.presentation.ModelLibrarySnapshot
import dev.charaly.runtime.presentation.NewStoryDraft
import dev.charaly.runtime.presentation.NewStorySnapshot
import dev.charaly.runtime.presentation.NewStoryStep
import dev.charaly.runtime.presentation.NewStoryPresenter
import dev.charaly.runtime.presentation.PackDetailSnapshot
import dev.charaly.runtime.presentation.RegistryModelStatus
import dev.charaly.runtime.presentation.StoryInfoSnapshot
import dev.charaly.runtime.presentation.StorySnapshot
import dev.charaly.runtime.presentation.WorldPanelSnapshot
import dev.charaly.runtime.session.CharalyError
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The whole app's UI state, in one immutable value.
 *
 * Two rules hold everywhere in this file:
 *  1. the ViewModel owns no world state - it holds the latest snapshot the runtime
 *     handed back and projects it through the presenters in the runtime module;
 *  2. every world-affecting action is a call into [CharalyRuntime], which routes it
 *     through the event engine. There is no path from a button to `WorldState`.
 */
data class CharalyUiState(
    val loading: Boolean = true,
    val packs: List<StoryPack> = emptyList(),
    val instances: List<StoryInstance> = emptyList(),
    val installedModels: List<InstalledModel> = emptyList(),
    val activeModelId: String = "",
    val loadedModelId: String? = null,

    // story session
    val story: StoryInstance? = null,
    val streamingText: String = "",
    val generationPhase: GenerationPhase = GenerationPhase.IDLE,
    val failureMessage: String = "",

    // library
    val libraryQuery: String = "",
    val libraryGenres: Set<String> = emptySet(),
    val librarySort: dev.charaly.runtime.presentation.PackSortOrder =
        dev.charaly.runtime.presentation.PackSortOrder.RECENT,

    // sessions
    val sessionsQuery: String = "",
    val sessionsSort: dev.charaly.runtime.presentation.SessionSortOrder =
        dev.charaly.runtime.presentation.SessionSortOrder.RECENT,

    // models
    val modelQuery: String = "",
    val modelBusy: Boolean = false,

    // new story wizard
    val newStoryDraft: NewStoryDraft? = null,
    val newStoryStep: NewStoryStep = NewStoryStep.SCENARIO,
    /** Set by createStory; the shell uses it to hand off to the immersive screen. */
    val lastCreatedStoryId: String? = null,

    // app preferences
    val onboardingComplete: Boolean = false,
    val developerMode: Boolean = false,
    val darkTheme: Boolean = true,
    val reduceMotion: Boolean = false,
    val lastFailedGeneration: String = "",

    val notice: String? = null,
    val error: String? = null,
) {
    val canGenerate: Boolean
        get() = loadedModelId != null && !generationPhase.isBusy() && story != null

    val hasInstalledModel: Boolean get() = installedModels.isNotEmpty()
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
    private val registry: ModelRegistry,
    private val catalog: ModelCatalog,
    private val preferences: AppPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(CharalyUiState())
    val state: StateFlow<CharalyUiState> = _state.asStateFlow()

    private val engineStatus = MutableStateFlow<EngineStatus>(EngineStatus.Unknown)

    private var generationJob: Job? = null

    init {
        refresh()
    }

    // ------------------------------------------------------------------
    // Boot
    // ------------------------------------------------------------------

    /**
     * Offline restore: read local documents, seed the demo packs if the library is
     * empty, then load the model the user last used.
     *
     * No network is involved anywhere in this function. That is why the app works
     * in airplane mode once a model is on the device.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val restored = runCatching { runtime.restore() }.getOrElse { error ->
                _state.update {
                    it.copy(loading = false, error = error.message ?: "could not read local stories")
                }
                return@launch
            }

            // Seeding is idempotent per pack, not "only when the library is empty":
            // an existing install gets the new demo worlds without losing the user's
            // own packs, and a fresh install still has something to play with.
            val existingIds = restored.packs.map { it.id }.toSet()
            val missing = DemoStoryPacks.all.filterNot { it.id in existingIds }
            runCatching { missing.forEach { runtime.createPack(it) } }
            val packs = runCatching { runtime.listPacks() }.getOrDefault(restored.packs)

            // Imported GGUF files and registry entries are reconciled on every
            // launch, so "installed" always means "a file exists".
            runCatching { models.syncRegistry(registry) }
            val installed = runCatching { registry.list() }.getOrDefault(emptyList())
            val active = runCatching { registry.activeId() }.getOrNull().orEmpty()

            val restoredInstances = runCatching { runtime.listInstances() }.getOrDefault(restored.instances)
            runCatching { restoredInstances.forEach { touchIfNewer(it) } }

            val status = when (val model = installed.firstOrNull { it.id == active }) {
                null -> EngineStatus.Failed("No model installed yet. Import a GGUF to generate replies.")
                else -> loadModel(model)
            }

            _state.update {
                it.copy(
                    loading = false,
                    packs = packs,
                    instances = restoredInstances,
                    installedModels = installed,
                    activeModelId = active,
                    onboardingComplete = preferences.onboardingComplete,
                    developerMode = preferences.developerMode,
                    darkTheme = preferences.darkTheme,
                    reduceMotion = preferences.reduceMotion,
                    notice = if (restored.failures.isEmpty()) {
                        null
                    } else {
                        "${restored.failures.size} story file(s) could not be read and were skipped."
                    },
                )
            }
            engineStatus.value = status
        }
    }

    // ------------------------------------------------------------------
    // Projections
    // ------------------------------------------------------------------

    private fun now(): Long = System.currentTimeMillis()

    fun definitions(): Map<String, WorldDefinition> = _state.value.packs.associate { pack ->
        pack.id.value to WorldDefinition(pack.characters, pack.locations)
    }

    fun homeSnapshot() = dev.charaly.runtime.presentation.HomePresenter.build(
        nowEpochMs = now(),
        instances = _state.value.instances,
        packs = _state.value.packs,
        definitions = definitions(),
        model = RegistryModelStatus(
            installed = currentInstalledModel(),
            engineLabel = runtime.engineInfo(),
            ready = engineStatus.value is EngineStatus.Ready,
            loadDetail = (engineStatus.value as? EngineStatus.Failed)?.reason.orEmpty(),
        ),
    )

    fun librarySnapshot(): LibrarySnapshot {
        val current = _state.value
        return dev.charaly.runtime.presentation.LibraryPresenter.build(
            nowEpochMs = now(),
            packs = current.packs,
            instances = current.instances,
            query = current.libraryQuery,
            selectedGenres = current.libraryGenres,
            sortOrder = current.librarySort,
        )
    }

    /**
     * The pack behind a route.
     *
     * Returns null for an id that no longer exists rather than throwing. A pack can
     * disappear while a detail screen is still on the back stack - deleted in the
     * creator, or a demo pack that failed to restore - and the screen must degrade to
     * its own "missing" state instead of taking the app down with it.
     */
    fun packById(packId: String): StoryPack? =
        _state.value.packs.firstOrNull { it.id.value == packId }

    fun packDetailSnapshot(packId: String): PackDetailSnapshot? {
        val current = _state.value
        return dev.charaly.runtime.presentation.PackDetailPresenter.buildOrNull(
            nowEpochMs = now(),
            pack = packById(packId),
            instances = current.instances,
            defaultProfileName = profileOf(packById(packId)?.defaultModelProfileId.orEmpty()).name,
        )
    }

    fun storySnapshot(): StorySnapshot? {
        val instance = _state.value.story ?: return null
        val current = _state.value
        return dev.charaly.runtime.presentation.StoryPresenter.build(
            instance = instance,
            pack = current.packs.firstOrNull { it.id == instance.storyPackId },
            definition = definitionFor(instance),
            phase = current.generationPhase,
            streamingText = current.streamingText,
            failureMessage = current.failureMessage,
            modelReady = current.loadedModelId != null,
        )
    }

    fun worldPanelSnapshot(): WorldPanelSnapshot? {
        val instance = _state.value.story ?: return null
        return dev.charaly.runtime.presentation.WorldPanelPresenter.build(
            instance = instance,
            pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId },
        )
    }

    /**
     * The story's world as places and people.
     *
     * Returns an empty snapshot rather than null when the story is missing, so a
     * restored navigation stack that names a deleted story renders the world's own
     * empty state instead of crashing.
     */
    fun worldSnapshot(instanceId: String? = null): dev.charaly.runtime.presentation.WorldSnapshot {
        val story = _state.value.story
        if (story == null || (instanceId != null && story.id.value != instanceId)) {
            return dev.charaly.runtime.presentation.WorldSnapshot("", "", "", emptyList(), emptyList())
        }
        val pack = _state.value.packs.firstOrNull { it.id == story.storyPackId }
        if (pack == null) {
            return dev.charaly.runtime.presentation.WorldSnapshot(
                worldTitle = story.packTitle,
                currentLocationName = "",
                timeLabel = story.worldClock.now.formatClock(),
                locations = emptyList(),
                characters = emptyList(),
            )
        }
        return dev.charaly.runtime.presentation.WorldPresenter.build(
            instance = story,
            pack = pack,
        )
    }

    /**
     * Walks the player into a place.
     *
     * Routed through the runtime so the arrival is a validated event: the player only
     * ends up somewhere that exists, and only meets the characters who are actually
     * standing there.
     */
    fun travelTo(locationId: String, onArrived: () -> Unit) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val arrived = runCatching { runtime.travelTo(story, LocationId(locationId)) }.getOrNull()
            if (arrived == null) {
                _state.update { it.copy(error = "You cannot get there from here.") }
                return@launch
            }
            _state.update {
                it.copy(
                    story = arrived,
                    streamingText = "",
                    generationPhase = GenerationPhase.IDLE,
                    notice = arrived.currentLocation()?.name?.let { name -> "You are at $name" },
                )
            }
            refreshInstances()
            onArrived()
        }
    }

    fun memoryPanelSnapshot(): MemoryPanelSnapshot? {
        val instance = _state.value.story ?: return null
        return dev.charaly.runtime.presentation.MemoryPresenter.build(
            instance = instance,
            pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId },
        )
    }

    fun storyInfoSnapshot(): StoryInfoSnapshot? {
        val instance = _state.value.story ?: return null
        return dev.charaly.runtime.presentation.StoryInfoPresenter.build(
            nowEpochMs = now(),
            instance = instance,
            pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId },
        )
    }

    fun sessionsSnapshot() = dev.charaly.runtime.presentation.SessionsPresenter.build(
        nowEpochMs = now(),
        instances = _state.value.instances,
        packs = _state.value.packs,
        query = _state.value.sessionsQuery,
        sortOrder = _state.value.sessionsSort,
    )

    fun modelLibrarySnapshot(): ModelLibrarySnapshot =
        dev.charaly.runtime.presentation.ModelLibraryPresenter.build(
            installedModels = _state.value.installedModels,
            catalog = catalog.items,
            activeModelId = _state.value.activeModelId,
            loadedModelId = _state.value.loadedModelId,
            downloadsAvailable = false,
            availableRamBytes = models.availableRamBytes(),
            query = _state.value.modelQuery,
        )

    fun modelDetailSnapshot(modelId: String): ModelDetailSnapshot? {
        val current = _state.value
        val model = current.installedModels.firstOrNull { it.id == modelId } ?: return null
        val binding = current.story?.takeIf { it.modelBinding.installedModelId == modelId }?.modelBinding
        val stories = current.instances.filter { it.modelBinding.installedModelId == modelId }
        return dev.charaly.runtime.presentation.ModelLibraryPresenter.detail(
            model = model,
            binding = binding,
            storiesUsing = stories,
            isLoaded = current.loadedModelId == modelId,
            availableRamBytes = models.availableRamBytes(),
        )
    }

    fun newStorySnapshot(): NewStorySnapshot? {
        val draft = _state.value.newStoryDraft ?: return null
        val pack = _state.value.packs.firstOrNull { it.id.value == draft.packId } ?: return null
        return NewStoryPresenter.build(
            draft = draft,
            pack = pack,
            installedModels = _state.value.installedModels,
            activeModelId = _state.value.activeModelId,
            step = _state.value.newStoryStep,
        )
    }

    fun profileOf(profileId: String): ModelProfile =
        dev.charaly.runtime.model.ModelProfileLibrary.resolve(profileId)

    fun engineStatus(): EngineStatus = engineStatus.value

    fun installedModel(modelId: String): InstalledModel? =
        _state.value.installedModels.firstOrNull { it.id == modelId }

    fun catalogItems() = catalog.items

    // ------------------------------------------------------------------
    // Navigation-facing actions
    // ------------------------------------------------------------------

    /** Opens a story and marks it played. */
    fun openStory(instanceId: String) {
        viewModelScope.launch {
            val instance = runtime.loadStory(StoryInstanceId(instanceId)) ?: return@launch
            val touched = runCatching { runtime.touch(instance, now()) }.getOrDefault(instance)
            bindModelForStory(touched)
            _state.update {
                it.copy(
                    story = touched,
                    streamingText = "",
                    generationPhase = GenerationPhase.IDLE,
                    failureMessage = "",
                    lastFailedGeneration = "",
                )
            }
            refreshInstances()
        }
    }

    /** The most recent playthrough of a pack, opened directly. */
    fun continueMostRecent(packId: String) {
        viewModelScope.launch {
            val instance = runCatching { runtime.continueStory(StoryPackId(packId)) }.getOrNull()
                ?: return@launch
            val touched = runCatching { runtime.touch(instance, now()) }.getOrDefault(instance)
            bindModelForStory(touched)
            _state.update { it.copy(story = touched, failureMessage = "", streamingText = "") }
            refreshInstances()
        }
    }

    // ------------------------------------------------------------------
    // New story
    // ------------------------------------------------------------------

    fun beginNewStory(packId: String) {
        val pack = _state.value.packs.firstOrNull { it.id.value == packId } ?: return
        _state.update {
            it.copy(
                newStoryDraft = NewStoryPresenter.initialDraft(pack),
                newStoryStep = NewStoryStep.SCENARIO,
            )
        }
    }

    fun newStoryStep(step: NewStoryStep) = _state.update { it.copy(newStoryStep = step) }

    fun updateDraft(transform: (NewStoryDraft) -> NewStoryDraft) {
        _state.update { current ->
            val draft = current.newStoryDraft ?: return@update current
            val updated = transform(draft)
            current.copy(newStoryDraft = updated, newStoryStep = NewStoryPresenter.stepFor(updated))
        }
    }

    /**
     * Creates the StoryInstance and opens it.
     *
     * The draft is turned into [StoryCreationOptions] here, the *resolved* model
     * binding is stored inside the instance, and the runtime builds the world
     * through the event engine. The UI never constructs world state.
     */
    fun createStory(packId: String) {
        val current = _state.value
        val pack = current.packs.firstOrNull { it.id.value == packId } ?: return
        val draft = current.newStoryDraft ?: return

        viewModelScope.launch {
            val binding = NewStoryPresenter.resolveBinding(
                pack = pack,
                draft = draft,
                installedModels = current.installedModels,
                activeModelId = current.activeModelId,
                nowEpochMs = now(),
            )
            val scenario = pack.scenario(draft.scenarioId)
            val persona = pack.persona(draft.personaId)

            val instance = runCatching {
                runtime.startStory(
                    pack = pack,
                    options = StoryCreationOptions(
                        instanceId = StoryInstanceId("story-${now()}"),
                        title = draft.storyTitle,
                        scenario = scenario,
                        persona = persona?.let {
                            PersonaBinding.from(it, draft.userName.ifBlank { it.name })
                        } ?: PersonaBinding.EMPTY,
                        startLocationId = scenario?.startLocationId,
                        focusCharacterId = draft.focusCharacterId.takeIf { it.isNotBlank() }
                            ?.let(::CharacterId),
                        castCharacterIds = draft.castCharacterIds.map(::CharacterId),
                        modelBinding = binding,
                        nowEpochMs = now(),
                    ),
                )
            }.getOrElse { error ->
                _state.update { it.copy(error = error.message ?: "could not start this story") }
                return@launch
            }

            bindModelForStory(instance)
            _state.update {
                it.copy(
                    story = instance,
                    newStoryDraft = null,
                    newStoryStep = NewStoryStep.SCENARIO,
                    lastCreatedStoryId = instance.id.value,
                    notice = "Stepped into ${instance.displayTitle}",
                )
            }
            refreshInstances()
        }
    }

    /** Clears the one-shot "a story was created" signal after the shell uses it. */
    fun consumeCreatedStory() = _state.update { it.copy(lastCreatedStoryId = null) }

    // ------------------------------------------------------------------
    // Story packs: creation and editing
    // ------------------------------------------------------------------

    /** Saves a pack the user built in the creator. Goes through the repository. */
    fun saveCreatedPack(pack: StoryPack) {
        viewModelScope.launch {
            runCatching { runtime.createPack(pack) }
                .onSuccess {
                    val packs = runCatching { runtime.listPacks() }.getOrDefault(_state.value.packs)
                    _state.update { it.copy(packs = packs, notice = "Created ${pack.title}") }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "could not save the story pack") }
                }
        }
    }

    /**
     * Saves an edited character.
     *
     * Editing a pack rewrites its *static* definition. Stories already running from
     * the previous definition keep their own world state, which is the whole reason
     * static and dynamic state are separate types.
     */
    fun updateCharacter(packId: String, character: dev.charaly.runtime.domain.CharacterDefinition) {
        updatePack(packId) { pack ->
            pack.copy(
                characters = pack.characters.map { if (it.id == character.id) character else it },
            )
        }
    }

    fun updateLocation(packId: String, location: dev.charaly.runtime.domain.Location) {
        updatePack(packId) { pack ->
            pack.copy(locations = pack.locations.map { if (it.id == location.id) location else it })
        }
    }

    fun updateEvent(packId: String, event: dev.charaly.runtime.domain.PackEventDefinition) {
        updatePack(packId) { pack ->
            pack.copy(events = pack.events.map { if (it.id == event.id) event else it })
        }
    }

    private fun updatePack(packId: String, transform: (StoryPack) -> StoryPack) {
        viewModelScope.launch {
            val pack = _state.value.packs.firstOrNull { it.id.value == packId } ?: return@launch
            val updated = runCatching { transform(pack) }.getOrElse { error ->
                _state.update { it.copy(error = error.message ?: "that change is not valid") }
                return@launch
            }
            runCatching { runtime.createPack(updated) }
                .onSuccess {
                    val packs = runCatching { runtime.listPacks() }.getOrDefault(_state.value.packs)
                    _state.update { it.copy(packs = packs, notice = "Saved ${updated.title}") }
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "could not save the story pack") }
                }
        }
    }

    /** Story info for any session, not just the open one. */
    fun sessionInfo(instanceId: String): dev.charaly.runtime.presentation.StoryInfoSnapshot? {
        val instance = _state.value.instances.firstOrNull { it.id.value == instanceId } ?: return null
        return dev.charaly.runtime.presentation.StoryInfoPresenter.build(
            nowEpochMs = now(),
            instance = instance,
            pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId },
        )
    }

    /** The transcript of a session, for the chapter/session detail screen. */
    fun sessionLines(instanceId: String): List<dev.charaly.runtime.presentation.StoryLine> {
        val instance = _state.value.instances.firstOrNull { it.id.value == instanceId } ?: return emptyList()
        return dev.charaly.runtime.presentation.StoryPresenter.build(
            instance = instance,
            pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId },
            definition = definitionFor(instance),
        ).lines
    }

    // ------------------------------------------------------------------
    // Conversation
    // ------------------------------------------------------------------

    /**
     * Sends the player's line.
     *
     * Everything the composer does is routed through here, so a "+" menu button has
     * no privileged access to the world: it only changes the shape of the input.
     */
    fun send(text: String, characterId: CharacterId? = null) {
        val story = _state.value.story ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val speaker = characterId ?: story.focusCharacterId
        runGeneration(story, trimmed, speaker)
    }

    private fun runGeneration(instance: StoryInstance, input: String, speaker: CharacterId?) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    generationPhase = GenerationPhase.THINKING,
                    streamingText = "",
                    failureMessage = "",
                    lastFailedGeneration = "",
                )
            }
            try {
                runtime.respond(instance, input, speaker).collect { update ->
                    when (update) {
                        is GenerationUpdate.Started -> _state.update {
                            it.copy(generationPhase = GenerationPhase.STREAMING)
                        }

                        is GenerationUpdate.Chunk -> _state.update {
                            it.copy(generationPhase = GenerationPhase.STREAMING, streamingText = update.accumulated)
                        }

                        is GenerationUpdate.AppliedAction -> _state.update {
                            it.copy(notice = "World updated: ${update.changes.joinToString("; ")}")
                        }

                        is GenerationUpdate.RejectedAction -> _state.update {
                            it.copy(notice = "Ignored an invalid world action: ${update.review.reason}")
                        }

                        is GenerationUpdate.Finished -> {
                            val finished = update.instance
                            if (finished != null) {
                                _state.update {
                                    it.copy(
                                        story = finished,
                                        streamingText = "",
                                        generationPhase = if (update.stopReason ==
                                            dev.charaly.runtime.inference.StopReason.CANCELLED
                                        ) {
                                            GenerationPhase.STOPPED
                                        } else {
                                            GenerationPhase.IDLE
                                        },
                                    )
                                }
                            } else {
                                _state.update { it.copy(streamingText = "", generationPhase = GenerationPhase.IDLE) }
                            }
                            refreshInstances()
                        }

                        is GenerationUpdate.Failed -> {
                            val message = ErrorMessages.of(update.error)
                            _state.update {
                                it.copy(
                                    generationPhase = GenerationPhase.FAILED,
                                    failureMessage = message,
                                    lastFailedGeneration = message,
                                    streamingText = "",
                                )
                            }
                        }
                    }
                }
            } finally {
                _state.update {
                    if (it.generationPhase.isBusy()) {
                        it.copy(generationPhase = GenerationPhase.IDLE)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** Aborts native generation immediately. */
    fun stopGeneration() {
        generationJob?.cancel()
        runtime.stop()
        _state.update {
            it.copy(
                generationPhase = GenerationPhase.STOPPED,
                streamingText = "",
            )
        }
    }

    /** Throws away the last reply and generates it again. */
    fun regenerate() {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val (trimmed, lastInput) = runCatching { runtime.prepareRegeneration(story) }.getOrDefault(story to null)
            if (lastInput.isNullOrBlank()) {
                _state.update { it.copy(notice = "Nothing to regenerate yet") }
                return@launch
            }
            _state.update { it.copy(story = trimmed) }
            runGeneration(trimmed, lastInput, trimmed.focusCharacterId)
        }
    }

    /** Sends an empty line to let the character continue on their own. */
    fun continueGeneration() {
        val story = _state.value.story ?: return
        runGeneration(story, "", story.focusCharacterId)
    }

    /** Edits the last player line and re-runs from there. */
    fun editLastUserMessage(text: String) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val lastCharacter = story.conversation.lastCharacterEntry()
            val trimmed = if (lastCharacter != null) {
                story.copy(conversation = story.conversation.dropFrom(lastCharacter.turn))
            } else {
                story
            }
            val saved = runCatching { runtime.save(trimmed) }.getOrDefault(trimmed)
            _state.update { it.copy(story = saved) }
            runGeneration(saved, text, saved.focusCharacterId)
        }
    }

    fun selectSpeaker(characterId: CharacterId) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val saved = runCatching { runtime.setFocus(story, characterId) }.getOrDefault(story)
            _state.update { it.copy(story = saved) }
        }
    }

    /** Deterministic time travel. Never touches the model. */
    fun advanceClock(minutes: Long) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val latest = runtime.loadStory(story.id) ?: story
            val advanced = runCatching { runtime.advance(latest, StoryDuration(minutes)) }.getOrNull()
                ?: return@launch
            _state.update {
                it.copy(
                    story = advanced,
                    notice = "Clock advanced to ${advanced.worldClock.now.formatClock()}",
                )
            }
            refreshInstances()
        }
    }

    // ------------------------------------------------------------------
    // Sessions
    // ------------------------------------------------------------------

    fun renameSession(instanceId: String, title: String) {
        viewModelScope.launch {
            val instance = runtime.loadStory(StoryInstanceId(instanceId)) ?: return@launch
            if (title.isBlank()) return@launch
            runCatching { runtime.renameStory(instance, title) }
            refreshInstances()
        }
    }

    fun deleteSession(instanceId: String) {
        viewModelScope.launch {
            runCatching { runtime.deleteStory(StoryInstanceId(instanceId)) }
            _state.update { current ->
                if (current.story?.id?.value == instanceId) {
                    current.copy(story = null, streamingText = "", generationPhase = GenerationPhase.IDLE)
                } else {
                    current
                }
            }
            refreshInstances()
        }
    }

    /** Branches a session into a new, independent story. */
    fun duplicateSession(instanceId: String) {
        viewModelScope.launch {
            val instance = runtime.loadStory(StoryInstanceId(instanceId)) ?: return@launch
            val branch = runCatching {
                runtime.duplicateStory(instance, StoryInstanceId("story-${now()}"), nowEpochMs = now())
            }.getOrNull() ?: return@launch
            _state.update { it.copy(notice = "Branched into ${branch.displayTitle}") }
            refreshInstances()
        }
    }

    fun setLibraryQuery(query: String) = _state.update { it.copy(libraryQuery = query) }

    fun toggleLibraryGenre(genre: String) = _state.update { current ->
        val genres = if (genre in current.libraryGenres) current.libraryGenres - genre
        else current.libraryGenres + genre
        current.copy(libraryGenres = genres)
    }

    fun clearLibraryFilters() = _state.update { it.copy(libraryQuery = "", libraryGenres = emptySet()) }

    fun setLibrarySort(order: dev.charaly.runtime.presentation.PackSortOrder) =
        _state.update { it.copy(librarySort = order) }

    fun setSessionsQuery(query: String) = _state.update { it.copy(sessionsQuery = query) }

    fun setSessionsSort(order: dev.charaly.runtime.presentation.SessionSortOrder) =
        _state.update { it.copy(sessionsSort = order) }

    fun setModelQuery(query: String) = _state.update { it.copy(modelQuery = query) }

    // ------------------------------------------------------------------
    // Models
    // ------------------------------------------------------------------

    /**
     * Imports a GGUF the user picked, registers it, and loads it.
     *
     * Imported and downloaded models end up in exactly the same registry, so the
     * library has one list, not two.
     */
    fun importModel(uri: android.net.Uri) {
        viewModelScope.launch {
            _state.update { it.copy(modelBusy = true, error = null) }
            models.importFrom(uri)
                .onSuccess { entry ->
                    val registered = models.registerImported(entry, registry, catalog)
                    registry.setActive(registered.id)
                    val installed = runCatching { registry.list() }.getOrDefault(_state.value.installedModels)
                    _state.update {
                        it.copy(
                            modelBusy = false,
                            installedModels = installed,
                            activeModelId = registered.id,
                            notice = "Installed ${registered.displayName}",
                        )
                    }
                    loadModel(registered)
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(modelBusy = false, error = error.message ?: "import failed")
                    }
                }
        }
    }

    fun selectModel(model: InstalledModel) {
        viewModelScope.launch {
            registry.setActive(model.id)
            _state.update { it.copy(activeModelId = model.id) }
            loadModel(model)
        }
    }

    fun deleteModel(model: InstalledModel) {
        viewModelScope.launch {
            models.delete(model)
            runCatching { registry.remove(model.id) }
            val installed = runCatching { registry.list() }.getOrDefault(emptyList())
            val active = runCatching { registry.activeId() }.getOrNull()
            _state.update {
                it.copy(
                    installedModels = installed,
                    activeModelId = active.orEmpty(),
                    loadedModelId = if (it.loadedModelId == model.id) null else it.loadedModelId,
                )
            }
            val next = installed.firstOrNull { it.id == active }
            engineStatus.value = if (next == null) {
                EngineStatus.Failed("No model installed yet. Import a GGUF to generate replies.")
            } else {
                loadModel(next)
            }
        }
    }

    /** Re-reads the GGUF header and marks the entry verified. */
    fun verifyModel(model: InstalledModel) {
        viewModelScope.launch {
            _state.update { it.copy(modelBusy = true) }
            models.readMetadata(dev.charaly.app.model.ModelEntry(model.absolutePath, model.displayName, model.sizeBytes))
                .onSuccess { metadata ->
                    models.verifyInRegistry(registry, model.id, metadata.architecture.orEmpty(), metadata.quantization.orEmpty(), metadata.contextLength?.toInt() ?: 0)
                }
                .onFailure { error ->
                    _state.update { it.copy(error = error.message ?: "could not read the model header") }
                }
            _state.update { it.copy(modelBusy = false, installedModels = runCatching { registry.list() }.getOrDefault(_state.value.installedModels)) }
        }
    }

    /**
     * Points the current story at another model or profile.
     *
     * This changes the instance's binding only: no world state is touched, so a
     * story never silently becomes a different story.
     */
    fun rebindStory(model: InstalledModel?, profile: ModelProfile) {
        val story = _state.value.story ?: return
        viewModelScope.launch {
            val updated = if (model != null) {
                runCatching { runtime.rebindModel(story, model, profile) }.getOrNull()
            } else {
                runCatching { runtime.rebindProfile(story, profile) }.getOrNull()
            } ?: return@launch
            if (model != null) {
                loadModel(model)
            }
            _state.update { it.copy(story = updated, notice = "This story now runs ${profile.name}") }
            refreshInstances()
        }
    }

    private fun currentInstalledModel(): InstalledModel? {
        val current = _state.value
        return current.loadedModelId?.let { id -> current.installedModels.firstOrNull { it.id == id } }
            ?: current.installedModels.firstOrNull { it.id == current.activeModelId }
    }

    /**
     * Loads the model a story is bound to.
     *
     * If the story's model is missing (deleted since it was created), the user gets
     * an honest message on the story screen instead of a silent swap.
     */
    private fun bindModelForStory(instance: StoryInstance) {
        val boundId = instance.modelBinding.installedModelId
        if (boundId.isBlank()) return
        viewModelScope.launch {
            val installed = runCatching { registry.list() }.getOrDefault(_state.value.installedModels)
            val model = installed.firstOrNull { it.id == boundId }
            if (model == null) {
                engineStatus.value = EngineStatus.Failed(
                    "The model for this story is no longer on this device.",
                )
                return@launch
            }
            if (engineStatus.value is EngineStatus.Ready && _state.value.loadedModelId == model.id) {
                return@launch
            }
            loadModel(model)
        }
    }

    private suspend fun loadModel(model: InstalledModel): EngineStatus {
        _state.update { it.copy(modelBusy = true) }
        engineStatus.value = EngineStatus.Loading
        val status = when (
            val outcome = runCatching {
                runtime.loadModel(ModelLoadRequest(path = model.absolutePath, displayName = model.displayName))
            }.getOrNull()
        ) {
            is LoadOutcome.Loaded -> EngineStatus.Ready(outcome.info.displayName)
            is LoadOutcome.Failed -> EngineStatus.Failed(ErrorMessages.of(outcome.error))
            null -> EngineStatus.Failed("This model file could not be loaded.")
        }
        _state.update {
            it.copy(
                modelBusy = false,
                loadedModelId = if (status is EngineStatus.Ready) model.id else it.loadedModelId,
            )
        }
        engineStatus.value = status
        return status
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    fun completeOnboarding() {
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        preferences.developerMode = enabled
        _state.update { it.copy(developerMode = enabled) }
    }

    fun setDarkTheme(dark: Boolean) {
        preferences.darkTheme = dark
        _state.update { it.copy(darkTheme = dark) }
    }

    fun setReduceMotion(reduce: Boolean) {
        preferences.reduceMotion = reduce
        _state.update { it.copy(reduceMotion = reduce) }
    }

    fun showNotice(message: String?) = _state.update { it.copy(notice = message) }

    fun showError(message: String?) = _state.update { it.copy(error = message) }

    fun consumeNotice() = _state.update { it.copy(notice = null, error = null) }

    // ------------------------------------------------------------------
    // Developer diagnostics (only reachable with Developer Mode on)
    // ------------------------------------------------------------------

    fun worldStateJson(): String = _state.value.story?.worldState?.describe().orEmpty()

    fun contextPreview(): String {
        val story = _state.value.story ?: return "No story open."
        val context = runCatching { runtime.previewContext(story) }.getOrNull() ?: return "No context."
        return context.systemPrompt()
    }

    fun promptEstimate(): Int {
        val story = _state.value.story ?: return 0
        val context = runCatching { runtime.previewContext(story) }.getOrNull() ?: return 0
        return runtime.promptEstimate(context)
    }

    fun modelDiagnostics(): String = runtime.modelDiagnostics()

    fun eventLog(): String = _state.value.story?.worldState?.eventLog
        ?.joinToString("\n") { it.value }
        .orEmpty()

    /**
     * Story health, for the developer panel.
     *
     * Never shown to a normal player: being told your story has contradictory facts is
     * not something anyone can act on, and it would read as the app being broken.
     */
    fun storyHealth(): String {
        val story = _state.value.story ?: return "No story open."
        val report = runCatching { runtime.storyHealth(story) }.getOrNull() ?: return "Could not analyse."
        return buildString {
            appendLine(report.summarise())
            if (report.findings.isEmpty()) return@buildString
            report.findings.forEach { finding ->
                appendLine()
                appendLine("[${finding.severity}] ${finding.kind}: ${finding.summary}")
                finding.evidence.take(EVIDENCE_LIMIT).forEach { appendLine("  - $it") }
            }
        }
    }

    fun storyHealthStatus(): String {
        val story = _state.value.story ?: return "NO STORY"
        val report = runCatching { runtime.storyHealth(story) }.getOrNull() ?: return "UNKNOWN"
        return report.status.name
    }

    /**
     * "What did the model actually receive?", section by section.
     *
     * Separate from [contextPreview] on purpose: the preview is the assembled prompt,
     * which is what you want when debugging a phrase, while this is the *budgeting* -
     * which sections survived, what they cost, and what was cut. A dropped section is
     * invisible in the assembled prompt and is exactly the thing worth finding.
     */
    /**
 * The causal graph, in plain text.
 *
 * Shown in developer mode only. The point of it is that "why did this happen?" has an
 * answer from the world rather than from the model's narration - so the graph has to be
 * inspectable, not merely present.
 */
fun causalityGraph(): String {
        val story = _state.value.story ?: return "No story open."
        val state = story.worldState
        if (state.causality.isEmpty()) {
            return "No causal links recorded yet. Every event starts as a cause rather than a reason."
        }
        return buildString {
            appendLine("${state.causality.size} links from ${state.eventLog.size} events")
            state.causality.values
                .sortedWith(compareBy({ it.reason.ordinal }, { it.effectId.value }))
                .take(CAUSAL_LIMIT)
                .forEach { link ->
                    appendLine("${link.reason.label}")
                    appendLine("  ${link.causeId.value} -> ${link.effectId.value}${link.detail}")
                }
        }
    }

    /** Threads, with the structure that says whether one is drifting. */
    fun storyThreads(): String {
        val story = _state.value.story ?: return "No story open."
        val threads = story.storyThreads.values.sortedWith(
            compareByDescending<dev.charaly.runtime.domain.StoryThread> { it.priority }.thenBy { it.id.value },
        )
        if (threads.isEmpty()) return "No story threads."
        return threads.joinToString("\n\n") { thread ->
            buildString {
                appendLine("${thread.title}  [${thread.kind.label}, ${thread.status.name.lowercase()}]")
                appendLine("  priority ${thread.priority}  stage ${thread.stage}  progress ${thread.progress}%")
                if (thread.nextBeat.isNotBlank()) appendLine("  next: ${thread.nextBeat}")
                if (thread.resolutionCondition.isNotBlank()) appendLine("  ends when: ${thread.resolutionCondition}")
                if (thread.consequenceIfAbandoned.isNotBlank()) {
                    appendLine("  if dropped: ${thread.consequenceIfAbandoned}")
                }
            }.trimEnd()
        }
    }

    /** What one character has worked out, for the inspector. */
    fun characterMinds(): String {
        val story = _state.value.story ?: return "No story open."
        val withMinds = story.knowledge.charactersWithMinds()
        if (withMinds.isEmpty()) return "Nobody has worked anything out yet."
        return withMinds.joinToString("\n\n") { id ->
            val mind = story.knowledge.mind(id)
            val name = story.characters[id]?.name ?: id.value
            buildString {
                appendLine("$name")
                mind.observations.forEach { appendLine("  saw: ${it.description}") }
                mind.beliefs.forEach { appendLine("  believes (${it.confidence}%): ${it.subject} ${it.claim}") }
                mind.suspicionList().forEach { appendLine("  suspects (${it.strength}%): ${it.subject} ${it.claim}") }
                mind.misconceptions.forEach {
                    appendLine("  WRONG: ${it.subject} ${it.claim} - actually, ${it.truth}")
                }
            }.trimEnd()
        }
    }

    /** Promises, goals and armed consequences. */
    fun commitments(): String {
        val story = _state.value.story ?: return "No story open."
        val ledger = story.worldState.commitments
        if (ledger.promises.isEmpty() && ledger.goals.isEmpty() && ledger.consequences.isEmpty()) {
            return "Nothing has been promised, pursued or set off yet."
        }
        return buildString {
            if (ledger.promises.isNotEmpty()) {
                appendLine("PROMISES")
                ledger.promises.forEach {
                    appendLine("  ${it.keeperId.value} -> ${it.beneficiaryId.value}: \"${it.text}\" [${it.status.name.lowercase()}]")
                }
            }
            if (ledger.goals.isNotEmpty()) {
                appendLine("GOALS")
                ledger.goals.forEach { appendLine("  ${it.ownerId.value}: ${it.describe()}") }
            }
            val pending = ledger.pendingConsequences()
            if (pending.isNotEmpty()) {
                appendLine("ARMED CONSEQUENCES")
                pending.forEach { appendLine("  ${it.describe()}") }
            }
        }.trimEnd()
    }

    fun contextSectionBreakdown(): String {
        val story = _state.value.story ?: return "No story open."
        val sections = runCatching { runtime.contextSections(story) }.getOrNull()
            ?: return "Could not build a context."
        if (sections.isEmpty()) return "No context could be built."
        return buildString {
            val total = sections.sumOf { it.chars }
            appendLine("total: $total chars across ${sections.size} sections")
            sections.forEach { section ->
                val cut = if (section.dropped > 0) "  (-${section.dropped} dropped)" else ""
                appendLine(
                    "[${section.priority.toString().padStart(2)}] " +
                        "${section.title.padEnd(22)} ${section.chars.toString().padStart(5)} chars" +
                        "  ${section.itemCount} items$cut",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------

    private fun definitionFor(instance: StoryInstance): WorldDefinition {
        val pack = _state.value.packs.firstOrNull { it.id == instance.storyPackId }
        return WorldDefinition(pack?.characters.orEmpty(), pack?.locations.orEmpty())
    }

    private fun refreshInstances() {
        viewModelScope.launch {
            val instances = runCatching { runtime.listInstances() }.getOrDefault(_state.value.instances)
            _state.update { it.copy(instances = instances) }
        }
    }

    private suspend fun touchIfNewer(instance: StoryInstance) {
        if (instance.sessionMeta.lastPlayedAtEpochMs <= 0L) {
            runCatching { runtime.touch(instance, instance.sessionMeta.createdAtEpochMs) }
        }
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }

    companion object {
        /** Findings show at most this much evidence, so one finding cannot flood the panel. */
        private const val EVIDENCE_LIMIT = 4

        /** Causal links shown at most. The full graph belongs in a log, not a panel. */
        private const val CAUSAL_LIMIT = 40

        fun factory(application: CharalyApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CharalyViewModel(
                        runtime = application.charaly,
                        models = application.modelManager,
                        registry = application.modelRegistry,
                        catalog = application.modelCatalog,
                        preferences = application.preferences,
                    ) as T
            }
    }
}

private fun GenerationPhase.isBusy(): Boolean =
    this == GenerationPhase.THINKING || this == GenerationPhase.STREAMING
