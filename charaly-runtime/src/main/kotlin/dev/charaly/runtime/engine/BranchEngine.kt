package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.ChoiceRecord
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventConditions
import dev.charaly.runtime.domain.OfferedChoice
import dev.charaly.runtime.domain.ObjectiveState
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.SceneId
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.events.EventPayload

/**
 * Branching: condition -> branch -> event -> state change -> new availability.
 *
 * ## Why choices are compiled like events
 *
 * Because a branch is only a branch if taking it changes the world *deterministically*.
 * The obvious alternative - letting the model narrate that the player betrayed someone -
 * has exactly one property that matters and lacks it: it is not repeatable. Here, a choice
 * is [SceneChoice], its gating conditions are evaluated against authoritative state, and
 * its effects compile to the same typed payloads every other world change uses. Same pack,
 * same state, same input: same result. Every time.
 *
 * ## What is offered
 *
 * A choice appears only when its conditions hold. The branch you cannot take is *absent*,
 * not disabled: greying it out advertises a future the player has not earned and, for a
 * hidden branch, spoils its existence.
 */
object BranchEngine {

    /**
     * The scene templates the story could plausibly be inside right now: those whose
     * location matches where the scene is, whose entry conditions hold.
     */
    fun candidateScenes(instance: StoryInstance, pack: StoryPack): List<SceneTemplate> {
        val locationId = instance.currentScene()?.locationId ?: return emptyList()
        return pack.scenes.filter { scene ->
            scene.locationId == locationId && scene.isAvailable(instance)
        }
    }

    /** The branches on offer right now, in a stable order. */
    fun offered(instance: StoryInstance, pack: StoryPack): List<OfferedChoice> {
        val offered = mutableListOf<OfferedChoice>()

        // Opening branches, until the first one has been taken.
        if (instance.choiceLedger.none { it.sceneId == OPENING_SCENE_ID }) {
            val scenario = instance.scenarioId.let { pack.scenario(it) }
            scenario?.openingChoices?.forEach { choice ->
                if (choice.isOffered(instance)) {
                    offered += choice.toOffered(OPENING_SCENE_ID, instance)
                }
            }
        }

        candidateScenes(instance, pack).forEach { scene ->
            scene.choices.forEach { choice ->
                if (choice.isOffered(instance)) offered += choice.toOffered(scene.id, instance)
            }
        }
        return offered
    }

    fun sceneFor(instance: StoryInstance, pack: StoryPack, sceneId: String): SceneTemplate? =
        pack.scenes.firstOrNull { it.id == sceneId }

    fun choiceFor(instance: StoryInstance, pack: StoryPack, sceneId: String, choiceId: String): SceneChoice? =
        if (sceneId == OPENING_SCENE_ID) {
            instance.scenarioId.let { pack.scenario(it) }?.openingChoices?.firstOrNull { it.id == choiceId }
        } else {
            sceneFor(instance, pack, sceneId)?.choice(choiceId)
        }

    /**
     * Compiles one branch into typed payloads.
     *
     * Returns an empty list for a branch that was refused - a refused branch changes
     * nothing at all, which is the whole point of refusing it.
     */
    fun compile(
        instance: StoryInstance,
        sceneId: String,
        choice: SceneChoice,
        at: StoryTime,
    ): List<EventPayload> {
        if (!choice.isOffered(instance)) return emptyList()
        if (choice.once && instance.tookChoice(sceneId, choice.id)) return emptyList()
        val context = EffectContext(
            instance = instance,
            at = at,
            eventKey = "choice:$sceneId/${choice.id}",
            sceneId = SceneId("scene-choice-${choice.id}-${instance.idCounter}"),
            memoryIdSeed = "mem-choice-${choice.id}-${instance.idCounter}",
        )
        return choice.effects.mapNotNull { it.toPayload(context) } +
            choice.threadChanges.mapNotNull { it.toPayload() }
    }

    /** The ledger entry for a taken branch. */
    fun record(instance: StoryInstance, sceneId: String, choice: SceneChoice, at: StoryTime): ChoiceRecord =
        ChoiceRecord(
            sceneId = sceneId,
            choiceId = choice.id,
            takenAt = at,
            note = choice.outcomeNote,
        )

