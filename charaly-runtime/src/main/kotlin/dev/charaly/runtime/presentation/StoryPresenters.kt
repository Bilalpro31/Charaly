package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.RelationshipKey
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.model.ModelBinding

/** How one transcript line should be drawn. */
enum class LineKind {
    /** Something the player said or did. */
    PLAYER,

    /** Spoken by a character. */
    DIALOGUE,

    /** Prose around the dialogue. */
    NARRATION,

    /** A bracketed physical action. */
    ACTION,

    /** A world change the engine actually applied. */
    WORLD_EVENT,

    /** A deterministic note from the runtime. */
    SYSTEM,
}

/**
 * One rendered line of the story.
 *
 * A character reply is split into several lines (dialogue, narration, action) so
 * the screen reads like prose rather than a log. All of it is presentation: none
 * of it is world state.
 */
data class StoryLine(
    val id: String,
    val kind: LineKind,
    val speakerId: String,
    val speakerName: String,
    val speakerAccent: Long,
    val speakerArtwork: dev.charaly.runtime.domain.PackArtwork,
    val segments: List<NarrativeSegment>,
    val rawText: String,
    val timeLabel: String,
    val isStreaming: Boolean = false,
    val eventSummary: String = "",
)

/**
 * Who is in the room right now.
 *
 * [activityLabel] is what they are doing, and [relationshipLabel] is what they think of
 * you - the two things a player actually wants from a roster. Neither is an internal value:
 * no ids, no affinity numbers, no `RelationshipAxis` names.
 */
data class ParticipantChip(
    val id: String,
    val name: String,
    val accent: Long,
    val isFocus: Boolean,
    val isPlayer: Boolean,
    val activityLabel: String,
    /**
     * How this character currently feels about the player, in a sentence.
     *
     * Empty when there is nothing to say, which is the common case for a character met ten
     * seconds ago. A blank is honest; "Neutral" for everyone would not be.
     */
    val relationshipLabel: String = "",
    /**
     * Whether this character is in the room, or merely relevant to it.
     *
     * Drives the PEOPLE sheet's split into "Here now" and "Elsewhere": a character you
     * have met but who has walked out of the scene is still worth listing, and listing
     * them without the distinction would imply they are standing there.
     */
    val isPresent: Boolean = true,
)

enum class GenerationPhase {
    IDLE,
    THINKING,
    STREAMING,
    STOPPED,
    FAILED,
}

/** The composer's mode buttons. They shape *input*, never world state. */
enum class ComposerMode(val label: String, val prefix: String, val hint: String) {
    SAY("Söyle", "", "Ne diyorsun?"),
    DO("Yap", "*", "Ne yapıyorsun?"),
    THINK("Düşün", "", "Nedir düşündüğün?"),
    OBSERVE("Gözle", "", "Ne fark ediyorsun?"),
    INTERACT("Dene", "", "Ne deniyorsun?"),
    ;

    /**
     * Turns a mode plus typed text into the line that is actually sent.
     *
     * Purely cosmetic shaping of the player's own input: it runs before the runtime
     * sees anything and it has no ability to change world state.
     */
    fun compose(input: String): String {
        val trimmed = input.trim()
        return when (this) {
            SAY, THINK, OBSERVE, INTERACT -> trimmed
            DO -> if (trimmed.startsWith("*")) trimmed else "*$trimmed*"
        }
    }
}

data class StorySnapshot(
    val storyId: String,
    val title: String,
    val packTitle: String,
    val packId: String,
    val theme: ResolvedTheme,
    val sceneLine: String,
    val locationName: String,
    val timeLabel: String,

    // ---- contextual header fields ---------------------------------------
    //
    // Read off authoritative state rather than assembled in the view, so the header
    // cannot claim a location or a cast the engine disagrees with. All three default to
    // empty so every existing constructor keeps working.

    /** Who the player is talking to, by name. The header's primary label. */
    val companionName: String = "",
    /** The story's own title, shown one step below the companion. */
    val storyTitle: String = "",
    /**
     * "Paris · Evening" - place and time of day, in story time.
     *
     * Assembled once here rather than in the composable so the same string is testable
     * and so a screen cannot format it two different ways.
     */
    val contextLine: String = "",
    /** "3 people here", or empty when the player is alone. */
    val presenceLabel: String = "",
    val chapterLabel: String,
    val chapterIndex: Int,
    val participants: List<ParticipantChip>,
    val lines: List<StoryLine>,
    val phase: GenerationPhase,
    val phaseLabel: String,
    val failureMessage: String,
    val failureActions: List<String>,
    val composerEnabled: Boolean,
    val composerHint: String,
    val modelName: String,
    val modelReady: Boolean,
    val modelDetail: String,
    val openThreadCount: Int,
    val memoryCount: Int,
    val personaName: String,
    /** The player's most recent line, so it can be edited and re-run. */
    val lastUserText: String,
    val emptyState: EmptyState?,
) {
    val isGenerating: Boolean get() = phase == GenerationPhase.THINKING || phase == GenerationPhase.STREAMING
}

