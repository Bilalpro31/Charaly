package dev.charaly.runtime.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The structural spine of a StoryPack: world framing, authored scenes, authored
 * objectives and packaged audio.
 *
 * Everything in this file is AUTHORING data. It crosses exactly one boundary on its
 * way into a running story:
 *
 * ```
 * SceneTemplate.choice.effects  --> EventEffect --> EventEngine --> WorldEvent --> WorldState
 * PackObjective.onComplete      --> EventEffect --> EventEngine --> WorldEvent --> WorldState
 * ```
 *
 * which is the standing Charaly rule: authored prose never mutates the world, typed
 * effects applied by the engine do. A scene choice is a *branch*, and a branch is only
 * trustworthy if taking it runs the same gauntlet as any other world change.
 */

/**
 * The world a pack is set in: the framing a player reads before anything happens.
 *
 * Distinct from [CanonBible], which is the index of what the setting *asserts*. This is
 * the brochure; canon is the law. `universe` and `era` intentionally overlap with the
 * bible's identically named fields - the bible carries them so a canon panel can answer
 * "which universe is this asserting things about", and this carries them so a pack
 * detail screen can describe the setting without loading canon at all. Callers prefer
 * the [settingUniverse] / [settingEra] helpers over reading either directly.
 */
@Serializable
data class PackWorld(
    val universe: String = "",
    val era: String = "",
    /** Where the action happens, in the author's words. */
    val geography: String = "",
    /** "Magic is real and costs memory." Hard rules of the setting. */
    val rules: List<String> = emptyList(),
    /** Plain statements that are true here but not stated anywhere else. */
    val worldFacts: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = universe.isBlank() && era.isBlank() && geography.isBlank() &&
            rules.isEmpty() && worldFacts.isEmpty()
}

/** Resolves the setting description from the two places it can be declared. */
fun StoryPack.settingUniverse(): String = world.universe.ifBlank { canon.universe }

fun StoryPack.settingEra(): String = world.era.ifBlank { canon.era }

/**
 * An authored scene: a situation with a place, a cast and a way in and out.
 *
 * The runtime counterpart is [Scene], which is *live*: a scene that is happening now,
 * with turn counts and a tempo. A [SceneTemplate] is the recipe that can open one, and
 * it survives in the pack untouched however often it is played.
 */
@Serializable
data class SceneTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val atmosphere: String = "",
    val locationId: LocationId,
    val participantCharacterIds: List<CharacterId> = emptyList(),
    val objective: String = "",
    val threadIds: List<ThreadId> = emptyList(),
    /** When the scene may open. Empty means "always available". */
    val entryConditions: List<EventCondition> = emptyList(),
    /** When the scene should end. Polled by the engine, never guessed. */
    val exitConditions: List<EventCondition> = emptyList(),
    /** The branches offered to the player inside this scene. */
    val choices: List<SceneChoice> = emptyList(),
    val visualAsset: VisualAsset? = null,
    /** "tense", "warm", "absurd" - the default register for replies here. */
    val defaultTone: String = "",
    /** Opens when the story begins with one of these scenarios. Empty = never automatic. */
    val scenarioIds: List<String> = emptyList(),
    /** Whether this scene is reachable only through another scene's choice. */
    val hidden: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "SceneTemplate needs an id" }
        require(name.isNotBlank()) { "SceneTemplate $id needs a name" }
        val dupes = choices.groupBy { it.id }.filterValues { it.size > 1 }
        require(dupes.isEmpty()) { "Duplicate choice ids in scene $id: ${dupes.keys}" }
    }

    fun choice(id: String): SceneChoice? = choices.firstOrNull { it.id == id }

    /** Whether the scene's entry conditions hold for [instance]. */
    fun isAvailable(instance: StoryInstance): Boolean =
        EventConditions.all(entryConditions, instance)

    /** Whether the scene's exit conditions hold for [instance]. */
    fun wantsToClose(instance: StoryInstance): Boolean =
        exitConditions.isNotEmpty() && EventConditions.all(exitConditions, instance)
}

