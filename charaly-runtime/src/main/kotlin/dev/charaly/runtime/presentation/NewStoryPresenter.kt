package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelBindingResolver
import dev.charaly.runtime.model.ModelProfile
import dev.charaly.runtime.model.ModelSelection
import dev.charaly.runtime.model.ModelSelectionResolver
import kotlinx.serialization.Serializable

/**
 * The New Story flow, as data.
 *
 * Four steps, one draft. The wizard owns *choices*; the runtime still owns what
 * those choices mean. Nothing here mutates a world.
 *
 * Serializable because the draft outlives the process: the wizard route is restored from a
 * saved back stack, and without a persisted draft that restore landed the user on a screen
 * with no Continue control and nothing to continue.
 */
@Serializable
data class NewStoryDraft(
    val packId: String,
    val scenarioId: String = "",
    val personaId: String = "",
    val userName: String = "",
    val focusCharacterId: String = "",
    val castCharacterIds: Set<String> = emptySet(),
    val storyTitle: String = "",
    val profileId: String = "",
    val modelId: String = "",
    /**
     * Characters the user imported, to be placed in this story.
     *
     * Kept apart from [castCharacterIds] on purpose. The pack's cast is what the *pack* says
     * happens; this is what the *user* asked to bring. Merging them into one set would make
     * it impossible to tell the two apart, and the isolation tests work by resolving ids
     * against the pack - so a single mixed set would quietly weaken them.
     */
    val importedCharacterIds: Set<String> = emptySet(),
) {
    val isComplete: Boolean
        get() = scenarioId.isNotBlank() && personaId.isNotBlank()
}

enum class NewStoryStep(val titleKey: String, val subtitleKey: String) {
    SCENARIO("setup.step.scenario.title", "setup.step.scenario.subtitle"),
    PERSONA("setup.step.persona.title", "setup.step.persona.subtitle"),
    CAST("setup.step.cast.title", "setup.step.cast.subtitle"),
    REVIEW("setup.step.review.title", "setup.step.review.subtitle"),
    ;

    /**
     * Localised rather than stored, so the wizard follows the language the user picked
     * without the enum having to be rebuilt.
     */
    val title: String get() = Loc.t(titleKey)

    val subtitle: String get() = Loc.t(subtitleKey)

    val stepNumber: Int get() = ordinal + 1

    val next: NewStoryStep?
        get() = entries.getOrNull(ordinal + 1)

    val previous: NewStoryStep?
        get() = entries.getOrNull(ordinal - 1)
}

data class NewStorySnapshot(
    val step: NewStoryStep,
    val packTitle: String,
    val packTagline: String,
    val theme: ResolvedTheme,
    /** The draft in its current state, so a step can mutate it precisely. */
    val draft: NewStoryDraft,
    val scenarios: List<ScenarioCard>,
    val personas: List<PersonaCard>,
    val castable: List<CharacterCard>,
    /**
     * The user's imported characters, offered separately from the pack's own cast.
     *
     * A distinct list rather than more entries in [castable]: these come from the user's
     * files, not from the pack author, and a user looking at the cast step needs to know
     * which is which. Empty when nothing has been imported, and the screen then shows no
     * section at all rather than an empty heading.
     */
    val importable: List<ImportedCharacterCard> = emptyList(),
    val importedInUseLabel: String = "",
    val review: NewStoryReview,
    /**
     * Whether the forward action is usable on this step.
     *
     * Derived from the model's *usability*, never from whether its weights happen to be in
     * RAM: a user who has just imported a GGUF must be able to press Continue. The model is
     * loaded lazily on the first turn, and [model] says so.
     */
    val canAdvance: Boolean,
    val selectedScenarioTitle: String,
    val selectedPersonaTitle: String,
    val selectedCastNames: List<String>,
    val modelLabel: String,
    /**
     * Whether a usable model is bound to this story.
     *
     * True for a model that is registered, active and merely still loading - which is the
     * state immediately after an import, and was previously reported as "no model".
     */
    val modelReady: Boolean,
    /** The model row: name, state, and the real reason when it is blocked. */
    val model: ModelLine = ModelLine(),
)

data class NewStoryReview(
    val worldTitle: String,
    val storyTitle: String,
    val roleName: String,
    val roleTagline: String,
    val castNames: List<String>,
    /** Names of the imported characters this story will contain, kept apart from the pack's. */
    val importedNames: List<String> = emptyList(),
    val locationName: String,
    /** The exact story time this story opens at, read from the pack's scenario. */
    val timeLabel: String,
    val modelName: String,
    val profileName: String,
    val worldIsolated: Boolean,
    /** True when every cast id and every imported id resolves to something this story owns. */
    val castResolves: Boolean = true,
)

