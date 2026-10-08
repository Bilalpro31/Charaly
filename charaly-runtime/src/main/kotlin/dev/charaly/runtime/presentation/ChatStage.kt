package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.EventEngine

/**
 * THE STAGE.
 *
 * ## What this is
 *
 * A chat screen that behaves like a scene. The engine already decides where you are, who
 * is there, what the world has done and what a character remembers. This type is the
 * projection that makes a reader *feel* those facts, and its whole design problem is
 * subtraction.
 *
 * ## The three things it refuses to do
 *
 * 1. **Show engine internals.** No ids, no event class names, no affinity numbers, no
 *    context budgets. Every string below is either authored by a pack or written by a
 *    presenter in story language.
 * 2. **Look like a messenger.** A character's reply is prose: a name, a spoken line, an
 *    action line. No bubbles on the character's side, no tails, no avatars in a column.
 * 3. **Lose the keyboard.** The composer is part of this snapshot's *contract* - it knows
 *    whether it is enabled and why - because a chat screen that cannot say "you need a
 *    local model" just looks broken.
 *
 * ## Why it is not a ViewModel field
 *
 * It is a pure function of a [StoryInstance] plus generation state. The alternative -
 * assembling lines in a composable - is how a screen ends up disagreeing with the engine
 * about who is speaking.
 */
data class ChatStage(
    val storyId: String,
    val title: String,

    /**
     * The pack's id, carried so the artwork resolver can pick an asset directory.
     *
     * On the stage rather than reached through the projection because it is *about* the
     * projection's output rather than part of it: the projection decides which picture, and
     * this says which pack's folder to look in. Keeping them apart means a projection stays
     * a pure function of world state, with no knowledge of asset layouts at all.
     */
    val packId: String = "",

    // ---- header: who, where, when ---------------------------------------
    /** "Adrien" - the person you are talking to. Never blank once a scene exists. */
    val companionName: String,
    val companionAccent: Long,
    val companionArtwork: PackArtwork,
    /** "Paris · Evening" - in story time, from the world clock. */
    val contextLine: String,
    /**
     * "18:42" - the world clock, read from authoritative state.
     *
     * A separate field rather than folded into [contextLine] because the clock is the
     * one header fact that *changes* while a story is being read: every completed turn
     * moves it. A header that shows only "Evening" cannot demonstrate that the world is
     * running, which is the entire claim this screen makes.
     */
    val clockLabel: String = "",
    /** "Day 3" - also from the clock, because a night passing is the change people notice. */
    val dayLabel: String = "",
    /** "3 people here", or empty when you are alone. */
    val presenceLabel: String,
    val theme: ResolvedTheme,
    val artwork: PackArtwork,

    // ---- the room ---------------------------------------------------------
    val people: List<Presence>,

    // ---- the transcript ---------------------------------------------------
    val beats: List<Beat>,
    /** Sparse, cinematic world moments interleaved with the dialogue. */
    val moments: List<StoryMoment>,

    // ---- generation -------------------------------------------------------
    val phase: GenerationPhase,
    /** "Adrien is thinking…" - a person, never "loading". */
    val phaseLabel: String,
    val isStreaming: Boolean,

    // ---- failure ----------------------------------------------------------
    val failure: StageFailure?,

    // ---- composer ---------------------------------------------------------
    val composer: Composer,

    /**
     * What this scene looks like: backdrop, atmosphere, per-character portraits.
     *
     * Derived once, from [VisualSceneProjection], so the backdrop cannot disagree with the
     * location in the header, and a portrait cannot disagree with the name above it. The
     * stage renders what the projection chose; it does not choose.
     */
    val visuals: VisualSceneProjection = VisualSceneProjection(
        background = VisualResolver.sceneBackdrop(locationId = "", seed = "scene"),
        atmosphereSeed = "scene",
    ),

    /**
     * Which model this scene speaks with, and whether it is ready.
     *
     * A first-class field rather than something the screen re-derives, because the device
     * bug this fixes was a screen re-deriving it and getting a different answer from the
     * one the composer was using.
     */
    val model: ModelLine,

    /** Shown instead of a transcript when the story has not begun. */
    val opening: EmptyState?,
) {
    val isGenerating: Boolean get() = phase.isBusy()

    val hasFailure: Boolean get() = failure != null

    /** Whether the transcript has anything to show. */
    val hasTranscript: Boolean get() = beats.isNotEmpty()

    /** Whether a turn can be requested. Mirrors the composer, from the same value. */
    val canGenerate: Boolean get() = composer.isEnabled && !phase.isBusy()
}

