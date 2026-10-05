package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelBindingResolver
import dev.charaly.runtime.model.ModelProfile

/**
 * The New Story flow, as data.
 *
 * Four steps, one draft. The wizard owns *choices*; the runtime still owns what
 * those choices mean. Nothing here mutates a world.
 */
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
) {
    val isComplete: Boolean
        get() = scenarioId.isNotBlank() && personaId.isNotBlank()
}

enum class NewStoryStep(val title: String, val subtitle: String) {
    SCENARIO("Choose an opening", "Every world has more than one way in"),
    PERSONA("Choose your role", "Who you are inside this world"),
    CAST("Choose your cast", "Who is already in the room"),
    REVIEW("Review", "One last look before you step in"),
    ;

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
    val review: NewStoryReview,
    val canAdvance: Boolean,
    val selectedScenarioTitle: String,
    val selectedPersonaTitle: String,
    val selectedCastNames: List<String>,
    val modelLabel: String,
    val modelReady: Boolean,
)

data class NewStoryReview(
    val worldTitle: String,
    val storyTitle: String,
    val roleName: String,
    val roleTagline: String,
    val castNames: List<String>,
    val locationName: String,
    val timeLabel: String,
    val modelName: String,
    val profileName: String,
    val worldIsolated: Boolean,
)

/**
 * Builds the New Story wizard.
 *
 * The interesting rule here is isolation: a draft only ever offers characters from
 * its own pack, and only cast that exists in that pack. There is no code path that
 * could put a Neon District character into a Miraculous story.
 */
object NewStoryPresenter {

    fun build(
        draft: NewStoryDraft,
        pack: StoryPack,
        installedModels: List<InstalledModel>,
        activeModelId: String,
        step: NewStoryStep = stepFor(draft),
        availableProfiles: List<ModelProfile> = dev.charaly.runtime.model.ModelProfileLibrary.all,
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
        val model = installedModels.firstOrNull { it.id == draft.modelId }
            ?: installedModels.firstOrNull { it.id == activeModelId }
        val profile = availableProfiles.firstOrNull { it.id == draft.profileId }
            ?: dev.charaly.runtime.model.ModelProfileLibrary.resolve(pack.defaultModelProfileId)

        // Defaults: a scenario implies a cast, and a persona implies a focus.
        val suggestedCast = (scenario?.castCharacterIds.orEmpty().map { it.value } +
            persona?.suggestedCharacterIds.orEmpty().map { it.value } +
            listOfNotNull(scenario?.focusCharacterId?.value)).toSet()

        val effectiveCast = draft.castCharacterIds.ifEmpty { suggestedCast }
        val effectiveDraft = draft.copy(castCharacterIds = effectiveCast)

        val review = NewStoryReview(
            worldTitle = pack.title,
            storyTitle = draft.storyTitle.ifBlank { defaultStoryTitle(pack, scenario) },
            roleName = persona?.name ?: "No role chosen",
            roleTagline = persona?.tagline.orEmpty(),
            castNames = pack.characters
                .filter { it.id.value in effectiveCast }
                .map { it.name },
            locationName = pack.location(scenario?.startLocationId ?: dev.charaly.runtime.domain.LocationId(""))?.name
                ?: scenario?.startLocationId?.value.orEmpty(),
            timeLabel = (scenario?.startTime ?: pack.initialWorldState.startTime).storyLabel(),
            modelName = model?.displayName ?: "No model bound",
            profileName = profile.name,
            // The cast can only ever be a subset of this pack's own characters.
            worldIsolated = effectiveCast.all { id -> pack.characterIds.any { it.value == id } },
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
            review = review,
            canAdvance = canAdvance(step, draft, pack),
            selectedScenarioTitle = scenario?.title.orEmpty(),
            selectedPersonaTitle = persona?.name.orEmpty(),
            selectedCastNames = review.castNames,
            modelLabel = review.modelName,
            modelReady = model != null,
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