/**
 * Builds the immersive story screen.
 *
 * This is the only place that turns a transcript into something a reader can look
 * at. It has no ability to change the world: it is a pure function of
 * (StoryInstance, WorldDefinition, generation state).
 */
object StoryPresenter {

    fun build(
        instance: StoryInstance,
        pack: StoryPack?,
        definition: WorldDefinition,
        phase: GenerationPhase = GenerationPhase.IDLE,
        streamingText: String = "",
        failureMessage: String = "",
        modelReady: Boolean = true,
    ): StorySnapshot {
        val theme = pack?.let { ResolvedTheme.of(it.identity.theme) } ?: ResolvedTheme.BRAND
        val scene = instance.currentScene()
        val location = instance.currentLocation()
        val focusId = instance.focusCharacterId

        val lines = buildList {
            instance.conversation.entries.forEach { entry ->
                addAll(linesFor(entry, instance, definition))
            }
            if (streamingText.isNotBlank()) {
                add(
                    streamingLine(instance, definition, streamingText),
                )
            }
        }

        return StorySnapshot(
            storyId = instance.id.value,
            title = instance.displayTitle,
            packTitle = instance.packTitle,
            packId = instance.storyPackId.value,
            theme = theme,
            sceneLine = sceneLine(instance, scene, location?.name.orEmpty()),
            locationName = location?.name.orEmpty(),
            timeLabel = instance.worldClock.now.clockLabel(),
            // The contextual header line: who you are with, where, and at what hour.
            // All three come from authoritative state - the focus character, the
            // player's location variable and the world clock - so the header cannot
            // describe a scene the engine has moved on from.
            companionName = definition.character(focusId)?.name.orEmpty(),
            storyTitle = instance.displayTitle,
            contextLine = contextLine(
                locationName = location?.name.orEmpty(),
                hour = instance.worldClock.now.hour,
            ),
            presenceLabel = presenceLabel(instance, definition),
            chapterLabel = instance.currentChapter()?.title ?: instance.displayTitle,
            chapterIndex = instance.currentChapter()?.index ?: 1,
            participants = participants(instance, definition),
            lines = lines,
            phase = phase,
            phaseLabel = phaseLabel(phase, instance, definition),
            failureMessage = failureMessage,
            failureActions = failureActions(phase, failureMessage, modelReady),
            composerEnabled = modelReady && !phase.isBusy(),
            composerHint = composerHint(instance, modelReady),
            modelName = instance.modelBinding.modelDisplayName.ifBlank { "No model bound" },
            modelReady = modelReady,
            modelDetail = instance.modelBinding.describe(),
            openThreadCount = instance.worldState.openThreads().size,
            memoryCount = instance.memories.size,
            personaName = instance.persona.name,
            lastUserText = instance.conversation.lastUserEntry()?.text.orEmpty(),
            emptyState = if (instance.conversation.entries.isEmpty()) {
                EmptyState(
                    title = sceneOpeningTitle(scene, definition),
                    body = sceneOpeningBody(instance, definition),
                    actionLabel = "",
                    artSeed = instance.id.value,
                )
            } else {
                null
            },
        )
    }

    /** "Paris • Rooftop • 18:42", with parts omitted when the world has no such part. */
    /**
     * "Paris · Evening" - the header's contextual line.
     *
     * Assembled here rather than in the composable so the same two facts are formatted
     * identically wherever they appear, and so a test can assert it.
     *
     * Both parts are optional and joined only when both exist: a location with no name
     * yields "Evening", not " · Evening".
     */
    fun contextLine(locationName: String, hour: Int): String {
        val time = StoryContextPresenter.timeOfDayLabel(hour)
        return when {
            locationName.isNotBlank() && time.isNotBlank() -> "$locationName · $time"
            locationName.isNotBlank() -> locationName
            else -> time
        }
    }