    private fun SceneChoice.toOffered(sceneId: String, instance: StoryInstance): OfferedChoice = OfferedChoice(
        sceneId = sceneId,
        choiceId = id,
        label = label,
        description = description,
        once = once,
        alreadyTaken = instance.tookChoice(sceneId, id),
    )

    /** The synthetic scene id opening branches live under. */
    const val OPENING_SCENE_ID = ""
}

/**
 * Objectives: what the story is asking of the player, and when it stops asking.
 *
 * Resolution is deterministic and engine-mediated, exactly like branching: conditions are
 * evaluated against authoritative state, and the reward or consequence is a list of typed
 * effects applied through the event engine - never a field written by a poller.
 */
object ObjectiveEngine {

    /**
     * The objectives an opening puts on the board.
     *
     * Membership is curated by the scenario: named objectives are in, `autoActivate`
     * objectives that did not narrow themselves to another opening are in, and everything
     * else is simply not part of this story - excluded rather than shown as locked, because
     * a greyed-out quest log is a spoiler list.
     */
    fun seed(pack: StoryPack, scenario: StartingScenario?, at: StoryTime): List<ObjectiveState> =
        pack.objectives.filter { objective -> belongsTo(objective, scenario) }
            .sortedByDescending { it.priority }
            .map { objective ->
                ObjectiveState(
                    objectiveId = objective.id,
                    title = objective.title,
                    description = objective.description,
                    status = ObjectiveStatus.ACTIVE,
                    optional = objective.optional,
                    secret = objective.secret,
                    priority = objective.priority,
                    activatedAt = at,
                    changedAt = at,
                )
            }

    private fun belongsTo(objective: PackObjective, scenario: StartingScenario?): Boolean = when {
        scenario == null -> objective.autoActivate
        objective.id in scenario.objectiveIds -> true
        scenario.id in objective.scenarioIds -> true
        else -> objective.autoActivate && objective.scenarioIds.isEmpty()
    }

    /** What one evaluation pass found. */
    data class Evaluation(
        val objectives: List<ObjectiveState>,
        val payloads: List<EventPayload>,
        val changed: Boolean,
    )

    /**
     * Resolves objectives against the current world.
     *
     * Failure is checked before completion on purpose: an objective whose win and loss
     * conditions hold at the same time is a badly authored objective, and the pessimistic
     * reading is the one that does not hand out rewards for disasters.
     */
    fun evaluate(instance: StoryInstance, pack: StoryPack): Evaluation {
        val payloads = mutableListOf<EventPayload>()
        var changed = false
        val resolved = instance.objectives.map { state ->
            val definition = pack.objective(state.objectiveId) ?: return@map state
            if (!state.isActive) return@map state
            val at = instance.worldClock.now
            when {
                definition.failureConditions.isNotEmpty() &&
                    EventConditions.all(definition.failureConditions, instance) -> {
                    changed = true
                    payloads += compile(definition.onFailure, instance, at, "objective-failed:${definition.id}")
                    state.failed(at, definition.title)
                }
                definition.completionConditions.isNotEmpty() &&
                    EventConditions.all(definition.completionConditions, instance) -> {
                    changed = true
                    payloads += compile(definition.onComplete, instance, at, "objective-completed:${definition.id}")
                    state.completed(at, definition.title)
                }
                else -> state
            }
        }
        return Evaluation(resolved, payloads, changed)
    }

    private fun compile(
        effects: List<dev.charaly.runtime.domain.EventEffect>,
        instance: StoryInstance,
        at: StoryTime,
        eventKey: String,
    ): List<EventPayload> {
        if (effects.isEmpty()) return emptyList()
        val context = EffectContext(
            instance = instance,
            at = at,
            eventKey = eventKey,
            sceneId = SceneId("scene-$eventKey-${instance.idCounter}"),
            memoryIdSeed = "mem-$eventKey-${instance.idCounter}",
        )
        return effects.mapNotNull { it.toPayload(context) }
    }

    /** Human-readable condition line for the story panel. */
    fun describeCompletion(objective: PackObjective): String =
        EventConditions.describe(objective.completionConditions)
}