/**
 * Builds the New Story wizard.
 *
 * The interesting rule here is isolation: a draft only ever offers characters from
 * its own pack, and only cast that exists in that pack. There is no code path that
 * could put a Neon District character into a Miraculous story.
 */
object NewStoryPresenter {

    /**
     * Builds the wizard.
     *
     * [imported] is the user's character library, offered as an extra cast section. It is
     * a separate parameter rather than something read from a global, because the presenter
     * must stay a pure function of its inputs - a wizard that reached for a repository would
     * stop being testable without a device.
     */
    fun build(
        draft: NewStoryDraft,
        pack: StoryPack,
        installedModels: List<InstalledModel>,
        activeModelId: String,
        step: NewStoryStep = stepFor(draft),
        availableProfiles: List<ModelProfile> = dev.charaly.runtime.model.ModelProfileLibrary.all,
        imported: List<dev.charaly.runtime.persistence.ImportedCharacter> = emptyList(),
        inUseBy: Map<CharacterId, Int> = emptyMap(),
        /**
         * The authoritative model answer.
         *
         * Supplied by the app, which owns the registry and the engine. When it is null the
         * presenter derives one from [installedModels] and [activeModelId] using exactly the
         * resolver the runtime uses, so a test and the app cannot reach different answers.
         */
        model: ModelSelection? = null,
    ): NewStorySnapshot {
        val scenarios = pack.scenarios.map { PackDetailPresenter.scenarioCard(it, pack) }
        val personas = pack.personas.map { PackDetailPresenter.personaCard(it, pack) }
        val castable = pack.characters.map { character ->
            val location = pack.location(character.startingLocationId ?: pack.startLocationOf(character.id) ?: dev.charaly.runtime.domain.LocationId(""))
            CharacterCard(
                id = character.id.value,
                name = character.name,
                tagline = character.summaryLine(),
                description = character.description,
                locationName = location?.name.orEmpty(),
                accent = character.accentLong().takeIf { it != 0L } ?: ResolvedTheme.of(pack.identity.theme).primary,
                artwork = character.artwork,
                avatarUri = character.avatarUri,
                role = character.identityRole,
            )
        }

        val scenario = pack.scenario(draft.scenarioId)
        val persona = pack.persona(draft.personaId)
        val chosenModel = installedModels.firstOrNull { it.id == draft.modelId }
            ?: installedModels.firstOrNull { it.id == activeModelId }
        // One resolver, used by the wizard and by the runtime that creates the story. The
        // two used to look the model up independently, which is how the review step could
        // show one model and the story could bind another.
        val selection = model ?: ModelSelectionResolver.resolveForNewStory(
            packDefaultProfileId = pack.defaultModelProfileId,
            requestedModelId = draft.modelId,
            installed = installedModels,
            activeModelId = activeModelId,
            nowEpochMs = 0L,
        )
        val modelLine = ModelStagePresenter.modelLine(selection)
        val profile = availableProfiles.firstOrNull { it.id == draft.profileId }
            ?: dev.charaly.runtime.model.ModelProfileLibrary.resolve(pack.defaultModelProfileId)

        // Defaults: a scenario implies a cast, and a persona implies a focus.
        val suggestedCast = (scenario?.castCharacterIds.orEmpty().map { it.value } +
            persona?.suggestedCharacterIds.orEmpty().map { it.value } +
            listOfNotNull(scenario?.focusCharacterId?.value)).toSet()

        val effectiveCast = draft.castCharacterIds.ifEmpty { suggestedCast }
        val effectiveDraft = draft.copy(castCharacterIds = effectiveCast)

        // Imported characters are matched by the draft's ids against the library, so an id
        // the library no longer has simply drops out rather than breaking the wizard.
        val library = CharacterImportPresenter.library(imported, inUseBy)
        val chosenImported = library.cards.filter { it.id in draft.importedCharacterIds }

        val review = NewStoryReview(
            worldTitle = pack.title,
            storyTitle = draft.storyTitle.ifBlank { defaultStoryTitle(pack, scenario) },
            roleName = persona?.name ?: "No role chosen",
            roleTagline = persona?.tagline.orEmpty(),
            castNames = pack.characters
                .filter { it.id.value in effectiveCast }
                .map { it.name },
            importedNames = chosenImported.map { it.name },
            locationName = pack.location(scenario?.startLocationId ?: dev.charaly.runtime.domain.LocationId(""))?.name
                ?: scenario?.startLocationId?.value.orEmpty(),
            // Read from the pack's own start state, so the Review step shows the hour the
            // story will actually open at rather than a formatted "now".
            timeLabel = (scenario?.startTime ?: pack.initialWorldState.startTime).storyLabel(),
            modelName = chosenModel?.displayName ?: Loc.t("setup.no_model_bound"),
            profileName = profile.name,
            // The cast can only ever be a subset of this pack's own characters.
            worldIsolated = effectiveCast.all { id -> pack.characterIds.any { it.value == id } },
            // Every id the draft names has to resolve, or the story would open with a
            // participant the engine cannot place. Imported ids are checked against the
            // library rather than against the pack, because they are not in the pack yet -
            // `startStoryWithImported` merges them into a copy at creation time.
            castResolves = effectiveCast.all { id -> pack.characterIds.any { it.value == id } } &&
                draft.importedCharacterIds.all { id -> library.cards.any { it.id == id } },
        )

        return NewStorySnapshot(
            step = step,
            packTitle = pack.title,
            packTagline = pack.identity.tagline,
            theme = ResolvedTheme.of(pack.identity.theme),
            draft = effectiveDraft,
            scenarios = scenarios,
            personas = personas,
            castable = castable,
            importable = library.cards,
            importedInUseLabel = library.usageLabel,
            review = review,
            // A missing model blocks the last step and nothing else: there is no point
            // refusing to let somebody choose an opening before they have chosen a GGUF,
            // and there is no point letting them press "Step in" for a model that cannot
            // load. This is the Continue/Start contract, in one place.
            canAdvance = canAdvance(step, draft, pack) && selection.canGenerate,
            selectedScenarioTitle = scenario?.title.orEmpty(),
            selectedPersonaTitle = persona?.name.orEmpty(),
            selectedCastNames = review.castNames,
            modelLabel = review.modelName,
            modelReady = selection.canGenerate,
            model = modelLine,
        )
    }