    /**
     * "3 people here", or empty when the player is alone.
     *
     * Empty rather than "Just you" in the *header*, because the header already has two
     * pieces of information and "· Just you" adds a third that says nothing. The full
     * phrasing is used in the World sheet, where presence is the question being asked.
     */
    fun presenceLabel(instance: StoryInstance, definition: WorldDefinition): String {
        val playerId = StoryContextPresenter.playerId(instance)
        val locationId = dev.charaly.runtime.engine.EventEngine.currentPlayerLocation(instance) ?: return ""
        val others = instance.worldState.charactersAt(locationId)
            .map { it.characterId }
            .filter { it != playerId }
            .filter { definition.character(it) != null }
        return when (others.size) {
            0 -> ""
            1 -> "1 person here"
            else -> "${others.size} people here"
        }
    }

    fun sceneLine(instance: StoryInstance, scene: Scene?, locationName: String): String {
        val region = packRegionOf(instance)
        return listOfNotNull(
            region.takeIf { it.isNotBlank() },
            locationName.takeIf { it.isNotBlank() },
            instance.worldClock.now.clockLabel(),
        ).joinToString("  ·  ")
    }

    private fun packRegionOf(instance: StoryInstance): String = when (instance.storyPackId.value) {
        "pack-miraculous-shadows-of-paris" -> "Paris"
        else -> ""
    }

    private fun linesFor(
        entry: TranscriptEntry,
        instance: StoryInstance,
        definition: WorldDefinition,
    ): List<StoryLine> {
        val speaker = definition.character(entry.speakerId)
        val accent = speaker?.accentLong()?.takeIf { it != 0L } ?: ResolvedTheme.BRAND.primary
        val artwork = speaker?.artwork
            ?: dev.charaly.runtime.domain.PackArtwork.generated(entry.id)
        val timeLabel = entry.at.clockLabel()

        return when (entry.role) {
            TranscriptRole.USER -> listOf(
                StoryLine(
                    id = entry.id,
                    kind = LineKind.PLAYER,
                    speakerId = "player",
                    speakerName = instance.persona.userDisplayName.ifBlank { "You" },
                    speakerAccent = ResolvedTheme.BRAND.accent,
                    speakerArtwork = dev.charaly.runtime.domain.PackArtwork.generated("player"),
                    segments = NarrativeSegments.parse(entry.text),
                    rawText = entry.text,
                    timeLabel = timeLabel,
                ),
            )

            TranscriptRole.NARRATION, TranscriptRole.SYSTEM -> listOf(
                StoryLine(
                    id = entry.id,
                    kind = if (entry.role == TranscriptRole.SYSTEM) LineKind.SYSTEM else LineKind.NARRATION,
                    speakerId = "narrator",
                    speakerName = if (entry.role == TranscriptRole.SYSTEM) "Dünya" else "Anlatım",
                    speakerAccent = ResolvedTheme.BRAND.ink,
                    speakerArtwork = dev.charaly.runtime.domain.PackArtwork.generated("narrator"),
                    segments = NarrativeSegments.parse(entry.text),
                    rawText = entry.text,
                    timeLabel = timeLabel,
                ),
            )

            TranscriptRole.CHARACTER -> NarrativeSegments.parse(entry.text).mapIndexed { index, segment ->
                StoryLine(
                    id = "${entry.id}-$index",
                    kind = when (segment.kind) {
                        SegmentKind.DIALOGUE -> LineKind.DIALOGUE
                        SegmentKind.ACTION -> LineKind.ACTION
                        SegmentKind.NARRATION -> LineKind.NARRATION
                    },
                    speakerId = entry.speakerId?.value.orEmpty(),
                    speakerName = speaker?.name ?: entry.speakerId?.value ?: "Someone",
                    speakerAccent = accent,
                    speakerArtwork = artwork,
                    segments = listOf(segment),
                    rawText = segment.text,
                    timeLabel = timeLabel,
                )
            }
        }
    }