/**
 * The model row on the stage: name, state, and - when it is blocked - the real reason.
 *
 * [reason] is empty when nothing is wrong. It is never a raw exception, and it is never
 * the generic "connect a local model" when the model is in fact installed and merely
 * loading.
 */
data class ModelLine(
    val modelId: String = "",
    val displayName: String = "",
    val stateLabel: String = "",
    val reason: String = "",
    val isReady: Boolean = false,
    /** True when the model will be loaded on first use rather than already being resident. */
    val loadsOnFirstUse: Boolean = false,
) {
    val isBound: Boolean get() = modelId.isNotBlank()
}

/**
 * One person in the room.
 *
 * A portrait, a name, and a *presence* - which is a fact about now, not a database row.
 * "Here", "Nearby", "Elsewhere". No affinity, no trust score, no mood number.
 */
data class Presence(
    val id: String,
    val name: String,
    val accent: Long,
    val artwork: PackArtwork,
    /** HERE / NEARBY / ELSEWHERE. Drives the dot's *shape* as well as its colour. */
    val whereabouts: Whereabouts,
    /** One relationship sentence, when there is something true to say. */
    val relationshipLabel: String = "",
) {
    val isHere: Boolean get() = whereabouts == Whereabouts.HERE
}

/**
 * Where somebody is, as three states rather than a boolean.
 *
 * The third state is the point. "Is here / is not here" cannot express the difference
 * between someone standing in the room and someone two streets away who still has a claim
 * on this conversation - and the difference is the entire reason a People sheet is worth
 * opening.
 */
enum class Whereabouts(val label: String) {
    /** In the room with you. */
    HERE("Here"),

    /** In the same place, or on the edge of it. */
    NEARBY("Nearby"),

    /** Somewhere else in the world, and relevant to this story anyway. */
    ELSEWHERE("Elsewhere"),
}

/**
 * One rendered beat of the transcript.
 *
 * A beat is a *turn*, not a token: one character's reply arrives as a beat with a spoken
 * line and any surrounding stage direction, and the three are typeset differently. A model
 * reply that contains three sentences and an action therefore reads as prose rather than
 * as three log lines.
 */
data class Beat(
    val id: String,
    /** PLAYER / CHARACTER / NARRATION / SYSTEM. Presentation only. */
    val role: BeatRole,
    /** Who is speaking. "You" for the player. */
    val speakerName: String,
    /**
     * The speaker's runtime identity, empty for the player and for narration.
     *
     * The portrait is resolved from this and never from [speakerName]. Matching art by name
     * is how "Adrien" and "Adrien Agreste" end up as two different people, and it breaks
     * outright the moment a pack renames somebody or a user imports a card whose name
     * collides with a pack character.
     */
    val speakerCharacterId: String = "",
    /** A stable, deterministic seed for this beat's portrait. */
    val speakerPortraitSeed: String = "",
    val speakerAccent: Long,
    val speakerArtwork: PackArtwork,
    /** The spoken words, without quotation marks. Empty for pure narration. */
    val dialogue: String,
    /** Prose around the dialogue. Empty when there is none. */
    val narration: String,
    /** A bracketed physical action, in italics. Empty when there is none. */
    val action: String,
    /** "18:42" - story time, quiet, optional. */
    val timeLabel: String,
    val isStreaming: Boolean = false,
) {
    /** Whether this beat has anything to typeset at all. */
    val isEmpty: Boolean get() = dialogue.isBlank() && narration.isBlank() && action.isBlank()
}

enum class BeatRole {
    /** The player. */
    PLAYER,