/**
 * One branch inside a scene.
 *
 * Taking a choice is an *authorized world change*: the conditions gate it and the
 * effects run through the event engine. That is what makes "help Marinette" versus
 * "betray Marinette" a deterministic fork instead of a sentence the model may or may
 * not remember.
 */
@Serializable
data class SceneChoice(
    val id: String,
    val label: String,
    val description: String = "",
    /** Offered only when these hold. Empty means "always offered". */
    val conditions: List<EventCondition> = emptyList(),
    /** Applied through the event engine when the player takes this branch. */
    val effects: List<EventEffect> = emptyList(),
    /** Companion thread nudges authored alongside [effects]. */
    val threadChanges: List<ThreadChange> = emptyList(),
    /** May be taken at most once. */
    val once: Boolean = true,
    /** A line the narrator sees after taking this branch. Prose only. */
    val outcomeNote: String = "",
) {
    init {
        require(id.isNotBlank()) { "SceneChoice needs an id" }
        require(label.isNotBlank()) { "SceneChoice $id needs a label" }
    }

    fun isOffered(instance: StoryInstance): Boolean = EventConditions.all(conditions, instance)
}

/**
 * An authored objective: a thing the story is asking the player to do.
 *
 * Unlike a [StoryThread], which is an arc the world tracks, an objective has an owner
 * (the player), an explicit win and an explicit loss. Its runtime shadow is
 * [ObjectiveState]; nothing here is mutated by playing.
 */
@Serializable
data class PackObjective(
    val id: String,
    val title: String,
    val description: String = "",
    /** The thread this objective serves, when it serves one. */
    val threadId: ThreadId? = null,
    /** Related locations/characters, for validation and detail screens. */
    val relatedLocationIds: List<LocationId> = emptyList(),
    val relatedCharacterIds: List<CharacterId> = emptyList(),
    /** Active from the start of these scenarios. Empty = every opening that lists it. */
    val scenarioIds: List<String> = emptyList(),
    /** Whether the objective starts active without being named by a scenario. */
    val autoActivate: Boolean = true,
    val completionConditions: List<EventCondition> = emptyList(),
    val failureConditions: List<EventCondition> = emptyList(),
    /** Applied through the event engine on completion. */
    val onComplete: List<EventEffect> = emptyList(),
    /** Applied through the event engine on failure. */
    val onFailure: List<EventEffect> = emptyList(),
    val optional: Boolean = false,
    /** Hidden from the player until it becomes active. */
    val secret: Boolean = false,
    val priority: Int = 50,
) {
    init {
        require(id.isNotBlank()) { "PackObjective needs an id" }
        require(title.isNotBlank()) { "PackObjective $id needs a title" }
    }

    /** An objective that can never resolve is a bug, and validation says so. */
    val isResolvable: Boolean get() = completionConditions.isNotEmpty()
}

/** The lifecycle of one objective inside one playthrough. */
@Serializable
enum class ObjectiveStatus {
    /** Declared but not yet part of this story. */
    LOCKED,

    /** The player is being asked for this now. */
    ACTIVE,

    /** Won. Terminal. */
    COMPLETED,

    /** Lost. Terminal. */
    FAILED,

    /** Left behind because the story moved on. Terminal. */
    SKIPPED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == SKIPPED
}

/**
 * The runtime shadow of a [PackObjective]: what *this* playthrough is doing about it.
 *
 * Lives in the [StoryInstance], never in the pack, which is the whole reason two
 * playthroughs of one pack can be at completely different points in the same story.
 */