    private fun streamingLine(
        instance: StoryInstance,
        definition: WorldDefinition,
        text: String,
    ): StoryLine {
        val speaker = definition.character(instance.focusCharacterId)
        return StoryLine(
            id = "streaming",
            kind = LineKind.DIALOGUE,
            speakerId = instance.focusCharacterId?.value.orEmpty(),
            speakerName = speaker?.name ?: "Someone",
            speakerAccent = speaker?.accentLong()?.takeIf { it != 0L } ?: ResolvedTheme.BRAND.primary,
            speakerArtwork = speaker?.artwork
                ?: dev.charaly.runtime.domain.PackArtwork.generated("streaming"),
            segments = NarrativeSegments.parse(text).takeIf { it.isNotEmpty() }
                ?: listOf(NarrativeSegment(SegmentKind.DIALOGUE, text)),
            rawText = text,
            timeLabel = instance.worldClock.now.clockLabel(),
            isStreaming = true,
        )
    }

    /**
     * World events that are queued to happen, as timeline chips.
     *
     * These are *future* events, so they belong in the world sheet ("Coming up"),
     * never in the transcript: a story that opened by listing its own scheduled
     * events would read like a debug log, not like a scene.
     */
    fun recentWorldEvents(instance: StoryInstance, limit: Int = 5): List<WorldEventChip> =
        instance.eventQueue.snapshot()
            .sortedBy { it.scheduledAt.totalMinutes }
            .takeLast(limit)
            .map {
                WorldEventChip(
                    summary = it.describe(),
                    timeLabel = it.scheduledAt.clockLabel(),
                    isPending = true,
                )
            }
            .reversed()

    /**
     * The room, split into who is here and who is merely relevant.
     *
     * ## Why the split, rather than one flat list
     *
     * A roster of everyone the pack contains is a catalogue, and a player who can see
     * twenty-three characters before meeting any of them is being shown the design document
     * instead of the world. What they want is much narrower: who am I standing with, and who
     * else has a claim on this moment.
     *
     * So the list is: the current scene's participants, plus anyone holding an unresolved
     * thread - a promise made, a secret known, something owed. That second group is derived
     * from live world state, so it changes as the story does, and it is exactly the set of
     * people the engine would surface on its own if the UI were not in the way.
     */
    fun participants(instance: StoryInstance, definition: WorldDefinition): List<ParticipantChip> {
        val scene = instance.currentScene()
        val focus = instance.focusCharacterId
        val presentIds: List<CharacterId> = if (scene != null && scene.participants.isNotEmpty()) {
            (if (focus != null) listOf(focus) else emptyList()) +
                scene.participants.filterNot { it == focus }
        } else {
            // No scene: whoever the world knows about, in a stable order, so the sheet does
            // not reshuffle itself on every recomposition.
            (if (focus != null) listOf(focus) else emptyList()) +
                instance.worldState.characters.keys.filterNot { it == focus }.sortedBy { it.value }
        }
        val present = presentIds.toSet()

        // Relevant-but-absent: anyone the player still owes something to, or who still
        // owes them. Read from the live ledger rather than from the pack, so this is a
        // property of *this* story's state and not of what the pack happens to contain -
        // which is the difference between a story surface and a character database.
        val ledger = instance.worldState.commitments
        // Promises are keyed by real CharacterId, so the player's own involvement can only
        // be found when the pack's persona template was built from a character - a persona
        // is a role, not an NPC, and usually has no CharacterId at all. Rather than guess,
        // this resolves the persona's id to a character when one exists and otherwise takes
        // everyone with an open promise as relevant. That is a deliberately generous
        // reading: a person you owe something to is worth listing whether or not the engine
        // recorded the other side of it.
        val playerId = definition.characters.firstOrNull { it.id.value == instance.persona.id }?.id
        val relevantElsewhere = buildList {
            if (playerId != null) {
                addAll(ledger.promisesOwedTo(playerId).map { it.keeperId })
                addAll(ledger.openPromisesOf(playerId).map { it.beneficiaryId })
            } else {
                addAll(ledger.promises.filter { it.status.isOpen }.flatMap { listOf(it.keeperId, it.beneficiaryId) })
            }
        }
            .filter { it !in present }
            .distinct()

        return (presentIds.map { it to true } + relevantElsewhere.map { it to false }).map { (id, isPresent) ->
            val character = definition.character(id)
            val runtime = instance.characters[id]
            ParticipantChip(
                id = id.value,
                name = character?.name ?: runtime?.name ?: id.value,
                accent = character?.accentLong()?.takeIf { it != 0L } ?: ResolvedTheme.BRAND.primary,
                isFocus = id == instance.focusCharacterId,
                isPlayer = false,
                activityLabel = if (isPresent) {
                    activityLabel(runtime?.activity ?: dev.charaly.runtime.domain.CharacterActivity.IDLE)
                } else {
                    "Not here right now"
                },
                relationshipLabel = relationshipLabelOf(instance.relationships, id),
                isPresent = isPresent,
            )
        }
    }