    /** The first step the draft is ready for. */
    fun stepFor(draft: NewStoryDraft): NewStoryStep = when {
        draft.scenarioId.isBlank() -> NewStoryStep.SCENARIO
        draft.personaId.isBlank() -> NewStoryStep.PERSONA
        draft.focusCharacterId.isBlank() -> NewStoryStep.CAST
        else -> NewStoryStep.REVIEW
    }

    fun canAdvance(step: NewStoryStep, draft: NewStoryDraft, pack: StoryPack): Boolean = when (step) {
        NewStoryStep.SCENARIO -> draft.scenarioId.isNotBlank()
        NewStoryStep.PERSONA -> draft.personaId.isNotBlank()
        NewStoryStep.CAST -> {
            val focus = draft.focusCharacterId
            focus.isNotBlank() && pack.character(CharacterId(focus)) != null
        }
        NewStoryStep.REVIEW -> draft.isComplete
    }

    /** Seeding a draft with the pack's own defaults, so the wizard is never empty. */
    fun initialDraft(pack: StoryPack): NewStoryDraft {
        val scenario = pack.scenarios.firstOrNull()
        val persona = pack.personas.firstOrNull()
        return NewStoryDraft(
            packId = pack.id.value,
            scenarioId = scenario?.id.orEmpty(),
            personaId = persona?.id.orEmpty(),
            userName = "",
            focusCharacterId = (scenario?.focusCharacterId?.value
                ?: persona?.suggestedCharacterIds?.firstOrNull()?.value
                ?: pack.characters.firstOrNull()?.id?.value).orEmpty(),
            castCharacterIds = (scenario?.castCharacterIds.orEmpty().map { it.value } +
                listOfNotNull(scenario?.focusCharacterId?.value)).toSet(),
            storyTitle = defaultStoryTitle(pack, scenario),
            profileId = pack.defaultModelProfileId,
        )
    }

    fun defaultStoryTitle(pack: StoryPack, scenario: StartingScenario?): String =
        scenario?.title?.let { "${pack.title} · $it" } ?: pack.title

    /**
     * Resolves the model binding a story should be created with.
     *
     * The same precedence the runtime uses, exposed here so the Review step can
     * show the user exactly what they are about to commit to.
     */
    fun resolveBinding(
        pack: StoryPack,
        draft: NewStoryDraft,
        installedModels: List<InstalledModel>,
        activeModelId: String,
        nowEpochMs: Long,
    ): ModelBinding = ModelBindingResolver.resolve(
        packDefaultProfileId = pack.defaultModelProfileId,
        // Exactly the precedence `build` uses for the Review step, so the model the user
        // was shown and the model the story is bound to are the same file.
        installedModel = installedModels.firstOrNull { it.id == draft.modelId }
            ?: installedModels.firstOrNull { it.id == activeModelId },
        userProfileId = draft.profileId.takeIf { it.isNotBlank() },
        nowEpochMs = nowEpochMs,
    )

    /** Personas for a scenario that suggests particular characters. */
    fun personasFor(pack: StoryPack, scenario: StartingScenario?): List<PersonaTemplate> =
        pack.personas.sortedByDescending { persona ->
            scenario?.castCharacterIds.orEmpty().count { it in persona.suggestedCharacterIds }
        }
}