@Serializable
data class ObjectiveState(
    val objectiveId: String,
    val title: String,
    val description: String = "",
    val status: ObjectiveStatus = ObjectiveStatus.ACTIVE,
    val optional: Boolean = false,
    val secret: Boolean = false,
    val priority: Int = 50,
    val activatedAt: StoryTime = StoryTime.START,
    val changedAt: StoryTime = StoryTime.START,
    val outcomeNote: String = "",
) {
    val isActive: Boolean get() = status == ObjectiveStatus.ACTIVE

    fun completed(at: StoryTime, note: String = ""): ObjectiveState =
        copy(status = ObjectiveStatus.COMPLETED, changedAt = at, outcomeNote = note)

    fun failed(at: StoryTime, note: String = ""): ObjectiveState =
        copy(status = ObjectiveStatus.FAILED, changedAt = at, outcomeNote = note)
}

/**
 * Packaged audio: ambience and cues that travel inside the `.charaly-pack`.
 *
 * Playback is the app layer's business. What the runtime owns is the *inventory* -
 * which file, what it is for, and whether its absence is allowed - because that is what
 * installation validation has to decide before a pack is allowed to be installed.
 */
@Serializable
data class PackAudioAsset(
    val id: String,
    /** Path relative to the pack's `assets/audio/` directory. Absolute paths are illegal. */
    val path: String,
    val caption: String = "",
    /** Ambient loops rather than one-shot cues. */
    val loop: Boolean = false,
    /** The scene this cue belongs to, when it belongs to one. */
    val sceneId: String = "",
    val attribution: String = "",
    val license: String = "",
    val source: AssetSource = AssetSource.GENERATED_ORIGINAL,
    /** Missing file blocks installation instead of warning. */
    val required: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "PackAudioAsset needs an id" }
        require(path.isNotBlank()) { "PackAudioAsset $id needs a path" }
    }
}

/**
 * A serialized choice ledger entry: which branch a playthrough already took.
 *
 * Stored as an id rather than the choice itself so that a pack update can never rewrite
 * what a player decided. The decision stands even if the branch is edited later.
 */
@Serializable
data class ChoiceRecord(
    val sceneId: String,
    val choiceId: String,
    val takenAt: StoryTime = StoryTime.START,
    val note: String = "",
)

/** Serial names for the content files that carry these structures. */
object PackStructureSections {
    const val SCENES = "scenes"
    const val OBJECTIVES = "objectives"
    const val AUDIO = "audio"
}

/**
 * Pack-level settings: the knobs a pack is allowed to set and nothing more.
 *
 * A pack may express an *opinion* about which model suits it. It may never bind one -
 * model selection is `dev.charaly.runtime.model`'s business and the runtime stays
 * independent of any pack's ambitions. What travels here is metadata a chooser can read,
 * and the offline guarantee the runtime relies on.
 */
@Serializable
data class PackSettings(
    /** Profile preset id the pack suggests. Resolved by the app, ignored when unknown. */
    val recommendedModelProfileId: String = "",
    /** A model id the pack suggests, purely as a hint. */
    val recommendedModelId: String = "",
    /** Free-form capability floor, e.g. "3 GB RAM", "long context". Honest, not enforced. */
    val minimumCapability: String = "",
    val notes: String = "",
    /**
     * Whether playing this story needs the network.
     *
     * Always false for a Charaly pack: story runtime is local by contract. The flag exists
     * so the claim is checkable rather than assumed - a pack that sets it true is a pack
     * that violates the offline rule, and validation says so.
     */
    val requiresNetwork: Boolean = false,
    /** Whether several stories from this pack may run side by side. */
    val allowMultipleInstances: Boolean = true,
)

/**
 * A scene choice as offered right now, after conditions.
 *
 * Projected to the UI so the player sees only what the world actually permits - the
 * unavailable branch is not greyed out, it is absent, because its existence is a spoiler.
 */
@Serializable
data class OfferedChoice(
    val sceneId: String,
    val choiceId: String,
    val label: String,
    val description: String = "",
    val once: Boolean = true,
    val alreadyTaken: Boolean = false,
)