    /**
     * How a character feels about the player, as a sentence.
     *
     * ## Why this reads the relationship rather than printing an affinity
     *
     * An affinity number is a score the engine computes and the player has no model for. A
     * verb is something they can act on - "wary of you" tells you to approach carefully in
     * a way that "0.42" does not.
     *
     * Built from the relationship's own axes rather than from any single total, and empty
     * when the character has no opinion yet: a fresh acquaintance is not "neutral", they
     * are simply someone you have just met.
     */
    private fun relationshipLabelOf(
        relationships: Map<RelationshipKey, Relationship>,
        characterId: CharacterId,
    ): String {
        // Relationships are keyed by the *pair* that holds them, so the character's own
        // entry is found by looking for the one whose source is them. Looking it up by key
        // alone would silently return nothing on any pack whose keys are ordered the other
        // way, which is the kind of bug that shows up as "relationships never display".
        val relationship = relationships.values.firstOrNull { it.sourceId == characterId }
            ?: return ""
        // Trust and affinity are 0..100 with 50 as their starting point, so the thresholds
        // below are read against that midpoint rather than against zero. Tension is the
        // axis that most reliably reads as a *sentence*: it only exists once something has
        // gone wrong, so a high value is unambiguous.
        return when {
            relationship.tension >= TENSION_HIGH ->
                if (relationship.trust <= TRUST_LOW) "Hâlâ sizi suçluyor" else "Bir şey saklıyor"
            relationship.trust >= TRUST_HIGH && relationship.affinity >= AFFINITY_HIGH ->
                "Güvenle yanınızda"
            relationship.trust >= TRUST_HIGH -> "Size güveniyor"
            relationship.affinity <= AFFINITY_LOW && relationship.trust <= TRUST_LOW -> "Size karşı soğuk"
            relationship.affinity <= AFFINITY_LOW -> "Sizden çekiniyor"
            relationship.affinity >= AFFINITY_HIGH -> "Çabuk ısınıyor"
            relationship.trust <= TRUST_LOW -> "Henüz size güvenmiyor"
            // Untouched axes and a stranger stage means no opinion has formed, which is
            // different from a neutral one and worth saying nothing about.
            relationship.stage == dev.charaly.runtime.domain.RelationshipStage.STRANGER &&
                relationship.familiarity == 0 -> ""
            else -> "Sizi tanımaya çalışıyor"
        }
    }

    /** Thresholds on the 0..100 relationship axes. Named so the labels read as bands. */
    private const val TENSION_HIGH = 40
    private const val TRUST_HIGH = 70
    private const val TRUST_LOW = 35
    private const val AFFINITY_HIGH = 70
    private const val AFFINITY_LOW = 35

    fun phaseLabel(
        phase: GenerationPhase,
        instance: StoryInstance,
        definition: WorldDefinition,
    ): String {
        val speaker = definition.character(instance.focusCharacterId)?.name
        return when (phase) {
            GenerationPhase.IDLE -> ""
            GenerationPhase.THINKING -> "${speaker ?: "Karakter"} düşünüyor…"
            GenerationPhase.STREAMING -> "${speaker ?: "Karakter"} konuşuyor…"
            GenerationPhase.STOPPED -> "Durduruldu"
            GenerationPhase.FAILED -> "Bir şey sahneyi kesti"
        }
    }

    /** Every failure gets a human sentence and at most two next actions. */
    fun failureActions(phase: GenerationPhase, message: String, modelReady: Boolean): List<String> = when {
        phase != GenerationPhase.FAILED -> emptyList()
        !modelReady -> listOf("Open Models")
        message.contains("model", ignoreCase = true) -> listOf("Retry", "Open Models")
        else -> listOf("Retry")
    }