    /** A character. */
    CHARACTER,

    /** The world's prose. */
    NARRATION,

    /** A deterministic note from the runtime. */
    SYSTEM,
}

/**
 * A failure, as a person would say it.
 *
 * [detail] is the technical explanation - an HTTP code, an engine message - and is
 * collapsed by default. The brief asks for exactly this shape: a human sentence above,
 * expandable diagnostics below, and actions that include a way out.
 */
data class StageFailure(
    val message: String,
    /** "HTTP 403", "no native library". Empty when there is nothing technical to say. */
    val detail: String = "",
    /** "Retry", "Open Models". Never empty - a failure with no action is a dead end. */
    val actions: List<StageAction>,
)

enum class StageAction(val label: String) {
    RETRY("Retry"),
    OPEN_MODELS("Choose a model"),
    DISMISS("Dismiss"),
}

/**
 * The composer's state.
 *
 * Part of the projection because "why can I not type" is a question the stage has to be
 * able to answer. A composer that is simply greyed out reads as a broken app; one that
 * says "this story needs a local model" reads as a product.
 */
data class Composer(
    val isEnabled: Boolean,
    val hint: String,
    /** The modes offered, in order. SAY first, because it is what people use. */
    val modes: List<ComposerMode> = listOf(ComposerMode.SAY),
    /** The player's most recent line, so it can be edited and re-run. */
    val lastPlayerLine: String = "",
) {
    /** Why the composer is disabled, or empty when it is not. */
    fun blockedReason(): String = if (isEnabled) "" else hint
}

object ChatStagePresenter {