    fun composerHint(instance: StoryInstance, modelReady: Boolean): String = when {
        !modelReady -> "This story needs a local model before it can continue."
        instance.conversation.entries.isEmpty() -> "Start the scene…"
        else -> "Say or do something…"
    }

    private fun sceneOpeningTitle(scene: Scene?, definition: WorldDefinition): String {
        val location = scene?.let { definition.location(it.locationId)?.name } ?: "somewhere"
        return "You are in $location"
    }

    private fun sceneOpeningBody(instance: StoryInstance, definition: WorldDefinition): String {
        val speaker = definition.character(instance.focusCharacterId)?.name ?: return "The scene is waiting."
        return "$speaker is here. Say something, or watch what happens."
    }
}

/** A world event rendered as a timeline card. */
data class WorldEventChip(
    val summary: String,
    val timeLabel: String,
    val isPending: Boolean,
)

/** True while the engine is producing tokens; drives every "stop" affordance. */
fun GenerationPhase.isBusy(): Boolean =
    this == GenerationPhase.THINKING || this == GenerationPhase.STREAMING

// ---------------------------------------------------------------------------
// World state panel
// ---------------------------------------------------------------------------

data class WorldPanelSnapshot(
    val sceneLocation: String,
    val sceneTime: String,
    val weather: String,
    val presentNames: List<String>,
    val characters: List<WorldCharacterRow>,
    val threads: List<ThreadCard>,
    val objectives: List<String>,
    val recentEvents: List<WorldEventChip>,
    val revision: Long,
    val isEmpty: Boolean,
)

data class WorldCharacterRow(
    val id: String,
    val name: String,
    val locationName: String,
    val activity: String,
    val mood: String,
    val relationshipSummary: String,
    val knowsCount: Int,
    val memoryCount: Int,
    val accent: Long,
)

/**
 * The world-state inspection sheet.
 *
 * Deliberately *not* JSON. A normal user wants to know who is where and what is
 * unresolved; the raw state stays in the developer panel.
 */
object WorldPanelPresenter {

    fun build(instance: StoryInstance, pack: StoryPack?): WorldPanelSnapshot {
        // A pack is optional here. When it is missing or unreadable the panel must
        // still render from the ids and names the runtime already holds in world state,
        // rather than showing an empty panel and looking like a lost world.
        val definition = WorldDefinition(
            characters = pack?.characters.orEmpty().ifEmpty {
                instance.worldState.characters.values.map {
                    CharacterDefinition(id = it.characterId, name = it.name)
                }
            },
            locations = pack?.locations.orEmpty().ifEmpty { instance.worldState.locations.values.toList() },
        )
        val scene = instance.currentScene()
        val rows = instance.worldState.characters.values
            .sortedBy { if (it.characterId == instance.focusCharacterId) "" else it.characterId.value }
            .map { runtime ->
                val character = definition.character(runtime.characterId)
                WorldCharacterRow(
                    id = runtime.characterId.value,
                    name = character?.name ?: runtime.name,
                    locationName = definition.location(runtime.locationId)?.name ?: "Unknown",
                    activity = activityLabel(runtime.activity),
                    mood = runtime.mood.ifBlank { "—" },
                    relationshipSummary = relationshipSummary(instance, runtime.characterId),
                    knowsCount = runtime.knownFactIds.size,
                    memoryCount = runtime.memoryIds.size,
                    accent = character?.accentLong()?.takeIf { it != 0L } ?: ResolvedTheme.BRAND.primary,
                )
            }

        val objectives = rows.flatMap { row ->
            instance.characters[CharacterId(row.id)]?.activeGoals.orEmpty()
        }.distinct().take(6)

        return WorldPanelSnapshot(
            sceneLocation = definition.location(scene?.locationId ?: instance.focusCharacter()?.locationId)?.name
                ?: "Nowhere in particular",
            sceneTime = instance.worldClock.now.storyLabel(),
            weather = weatherOf(instance),
            presentNames = scene?.participants.orEmpty().map { definition.nameOf(it) },
            characters = rows,
            threads = instance.worldState.storyThreads.values.map { thread ->
                ThreadCard(
                    id = thread.id.value,
                    title = thread.title,
                    description = thread.description,
                    stage = thread.stage,
                    statusLabel = thread.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    isOpen = thread.isOpen(),
                    participantNames = thread.involvedCharacterIds.map { definition.nameOf(it) },
                )
            }.sortedByDescending { it.isOpen },
            objectives = objectives,
            recentEvents = StoryPresenter.recentWorldEvents(instance, limit = 5),
            revision = instance.worldState.revision,
            isEmpty = rows.isEmpty(),
        )
    }