    /**
     * Builds the stage.
     *
     * Pure. Nothing here touches the world, and nothing a screen does while rendering can
     * change what this returns for the same inputs.
     */
    fun build(
        instance: StoryInstance,
        pack: StoryPack?,
        definition: WorldDefinition,
        phase: GenerationPhase = GenerationPhase.IDLE,
        streamingText: String = "",
        failureMessage: String = "",
        /**
         * The authoritative model answer.
         *
         * Takes precedence over [modelReady] when supplied, and is what the app always
         * passes. See [dev.charaly.runtime.model.ModelSelection] for why "is a model
         * connected" cannot be a boolean: a model that is registered, active and bound but
         * still loading is *usable*, and gating the composer on engine residency was what
         * produced "Connect a local model" on a device that already had a working GGUF.
         */
        model: dev.charaly.runtime.model.ModelSelection? = null,
        modelReady: Boolean = true,
    ): ChatStage {
        val selection = model
            ?: dev.charaly.runtime.model.ModelSelection.ofReadyFlag(modelReady)
        val theme = pack?.let { ResolvedTheme.of(it.identity.theme) } ?: ResolvedTheme.BRAND
        val focusId = instance.focusCharacterId
        val companion = definition.character(focusId)
        val location = instance.currentLocation()
        val playerId = StoryContextPresenter.playerId(instance)

        val beats = buildList {
            instance.conversation.entries.forEach { entry ->
                addAll(beatsFor(entry, instance, definition))
            }
            if (streamingText.isNotBlank()) {
                add(streamingBeat(instance, definition, streamingText))
            }
        }

        val failure = failureOf(phase, failureMessage, selection)
        val modelLine = ModelStagePresenter.modelLine(selection)
        // Built here rather than in a composable, so the backdrop, the header's location and
        // the portraits all come from one read of authoritative state.
        val visuals = VisualSceneProjection.project(instance, definition, pack)

        return ChatStage(
            storyId = instance.id.value,
            packId = pack?.id?.value.orEmpty(),
            title = instance.displayTitle,
            companionName = companion?.name.orEmpty().ifBlank { "The scene" },
            companionAccent = companion?.accentLong()?.takeIf { it != 0L }
                ?: ResolvedTheme.BRAND.primary,
            companionArtwork = companion?.artwork ?: PackArtwork.generated(instance.id.value),
            contextLine = listOfNotNull(
                location?.name?.takeIf { it.isNotBlank() },
                StoryContextPresenter.timeOfDayLabel(instance.worldClock.now.hour)
                    .takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            clockLabel = instance.worldClock.now.formatClock(),
            dayLabel = "${instance.worldClock.now.day}. Gün",
            presenceLabel = presenceLabel(instance, definition, playerId),
            theme = theme,
            artwork = PackArtwork.generated("banner-${instance.storyPackId.value}"),
            people = presences(instance, definition, playerId),
            beats = beats.filterNot { it.isEmpty },
            moments = StoryMomentPresenter.moments(instance),
            phase = phase,
            phaseLabel = phaseLabel(phase, companion?.name),
            isStreaming = phase == GenerationPhase.STREAMING,
            failure = failure,
            composer = composer(instance, phase, selection),
            visuals = visuals,
            model = modelLine,
            opening = if (instance.conversation.entries.isEmpty()) {
                opening(instance, definition, location?.name.orEmpty(), companion?.name)
            } else {
                null
            },
        )
    }

    // -------------------------------------------------------------------- beats

    /**
     * One transcript entry, as beats.
     *
     * A character's reply is *one* beat carrying its dialogue, narration and action
     * together, so the three read as a single paragraph of prose. Splitting them into
     * separate rows - which the previous renderer did - is what made the screen look like
     * a log of tokens rather than like somebody speaking.
     */
    private fun beatsFor(
        entry: dev.charaly.runtime.domain.TranscriptEntry,
        instance: StoryInstance,
        definition: WorldDefinition,
    ): List<Beat> {
        val time = entry.at.clockLabel()

        return when (entry.role) {
            dev.charaly.runtime.domain.TranscriptRole.USER -> listOf(
                Beat(
                    id = entry.id,
                    role = BeatRole.PLAYER,
                    speakerName = instance.persona.userDisplayName.ifBlank { "You" },
                    speakerAccent = ResolvedTheme.BRAND.ink,
                    speakerArtwork = PackArtwork.generated("player"),
                    dialogue = "",
                    narration = entry.text,
                    action = "",
                    timeLabel = time,
                ),
            )

            dev.charaly.runtime.domain.TranscriptRole.NARRATION,
            dev.charaly.runtime.domain.TranscriptRole.SYSTEM,
            -> listOf(
                Beat(
                    id = entry.id,
                    role = if (entry.role == dev.charaly.runtime.domain.TranscriptRole.SYSTEM) {
                        BeatRole.SYSTEM
                    } else {
                        BeatRole.NARRATION
                    },
                    speakerName = if (entry.role == dev.charaly.runtime.domain.TranscriptRole.SYSTEM) {
                        "The world"
                    } else {
                        "Narration"
                    },
                    speakerAccent = ResolvedTheme.BRAND.secondary,
                    speakerArtwork = PackArtwork.generated("narration"),
                    dialogue = "",
                    narration = entry.text,
                    action = "",
                    timeLabel = time,
                ),
            )

            dev.charaly.runtime.domain.TranscriptRole.CHARACTER -> {
                val speaker = definition.character(entry.speakerId)
                val accent = speaker?.accentLong()?.takeIf { it != 0L } ?: ResolvedTheme.BRAND.primary
                val segments = NarrativeSegments.parse(entry.text)
                listOf(
                    Beat(
                        id = entry.id,
                        role = BeatRole.CHARACTER,
                        speakerName = speaker?.name ?: entry.speakerId?.value ?: "Someone",
                        speakerCharacterId = entry.speakerId?.value.orEmpty(),
                        speakerPortraitSeed = entry.speakerId?.value.orEmpty(),
                        speakerAccent = accent,
                        speakerArtwork = speaker?.artwork ?: PackArtwork.generated(entry.id),
                        dialogue = segments.filter { it.kind == SegmentKind.DIALOGUE }
                            .joinToString(" ") { it.text },
                        narration = segments.filter { it.kind == SegmentKind.NARRATION }
                            .joinToString(" ") { it.text },
                        action = segments.filter { it.kind == SegmentKind.ACTION }
                            .joinToString(" ") { it.text },
                        timeLabel = time,
                    ),
                )
            }
        }
    }

    private fun streamingBeat(
        instance: StoryInstance,
        definition: WorldDefinition,
        text: String,
    ): Beat {
        val speaker = definition.character(instance.focusCharacterId)
        val segments = NarrativeSegments.parse(text)
        return Beat(
            id = "streaming",
            role = BeatRole.CHARACTER,
            speakerName = speaker?.name ?: "Someone",
            speakerCharacterId = instance.focusCharacterId?.value.orEmpty(),
            speakerPortraitSeed = instance.focusCharacterId?.value.orEmpty(),
            speakerAccent = speaker?.accentLong()?.takeIf { it != 0L } ?: ResolvedTheme.BRAND.primary,
            speakerArtwork = speaker?.artwork ?: PackArtwork.generated("streaming"),
            dialogue = segments.filter { it.kind == SegmentKind.DIALOGUE }
                .joinToString(" ") { it.text },
            narration = segments.filter { it.kind == SegmentKind.NARRATION }
                .joinToString(" ") { it.text },
            action = segments.filter { it.kind == SegmentKind.ACTION }
                .joinToString(" ") { it.text },
            timeLabel = instance.worldClock.now.clockLabel(),
            isStreaming = true,
        )
    }

    // ------------------------------------------------------------------ presence

    private fun presenceLabel(
        instance: StoryInstance,
        definition: WorldDefinition,
        playerId: CharacterId?,
    ): String {
        val others = hereCount(instance, definition, playerId)
        return if (others == 0) "" else StoryContextPresenter.presenceLabel(others)
    }

    /** How many people are actually in the room, excluding the player. */
    private fun hereCount(
        instance: StoryInstance,
        definition: WorldDefinition,
        playerId: CharacterId?,
    ): Int = EventEngine.currentPlayerLocation(instance)
        ?.let { instance.worldState.charactersAt(it) }
        .orEmpty()
        .count { runtime -> runtime.characterId != playerId && definition.character(runtime.characterId) != null }

    /**
     * Who is in the room, in three states.
     *
     * HERE is the current scene's participants. NEARBY is anyone else in the same
     * location. ELSEWHERE is anyone holding an unresolved claim on this story - a promise
     * made, a secret known - read from the live ledger rather than from the pack, so it
     * changes as the story does and is never a catalogue of the cast.
     */
    private fun presences(
        instance: StoryInstance,
        definition: WorldDefinition,
        playerId: CharacterId?,
    ): List<Presence> {
        val sceneIds = instance.currentScene()?.participants.orEmpty()
        val locationId = EventEngine.currentPlayerLocation(instance)
        val atLocation = locationId
            ?.let { instance.worldState.charactersAt(it) }
            .orEmpty()
            .map { it.characterId }
            .filter { it != playerId }

        val elsewhere = ledgerClaims(instance, playerId).filter { it !in sceneIds && it !in atLocation }

        return (sceneIds.map { it to Whereabouts.HERE } +
            atLocation.filter { it !in sceneIds }.map { it to Whereabouts.NEARBY } +
            elsewhere.map { it to Whereabouts.ELSEWHERE })
            .distinctBy { it.first }
            .map { (id, where) -> presence(instance, definition, id, where) }
    }

    private fun people(
        instance: StoryInstance,
        definition: WorldDefinition,
        playerId: CharacterId?,
    ): List<CharacterId> {
        val scene = instance.currentScene()?.participants.orEmpty()
        val atLocation = EventEngine.currentPlayerLocation(instance)
            ?.let { instance.worldState.charactersAt(it) }
            .orEmpty()
            .map { it.characterId }
        return (scene + atLocation).filter { it != playerId }.distinct()
    }

    /**
     * Characters the story still owes something to.
     *
     * Read from the commitment ledger, so it is a property of *this* story rather than of
     * the pack - which is the whole difference between a People sheet and a character
     * database.
     */
    private fun ledgerClaims(instance: StoryInstance, playerId: CharacterId?): List<CharacterId> {
        val ledger = instance.worldState.commitments
        val ids = mutableSetOf<CharacterId>()
        ledger.promises.filter { it.status.isOpen }.forEach { promise ->
            if (playerId == null || promise.keeperId == playerId || promise.beneficiaryId == playerId) {
                ids += promise.keeperId
                ids += promise.beneficiaryId
            }
        }
        instance.storyThreads.values
            .filter { it.status.isOpen }
            .forEach { thread -> ids += thread.involvedCharacterIds }
        return ids.filter { it != playerId }.toList()
    }

    private fun presence(
        instance: StoryInstance,
        definition: WorldDefinition,
        id: CharacterId,
        where: Whereabouts,
    ): Presence {
        val character = definition.character(id)
        return Presence(
            id = id.value,
            name = character?.name ?: instance.characters[id]?.name ?: id.value,
            accent = character?.accentLong()?.takeIf { it != 0L } ?: ResolvedTheme.BRAND.primary,
            artwork = character?.artwork ?: PackArtwork.generated(id.value),
            whereabouts = where,
            relationshipLabel = StoryContextPresenter.standingLabel(instance, id),
        )
    }

    // ------------------------------------------------------------------ moments

    // ----------------------------------------------------------------- composer

    private fun composer(
        instance: StoryInstance,
        phase: GenerationPhase,
        model: dev.charaly.runtime.model.ModelSelection,
    ) = Composer(
        // Busy *or* genuinely modelless means not editable. A composer that accepts a line
        // while the model is already writing would queue text nobody can see.
        //
        // Note what is NOT checked here: whether the weights are currently in RAM. A model
        // that is installed, bound and merely still loading is perfectly editable - the
        // runtime loads it on the first turn, and the stage says so in [ModelLine]. Gating
        // on residency is what made an imported GGUF look like no model at all.
        isEnabled = model.canGenerate && !phase.isBusy(),
        hint = when {
            !model.canGenerate -> Loc.t("chat.needs_model")
            model.needsEngineLoad -> Loc.t("chat.loading_model")
            instance.conversation.entries.isEmpty() -> Loc.t("chat.composer_open_hint")
            else -> Loc.t("chat.composer_say_hint")
        },
        modes = listOf(ComposerMode.SAY, ComposerMode.DO, ComposerMode.THINK),
        lastPlayerLine = instance.conversation.lastUserEntry()?.text.orEmpty(),
    )

    // ------------------------------------------------------------------ failure

    /**
     * A failure, in a sentence, with somewhere to go.
     *
     * The technical detail is separated from the headline on purpose. "HTTP 403" is what
     * the user saw in a bug report; "That model could not be fetched." is what happened.
     */
    /**
     * A failure, in a sentence, with somewhere to go.
     *
     * ## Why the headline is rewritten even when the message is already a sentence
     *
     * The runtime maps its own error types to sentences, but a message that reaches here
     * from an unexpected source - a native engine, a third-party transport - is raw text,
     * and raw text can be `InferenceError: context window exceeded`. Printing that as the
     * headline puts a class name in front of a player, which is the thing the whole
     * presenter exists to prevent.
     *
     * So the rule is: **the technical half is detected and demoted**, never promoted. If
     * the message looks like a code, it becomes the collapsed detail and the headline is
     * written here. Otherwise the message is trusted, because by then it is a sentence
     * somebody chose.
     */
    private fun failureOf(
        phase: GenerationPhase,
        message: String,
        model: dev.charaly.runtime.model.ModelSelection,
    ): StageFailure? {
        if (phase != GenerationPhase.FAILED) return null

        val raw = message.trim()
        val lower = raw.lowercase()
        val networkish = lower.contains("http") || lower.contains("403") ||
            lower.contains("404") || lower.contains("could not be reached")
        // A class name, an exception suffix, or a fully-qualified type. All three mean the
        // message is the engine talking, not the app.
        val leaksInternals = looksLikeEngineText(raw)

        val headline = when {
            raw.isBlank() -> Loc.t("error.unexpected")
            networkish -> friendlyNetwork(raw)
            leaksInternals -> genericFor(lower, model)
            // The engine told us the model itself is the problem, and it knows a more
            // specific reason than this presenter does. Prefer its answer over the generic
            // sentence, because "your file is missing" and "no model at all" are different
            // problems and the user can only act on one of them.
            model.loadFailure.isNotBlank() -> model.loadFailure
            model.blocked == dev.charaly.runtime.model.ModelBlockReason.FILE_MISSING ->
                Loc.t("model.file_missing")
            else -> raw
        }

        val detail = when {
            raw.isBlank() -> ""
            leaksInternals -> raw
            networkish -> raw
            // A sentence that merely mentions a model is a sentence; repeating it beneath
            // itself reads as the app not knowing what it said.
            else -> ""
        }

        return StageFailure(
            message = headline,
            detail = detail,
            actions = when {
                !model.canGenerate -> listOf(StageAction.OPEN_MODELS, StageAction.DISMISS)
                lower.contains("model") -> listOf(StageAction.RETRY, StageAction.OPEN_MODELS)
                else -> listOf(StageAction.RETRY, StageAction.DISMISS)
            },
        )
    }

    /**
     * Whether a message is the engine speaking rather than the app.
     *
     * Deliberately a shape test rather than a blocklist of known types: the point is to
     * catch anything *new* that reaches the UI with a class name in it, which a blocklist
     * would miss the first time.
     */
    private fun looksLikeEngineText(raw: String): Boolean {
        if (raw.contains("Exception") || raw.contains("Throwable")) return true
        if (raw.contains("Error:") || raw.contains("error:")) return true
        // A dotted type reference, e.g. `dev.charaly.runtime.inference.InferenceError`.
        if (Regex("""\b[a-z][a-zA-Z0-9_]*(\.[a-z][a-zA-Z0-9_]*){2,}""").containsMatchIn(raw)) return true
        // A CamelCase identifier that is not a normal word - `InferenceError`, `NullPointer`.
        return Regex("""\b[A-Z][a-z]+[A-Z][A-Za-z0-9]*\b""").containsMatchIn(raw)
    }

    /**
     * A headline for a message that leaked internals.
     *
     * Names the most likely cause so the sentence is still *specific* - "the model stopped
     * unexpectedly" tells a user whether to look at the model or at the network - without
     * reproducing anything the engine said.
     */
    private fun genericFor(lower: String, model: dev.charaly.runtime.model.ModelSelection): String = when {
        lower.contains("context") -> Loc.t("error.context_exhausted")
        lower.contains("memory") || lower.contains("alloc") -> Loc.t("error.needs_more_memory")
        lower.contains("token") -> Loc.t("error.too_long")
        lower.contains("model") -> Loc.t("error.model_could_not_finish")
        lower.contains("load") && !model.canGenerate -> Loc.t("model.load_failed")
        else -> Loc.t("error.unexpected")
    }

    private fun friendlyNetwork(raw: String): String = when {
        raw.contains("403") -> Loc.t("error.model_not_fetched")
        raw.contains("404") -> Loc.t("error.model_not_in_library")
        else -> Loc.t("error.library_unreachable")
    }

    private fun phaseLabel(phase: GenerationPhase, speaker: String?): String = when (phase) {
        GenerationPhase.IDLE -> ""
        GenerationPhase.THINKING -> if (speaker != null) "$speaker düşünüyor…" else "Düşünüyor…"
        GenerationPhase.STREAMING -> if (speaker != null) "$speaker konuşuyor…" else "Konuşuyor…"
        GenerationPhase.STOPPED -> Loc.t("chat.stopped")
        GenerationPhase.FAILED -> Loc.t("chat.failed")
    }

    private fun opening(
        instance: StoryInstance,
        definition: WorldDefinition,
        locationName: String,
        companionName: String?,
    ) = EmptyState(
        title = if (locationName.isNotBlank()) {
            Loc.t("chat.opening_title", locationName)
        } else {
            Loc.t("chat.opening_title_generic")
        },
        body = companionName?.let { Loc.t("chat.opening_body", it) }
            ?: Loc.t("chat.opening_body_generic"),
        artSeed = "charaly-stage-${instance.id.value}",
    )
}