    /** Weather is optional world content: only shown if the pack defines it. */
    private fun weatherOf(instance: StoryInstance): String =
        instance.worldState.variables["weather"]?.value?.takeIf { it.isNotBlank() }
            ?: instance.worldState.variables["sky"]?.value?.takeIf { it.isNotBlank() }
            ?: ""

    private fun relationshipSummary(instance: StoryInstance, id: CharacterId): String {
        val relationships = instance.relationships.values
            .filter { it.sourceId == id || it.targetId == id }
            .sortedByDescending { it.affinity }
        if (relationships.isEmpty()) return "No ties recorded"
        val best = relationships.first()
        return best.describe(id)
    }
}

// ---------------------------------------------------------------------------
// Memory panel
// ---------------------------------------------------------------------------

data class MemoryPanelSnapshot(
    val sections: List<MemorySection>,
    val totalCount: Int,
    val emptyState: EmptyState?,
)

data class MemorySection(
    val title: String,
    val subtitle: String,
    val memories: List<MemoryRow>,
)

data class MemoryRow(
    val id: String,
    val text: String,
    val importance: Int,
    val importanceLabel: String,
    val ownerName: String,
    val sourceLabel: String,
    val timeLabel: String,
)

/**
 * Memory as a story feature, not a database.
 *
 * The user-facing promise is "the story remembers this". Importance is shown as a
 * short word, never as a number on its own, and vector/database internals are
 * deliberately absent.
 */
/**
 * Memory as a story feature, not a database.
 *
 * Thin compatibility wrapper over [MemoryPanelPresenter], which holds the real logic:
 * the old single-list version had no tabs, no pinning and no notion of superseded
 * claims, so it could only ever show "the last few memories".
 */
object MemoryPresenter {

    fun build(instance: StoryInstance, pack: StoryPack?): MemoryPanelSnapshot {
        val definition = WorldDefinition(
            // Fall back to runtime state so the panel still shows names when the pack
            // is temporarily unavailable. See WorldPanelPresenter.build.
            characters = pack?.characters.orEmpty().ifEmpty {
                instance.worldState.characters.values.map {
                    CharacterDefinition(id = it.characterId, name = it.name)
                }
            },
            locations = pack?.locations.orEmpty().ifEmpty { instance.worldState.locations.values.toList() },
        )
        // Current memories only: a superseded claim is history, and showing it beside its
        // replacement would tell the user two contradictory things are true at once.
        val all = instance.memories.current()

        fun row(memory: dev.charaly.runtime.domain.memory.Memory): MemoryRow = MemoryRow(
            id = memory.id.value,
            text = memory.content,
            importance = memory.importance,
            importanceLabel = importanceLabel(memory.importance),
            ownerName = definition.nameOf(memory.characterId),
            sourceLabel = sourceLabel(memory.source),
            timeLabel = memory.createdAt.storyLabel(),
        )

        val sections = buildList {
            val important = all.filter { it.importance >= 4 }
            if (important.isNotEmpty()) {
                add(
                    MemorySection(
                        title = "Important memories",
                        subtitle = "The things that shaped this story",
                        memories = important.reversed().map(::row),
                    ),
                )
            }
            val recent = all.takeLast(6).reversed()
            if (recent.isNotEmpty()) {
                add(
                    MemorySection(
                        title = "Recent memories",
                        subtitle = "What happened lately",
                        memories = recent.map(::row),
                    ),
                )
            }
            val byCharacter = all.groupBy { it.characterId }
            byCharacter.entries
                .sortedByDescending { it.value.size }
                .take(MAX_CHARACTER_SECTIONS)
                .forEach { (characterId, memories) ->
                    add(
                        MemorySection(
                            title = definition.nameOf(characterId),
                            subtitle = plural(memories.size, "memory", "memories"),
                            memories = memories.reversed().take(6).map(::row),
                        ),
                    )
                }
        }

        return MemoryPanelSnapshot(
            sections = sections,
            totalCount = all.size,
            emptyState = if (all.isEmpty()) {
                EmptyState(
                    title = "Nothing remembered yet.",
                    body = "Memories appear here as the story accumulates them.",
                    artSeed = "charaly-empty-memory",
                )
            } else {
                null
            },
        )
    }

    fun importanceLabel(importance: Int): String = when (importance) {
        5 -> "Defining"
        4 -> "Important"
        3 -> "Noted"
        2 -> "Minor"
        else -> "Trivial"
    }

    fun sourceLabel(source: dev.charaly.runtime.domain.memory.MemorySource): String = when (source) {
        dev.charaly.runtime.domain.memory.MemorySource.EVENT -> "From an event"
        dev.charaly.runtime.domain.memory.MemorySource.DIALOGUE -> "From a conversation"
        dev.charaly.runtime.domain.memory.MemorySource.USER_INPUT -> "From you"
        dev.charaly.runtime.domain.memory.MemorySource.AUTHORED -> "Written into the pack"
        dev.charaly.runtime.domain.memory.MemorySource.CONSOLIDATED -> "Rolled up from earlier moments"
        dev.charaly.runtime.domain.memory.MemorySource.IMPORTED -> "Imported"
    }

    /**
     * The layered-memory label the memory sheet shows instead of an internal name.
     *
     * Delegates rather than repeating the table: two copies of this mapping is how
     * one of them quietly ends up missing a tier the other knows about.
     */
    fun tierLabel(tier: dev.charaly.runtime.domain.memory.MemoryTier): String =
        MemoryPanelPresenter.tierLabel(tier)

    const val MAX_CHARACTER_SECTIONS = 3
}

// ---------------------------------------------------------------------------
// Story info sheet
// ---------------------------------------------------------------------------

data class StoryInfoSnapshot(
    val title: String,
    val packTitle: String,
    val personaName: String,
    val personaRole: String,
    val scenarioTitle: String,
    val binding: ModelBinding,
    val storyClock: String,
    val createdLabel: String,
    val lastPlayedLabel: String,
    val chapters: List<ChapterCard>,
    val isBranch: Boolean,
    val branchFrom: String,
)

data class ChapterCard(
    val index: Int,
    val title: String,
    val summary: String,
    val locationName: String,
    val participantNames: List<String>,
    val timeLabel: String,
    val turnCount: Int,
    val isCurrent: Boolean,
)

object StoryInfoPresenter {

    fun build(nowEpochMs: Long, instance: StoryInstance, pack: StoryPack?): StoryInfoSnapshot {
        val definition = WorldDefinition(
            // Fall back to runtime state so the panel still shows names when the pack
            // is temporarily unavailable. See WorldPanelPresenter.build.
            characters = pack?.characters.orEmpty().ifEmpty {
                instance.worldState.characters.values.map {
                    CharacterDefinition(id = it.characterId, name = it.name)
                }
            },
            locations = pack?.locations.orEmpty().ifEmpty { instance.worldState.locations.values.toList() },
        )
        val scenarioTitle = pack?.scenario(instance.scenarioId)?.title.orEmpty()
        return StoryInfoSnapshot(
            title = instance.displayTitle,
            packTitle = instance.packTitle,
            personaName = instance.persona.userDisplayName.ifBlank { instance.persona.name.ifBlank { "No role chosen" } },
            personaRole = instance.persona.rolePrompt,
            scenarioTitle = scenarioTitle,
            binding = instance.modelBinding,
            storyClock = instance.worldClock.now.storyLabel(),
            createdLabel = RelativeTime.describe(nowEpochMs, instance.sessionMeta.createdAtEpochMs),
            lastPlayedLabel = RelativeTime.describe(nowEpochMs, instance.sessionMeta.lastPlayedAtEpochMs),
            chapters = instance.chapters.map { chapter ->
                ChapterCard(
                    index = chapter.index,
                    title = chapter.title,
                    summary = chapter.summary,
                    locationName = definition.location(chapter.locationId)?.name ?: "Unknown",
                    participantNames = chapter.participantIds.map { definition.nameOf(it) },
                    timeLabel = chapter.startedAt.storyLabel(),
                    turnCount = chapter.turnCount(),
                    isCurrent = chapter.isCurrent,
                )
            },
            isBranch = instance.branchOrigin != dev.charaly.runtime.domain.BranchOrigin.NONE,
            branchFrom = instance.branchedFrom?.sourceTitle.orEmpty(),
        )
    }
}