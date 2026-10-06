package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.model.InstalledModel

// ---------------------------------------------------------------------------
// Shared card models
// ---------------------------------------------------------------------------

/** A Story Pack as a card. Everything a card shows comes from the pack. */
data class PackCard(
    val id: String,
    val title: String,
    val tagline: String,
    val genres: List<String>,
    val characterCount: Int,
    val locationCount: Int,
    val sessionCount: Int,
    val lastPlayedLabel: String,
    val theme: ResolvedTheme,
    val artwork: PackArtwork,
    val isFeatured: Boolean,
    val isDemo: Boolean,
    val fandomNotice: String,
)

/** A character as a card. */
data class CharacterCard(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val locationName: String,
    val accent: Long,
    val artwork: PackArtwork,
    val avatarUri: String?,
    val role: String,
)

/** A location as a card. */
data class LocationCard(
    val id: String,
    val name: String,
    val summary: String,
    val description: String,
    val occupantNames: List<String>,
    val accent: Long,
    val artwork: PackArtwork,
    val rules: List<String>,
    val lore: String,
)

/** A story thread as a card. */
data class ThreadCard(
    val id: String,
    val title: String,
    val description: String,
    val stage: Int,
    val statusLabel: String,
    val isOpen: Boolean,
    val participantNames: List<String>,
)

/** An authored event as a card. */
data class EventCard(
    val id: String,
    val title: String,
    val description: String,
    val triggerLabel: String,
    val conditionsLabel: String,
    val effects: List<String>,
    val locationName: String,
    val participantNames: List<String>,
    val categoryLabel: String,
    val repeatable: Boolean,
    val cooldownMinutes: Long,
)

/** A starting scenario as a card. */
data class ScenarioCard(
    val id: String,
    val title: String,
    val tagline: String,
    val description: String,
    val timeLabel: String,
    val locationName: String,
    val focusName: String?,
    val castNames: List<String>,
    val artwork: PackArtwork,
)

/** A persona as a card. */
data class PersonaCard(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val suggestedNames: List<String>,
)

/** A running story as a card. */
data class SessionCard(
    val id: String,
    val title: String,
    val packTitle: String,
    val packId: String,
    val locationName: String,
    val chapterTitle: String,
    val timeLabel: String,
    val lastPlayedLabel: String,
    val castNames: List<String>,
    val castAccents: List<Long>,
    val turnCount: Int,
    val memoryCount: Int,
    val modelName: String,
    val personaName: String,
    val theme: ResolvedTheme,
    val isBranch: Boolean,
)

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

/** The single "continue this" card: the first thing the user sees. */
data class ContinueCard(
    val storyId: String,
    val packId: String,
    val packTitle: String,
    val storyTitle: String,
    val companionName: String,
    val companionAccent: Long,
    val companionArtwork: PackArtwork,
    val lastPlayedLabel: String,
    val locationLine: String,
    val theme: ResolvedTheme,
    val progressLabel: String,

    // ---- Story Resume Intelligence -------------------------------------
    //
    // The card used to show a title and a timestamp, which means it says nothing about
    // *where you were*. Three of these four are read straight off authoritative state,
    // so a user glancing at Home can tell whether stepping back in is worth it.

    /** "Collège Françoise Dupont" - where the player is standing. */
    val sceneLocation: String = "",
    /** "Evening" - the story's own clock, not the wall clock. */
    val sceneTimeOfDay: String = "",
    /** "Day 3" */
    val sceneDayLabel: String = "",
    /**
     * The open thread the story is currently about, by title.
     *
     * The most useful single line on the card: it is the answer to "what was I doing",
     * phrased as the story phrases it rather than as an engine stage number.
     */
    val currentBeat: String = "",
    /** "Marinette, Alya" - who is here, capped. Empty when the player is alone. */
    val presentNames: List<String> = emptyList(),
    /** "2 people here". Never blank: "Just you" is the honest single case. */
    val presenceLabel: String = "",
    /**
     * The pack's wide hero for this story, when the pack has one.
     *
     * Preferred over a generated composition for the hero because it is the pack's own
     * artwork - the thing that makes the card feel like a story rather than a row.
     */
    val bannerArtwork: PackArtwork? = null,
)

data class ModelStatusCard(
    val modelName: String,
    val stateLabel: String,
    val detailLabel: String,
    val isReady: Boolean,
    val accent: Long,
)

data class HomeSnapshot(
    val greeting: String,
    val subtitle: String,
    val continueCard: ContinueCard?,
    val packs: List<PackCard>,
    val recentSessions: List<SessionCard>,
    val modelStatus: ModelStatusCard,
    val hasAnyContent: Boolean,
    val emptyState: EmptyState?,
) {
    val hasContinue: Boolean get() = continueCard != null
}

/**
 * Builds the Home screen.
 *
 * Home is a launchpad, not a dashboard: the priority order is
 * continue-story > story packs > recent sessions, and nothing else is added to
 * it when there is nothing useful to add.
 */
object HomePresenter {

    fun build(
        nowEpochMs: Long,
        instances: List<StoryInstance>,
        packs: List<StoryPack>,
        definitions: Map<String, WorldDefinition>,
        model: ModelStatusLike,
    ): HomeSnapshot {
        val sorted = instances.sortedWith(
            compareByDescending<StoryInstance> { it.sessionMeta.lastPlayedAtEpochMs }
                .thenByDescending { it.updatedAt.totalMinutes },
        )
        val continueTarget = sorted.firstOrNull()
        val continueCard = continueTarget?.let { instance ->
            val pack = packs.firstOrNull { it.id == instance.storyPackId }
            val definition = definitions[instance.storyPackId.value]
            val companionId = instance.focusCharacterId
                ?: instance.worldState.characters.keys.minByOrNull { it.value }
            val companion = definition?.character(companionId)
            val location = instance.currentLocation()
            val now = instance.worldClock.now

            // Read off the same authoritative state the story screen uses, so the card
            // cannot claim a location the engine disagrees with.
            val playerId = StoryContextPresenter.playerId(instance)
            val presentNames = EventEngine.currentPlayerLocation(instance)
                ?.let { instance.worldState.charactersAt(it) }
                .orEmpty()
                .map { it.characterId }
                .filter { it != playerId }
                .mapNotNull { definition?.character(it)?.name }
                .sorted()
                .take(PRESENT_NAME_CAP)

            val currentBeat = instance.storyThreads.values
                .filter { it.status.isOpen }
                .maxByOrNull { it.priority }
                ?.let { thread -> thread.nextBeat.ifBlank { thread.title } }
                .orEmpty()

            ContinueCard(
                storyId = instance.id.value,
                packId = instance.storyPackId.value,
                packTitle = instance.packTitle,
                storyTitle = instance.displayTitle,
                companionName = companion?.name ?: "Your story",
                companionAccent = companion?.accentLong() ?: ResolvedTheme.BRAND.primary,
                companionArtwork = companion?.artwork ?: PackArtwork.generated(instance.id.value),
                lastPlayedLabel = RelativeTime.describe(nowEpochMs, instance.sessionMeta.lastPlayedAtEpochMs),
                locationLine = location?.let { "${it.name} · ${instance.worldClock.now.clockLabel()}" }.orEmpty(),
                theme = pack?.let { ResolvedTheme.of(it.identity.theme) } ?: ResolvedTheme.BRAND,
                progressLabel = progressLabel(instance),
                sceneLocation = location?.name.orEmpty(),
                sceneTimeOfDay = StoryContextPresenter.timeOfDayLabel(now.hour),
                sceneDayLabel = "Day ${now.day}",
                currentBeat = currentBeat,
                presentNames = presentNames,
                presenceLabel = StoryContextPresenter.presenceLabel(presentNames.size),
                // The pack's cover seed is what its generated artwork is keyed on, so the
                // card gets the pack's *own* composition rather than one hashed from the
                // story id - which would mean every playthrough of a pack looked
                // different from every other.
                bannerArtwork = pack?.let { PackArtwork.generated("banner-${it.id.value}", caption = it.identity.tagline) },
            )
        }

        val packCards = packs
            .sortedWith(
                compareByDescending<StoryPack> { it.identity.isFeatured }
                    .thenByDescending { instances.any { i -> i.storyPackId == it.id } }
                    .thenBy { it.title.lowercase() },
            )
            .map { pack ->
                val packSessions = instances.filter { it.storyPackId == pack.id }
                PackCard(
                    id = pack.id.value,
                    title = pack.title,
                    tagline = pack.identity.tagline.ifBlank { pack.description.lineSequence().firstOrNull().orEmpty() },
                    genres = pack.genreChips().take(3),
                    characterCount = pack.characters.size,
                    locationCount = pack.locations.size,
                    sessionCount = packSessions.size,
                    lastPlayedLabel = packSessions
                        .maxByOrNull { it.sessionMeta.lastPlayedAtEpochMs }
                        ?.let { RelativeTime.compact(nowEpochMs, it.sessionMeta.lastPlayedAtEpochMs) }
                        ?: "New",
                    theme = ResolvedTheme.of(pack.identity.theme),
                    artwork = pack.identity.cover,
                    isFeatured = pack.identity.isFeatured,
                    isDemo = pack.identity.isDemo,
                    fandomNotice = pack.identity.fandomNotice,
                )
            }

        val sessionCards = sorted.take(MAX_RECENT_SESSIONS).map { instance ->
            SessionCard(
                id = instance.id.value,
                title = instance.displayTitle,
                packTitle = instance.packTitle,
                packId = instance.storyPackId.value,
                locationName = instance.currentLocation()?.name ?: "Somewhere in ${instance.packTitle}",
                chapterTitle = instance.currentChapter()?.title.orEmpty(),
                timeLabel = instance.worldClock.now.storyLabel(),
                lastPlayedLabel = RelativeTime.describe(nowEpochMs, instance.sessionMeta.lastPlayedAtEpochMs),
                castNames = castNames(instance, definitions[instance.storyPackId.value]),
                castAccents = castAccents(instance, definitions[instance.storyPackId.value]),
                turnCount = instance.conversation.size,
                memoryCount = instance.memories.size,
                modelName = instance.modelBinding.modelDisplayName.ifBlank { "No model bound" },
                personaName = instance.persona.name,
                theme = packs.firstOrNull { it.id == instance.storyPackId }
                    ?.let { ResolvedTheme.of(it.identity.theme) }
                    ?: ResolvedTheme.BRAND,
                isBranch = instance.branchOrigin != dev.charaly.runtime.domain.BranchOrigin.NONE,
            )
        }

        val modelCard = ModelStatusCard(
            modelName = model.displayName(),
            stateLabel = model.stateLabel(),
            detailLabel = model.detailLabel(),
            isReady = model.isReady(),
            accent = ResolvedTheme.BRAND.accent,
        )

        return HomeSnapshot(
            greeting = greetingFor(nowEpochMs),
            subtitle = homeSubtitle(continueCard, packCards.size, sessionCards.size),
            continueCard = continueCard,
            packs = packCards,
            recentSessions = sessionCards,
            modelStatus = modelCard,
            hasAnyContent = continueCard != null || packCards.isNotEmpty() || sessionCards.isNotEmpty(),
            emptyState = null,
        )
    }

    /** The Home empty state is a door, not an apology. */
    fun emptyState(packCount: Int): EmptyState? =
        if (packCount > 0) {
            null
        } else {
            EmptyState(
                title = "No stories yet.",
                body = "Choose a Story Pack and step into your first world.",
                actionLabel = "Explore Story Packs",
                artSeed = "charaly-empty-home",
            )
        }

    private fun progressLabel(instance: StoryInstance): String {
        val chapters = instance.chapters.size
        val turns = instance.conversation.size
        return when {
            chapters > 1 && turns > 0 -> "Chapter $chapters · $turns lines"
            turns > 0 -> "$turns lines"
            else -> "Just started"
        }
    }

    private fun homeSubtitle(continueCard: ContinueCard?, packCount: Int, sessionCount: Int): String = when {
        continueCard != null -> "Picking up where you left off."
        packCount > 0 -> "$packCount worlds are waiting."
        else -> "Your stories live on this device."
    }

    internal fun castNames(instance: StoryInstance, definition: WorldDefinition?): List<String> {
        val focus = instance.focusCharacterId
        return instance.worldState.characters.values
            .sortedWith(focusFirst(focus))
            .take(MAX_CAST)
            .map { definition?.nameOf(it.characterId) ?: it.name }
    }

    internal fun castAccents(instance: StoryInstance, definition: WorldDefinition?): List<Long> =
        instance.worldState.characters.values
            .sortedWith(focusFirst(instance.focusCharacterId))
            .take(MAX_CAST)
            .mapNotNull { definition?.character(it.characterId)?.accentLong() ?: 0L }
            .filter { it != 0L }

    /** Focus first, then deterministic by id. */
    internal fun focusFirst(focus: CharacterId?): Comparator<dev.charaly.runtime.domain.CharacterRuntime> =
        compareBy<dev.charaly.runtime.domain.CharacterRuntime> {
            if (focus != null && it.characterId == focus) "" else it.characterId.value
        }

    const val MAX_RECENT_SESSIONS = 4

    /**
     * How many names the Continue card lists.
     *
     * Three. Enough to recognise the scene, short enough that a fourth name does not
     * push the beat line off the card.
     */
    const val PRESENT_NAME_CAP = 3
    const val MAX_CAST = 4
}

/** The little model chip on Home, abstracted so the presenter stays testable. */
interface ModelStatusLike {
    fun displayName(): String
    fun stateLabel(): String
    fun detailLabel(): String
    fun isReady(): Boolean
}

/** Adapter for the real model registry + engine state. */
class RegistryModelStatus(
    /**
     * The authoritative selection.
     *
     * Taken as a parameter rather than re-derived here, because this is the class that was
     * re-deriving it: it read "is the engine resident" and reported that as "is a model
     * connected", which is the disagreement the [dev.charaly.runtime.model.ModelSelection]
     * contract exists to end.
     */
    resolved: dev.charaly.runtime.model.ModelSelection = dev.charaly.runtime.model.ModelSelection(),
    private val installed: InstalledModel? = null,
    private val engineLabel: String = "",
    private val loading: Boolean = false,
    /**
     * Convenience for call sites that only know a boolean.
     *
     * `true` means *usable*, and is mapped onto a resident selection - so a caller that
     * used to pass "the engine has something loaded" now gets the stronger, correct
     * statement rather than the weaker one it used to mean.
     */
    ready: Boolean? = null,
) : ModelStatusLike {

    private val selection: dev.charaly.runtime.model.ModelSelection =
        if (ready == null || resolved.isBound) {
            resolved
        } else {
            dev.charaly.runtime.model.ModelSelection.ofReadyFlag(ready)
        }

    override fun displayName(): String =
        selection.displayName.ifBlank { installed?.displayName ?: Loc.t("model.none_installed") }

    override fun stateLabel(): String = when {
        loading -> Loc.t("model.loading")
        selection.canGenerate && selection.isResident -> Loc.t("model.ready")
        selection.canGenerate -> Loc.t("model.installed")
        else -> Loc.t("model.not_installed")
    }

    override fun detailLabel(): String = when {
        !selection.canGenerate -> ModelStagePresenter.reason(selection)
        selection.needsEngineLoad -> Loc.t("model.ready_first_use")
        installed != null -> Loc.t("model.local_meta", installed.sizeLabel)
        else -> Loc.t("model.ready")
    }

    /**
     * "Ready" means *usable*, not *resident*.
     *
     * A model that is installed and merely still loading is ready as far as the user is
     * concerned - they can press Continue and write - so it must not show as needing
     * attention on Home.
     */
    override fun isReady(): Boolean = selection.canGenerate
}

// ---------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------

enum class PackSortOrder(val label: String) {
    RECENT("Recently played"),
    ALPHABETICAL("A to Z"),
    CHARACTERS("Most characters"),
}

data class LibrarySnapshot(
    val query: String,
    val sortOrder: PackSortOrder,
    val availableGenres: List<String>,
    val selectedGenres: Set<String>,
    val sections: List<LibrarySection>,
    val totalPacks: Int,
    val visiblePacks: Int,
    val theme: ResolvedTheme,
    val emptyState: EmptyState?,
)

data class LibrarySection(
    val title: String,
    val subtitle: String,
    val packs: List<PackCard>,
)

/**
 * The Story Pack library: search, filter, sort, and three real sections.
 *
 * Filtering happens on pack metadata only (title, tagline, genres, author), never
 * on world state, so the library stays responsive without loading stories.
 */
object LibraryPresenter {

    fun build(
        nowEpochMs: Long,
        packs: List<StoryPack>,
        instances: List<StoryInstance>,
        query: String = "",
        selectedGenres: Set<String> = emptySet(),
        sortOrder: PackSortOrder = PackSortOrder.RECENT,
    ): LibrarySnapshot {
        val allCards = packs.map { pack ->
            val packSessions = instances.filter { it.storyPackId == pack.id }
            PackCard(
                id = pack.id.value,
                title = pack.title,
                tagline = pack.identity.tagline.ifBlank { pack.description.lineSequence().firstOrNull().orEmpty() },
                genres = pack.genreChips().take(3),
                characterCount = pack.characters.size,
                locationCount = pack.locations.size,
                sessionCount = packSessions.size,
                lastPlayedLabel = packSessions
                    .maxByOrNull { it.sessionMeta.lastPlayedAtEpochMs }
                    ?.let { RelativeTime.compact(nowEpochMs, it.sessionMeta.lastPlayedAtEpochMs) }
                    ?: "New",
                theme = ResolvedTheme.of(pack.identity.theme),
                artwork = pack.identity.cover,
                isFeatured = pack.identity.isFeatured,
                isDemo = pack.identity.isDemo,
                fandomNotice = pack.identity.fandomNotice,
            )
        }

        val filtered = allCards.filter { card ->
            val matchesQuery = query.isBlank() ||
                card.title.contains(query, ignoreCase = true) ||
                card.tagline.contains(query, ignoreCase = true) ||
                card.genres.any { it.contains(query, ignoreCase = true) }
            val matchesGenre = selectedGenres.isEmpty() || selectedGenres.any { selected ->
                card.genres.any { it.equals(selected, ignoreCase = true) }
            }
            matchesQuery && matchesGenre
        }.sortedWith(comparator(sortOrder))

        val sections = buildList {
            if (filtered.isEmpty()) return@buildList
            val featured = filtered.filter { it.isFeatured }
            if (featured.isNotEmpty()) {
                add(
                    LibrarySection(
                        title = "Featured",
                        subtitle = "Hand-picked worlds to start with",
                        packs = featured,
                    ),
                )
            }
            val recent = filtered.filter { card ->
                instances.any { it.storyPackId.value == card.id }
            }.sortedByDescending { card ->
                instances.filter { it.storyPackId.value == card.id }
                    .maxOfOrNull { it.sessionMeta.lastPlayedAtEpochMs } ?: 0L
            }
            if (recent.isNotEmpty()) {
                add(
                    LibrarySection(
                        title = "Recently played",
                        subtitle = "Worlds you have already stepped into",
                        packs = recent,
                    ),
                )
            }
            val rest = filtered.filter { card ->
                card.id !in featured.map { it.id } && card.id !in recent.map { it.id }
            }
            if (rest.isNotEmpty()) {
                add(
                    LibrarySection(
                        title = "All story packs",
                        subtitle = plural(rest.size, "world"),
                        packs = rest,
                    ),
                )
            }
        }

        return LibrarySnapshot(
            query = query,
            sortOrder = sortOrder,
            availableGenres = packs.flatMap { it.genreChips() }.distinct().sorted(),
            selectedGenres = selectedGenres,
            sections = sections,
            totalPacks = packs.size,
            visiblePacks = filtered.size,
            theme = ResolvedTheme.BRAND,
            emptyState = if (filtered.isEmpty()) {
                EmptyState(
                    title = if (packs.isEmpty()) "No story packs yet." else "Nothing matches that.",
                    body = if (packs.isEmpty()) {
                        "Create a world with characters, places and events, then step inside it."
                    } else {
                        "Try a different search, or clear the filters."
                    },
                    actionLabel = if (packs.isEmpty()) "Create Story Pack" else "Clear filters",
                    artSeed = "charaly-empty-library",
                )
            } else {
                null
            },
        )
    }

    private fun comparator(order: PackSortOrder): Comparator<PackCard> = when (order) {
        PackSortOrder.RECENT -> compareByDescending<PackCard> { it.isFeatured }.thenByDescending { it.sessionCount }
        PackSortOrder.ALPHABETICAL -> compareBy { it.title.lowercase() }
        PackSortOrder.CHARACTERS -> compareByDescending<PackCard> { it.characterCount }.thenBy { it.title.lowercase() }
    }
}

// ---------------------------------------------------------------------------
// Pack detail
// ---------------------------------------------------------------------------

/**
 * A pack's canon, flattened into lines a screen can show.
 *
 * Rules first, then the timeline, because the rules are what actually constrains what
 * can happen in the story and the timeline is context for them.
 */
private fun canonLinesOf(canon: dev.charaly.runtime.domain.CanonBible): List<String> = buildList {
    if (canon.universe.isNotBlank()) add("Setting: ${canon.universe}")
    canon.worldRules.forEach { rule ->
        add(if (rule.secret) "Secret rule: ${rule.statement}" else "Rule: ${rule.statement}")
        rule.forbids.forEach { add("  never: $it") }
    }
    canon.timeline.forEach { era ->
        add("${era.title}: ${era.summary}")
        era.distinguishingFacts.forEach { add("  $it") }
    }
    canon.organizations.forEach { org -> add("${org.name}: ${org.purpose}") }
    canon.importantObjects.forEach { obj -> add("${obj.name} - ${obj.description}") }
}

data class PackDetailSnapshot(
    val id: String,
    val title: String,
    val tagline: String,
    val description: String,
    val genres: List<String>,
    val author: String,
    val era: String,
    val tone: String,
    val theme: ResolvedTheme,
    val artwork: PackArtwork,
    val fandomNotice: String,
    val contentNotes: List<String>,
    val characters: List<CharacterCard>,
    val locations: List<LocationCard>,
    val threads: List<ThreadCard>,
    val events: List<EventCard>,
    val scenarios: List<ScenarioCard>,
    val personas: List<PersonaCard>,
    val factions: List<FactionCard>,
    val lore: List<LoreCard>,
    val activeStories: List<SessionCard>,
    val stats: List<StatChip>,
    val defaultProfileName: String,
    val canContinue: Boolean,
    /**
     * The pack's canon, in the user's words.
     *
     * Shown because "is this still the author's version of the world?" is a question a
     * player of a media pack genuinely has, and answering it from the pack rather than
     * from a running story is the whole point of separating the two.
     */
    val canonLines: List<String> = emptyList(),
    /** The pack's explicit rights/attribution notice, if it declares one. */
    val canonNotice: String = "",
)

data class FactionCard(
    val id: String,
    val name: String,
    val motto: String,
    val description: String,
    val memberNames: List<String>,
    val accent: Long,
)

data class LoreCard(
    val id: String,
    val title: String,
    val content: String,
    val importance: Int,
    val isSecret: Boolean,
)

data class StatChip(val label: String, val value: String)

/**
 * The "enter a universe" screen.
 *
 * Note what it does *not* do: it does not start a story. Opening a pack is a
 * look around; starting one is an explicit, separate decision.
 */
object PackDetailPresenter {

    fun build(
        nowEpochMs: Long,
        pack: StoryPack,
        instances: List<StoryInstance>,
        defaultProfileName: String,
    ): PackDetailSnapshot {
        val packSessions = instances.filter { it.storyPackId == pack.id }
            .sortedByDescending { it.sessionMeta.lastPlayedAtEpochMs }

        return PackDetailSnapshot(
            id = pack.id.value,
            title = pack.title,
            tagline = pack.identity.tagline,
            description = pack.description,
            genres = pack.genreChips(),
            author = pack.author,
            era = pack.identity.era,
            tone = pack.identity.tone,
            theme = ResolvedTheme.of(pack.identity.theme),
            artwork = pack.identity.cover,
            fandomNotice = pack.identity.fandomNotice,
            contentNotes = pack.identity.contentNotes,
            characters = pack.characters.map { character ->
                val startId = character.startingLocationId ?: pack.startLocationOf(character.id)
                val locationName = startId?.let { pack.location(it)?.name }.orEmpty()
                CharacterCard(
                    id = character.id.value,
                    name = character.name,
                    tagline = character.summaryLine(),
                    description = character.description,
                    locationName = locationName,
                    accent = character.accentLong().takeIf { it != 0L } ?: ResolvedTheme.of(pack.identity.theme).primary,
                    artwork = character.artwork,
                    avatarUri = character.avatarUri,
                    role = character.identityRole,
                )
            },
            locations = pack.locations.map { location ->
                LocationCard(
                    id = location.id.value,
                    name = location.name,
                    summary = location.blurb(),
                    description = location.description,
                    occupantNames = location.startingOccupants.map { id ->
                        pack.character(id)?.name ?: id.value
                    },
                    accent = location.accentLong().takeIf { it != 0L } ?: ResolvedTheme.of(pack.identity.theme).secondary,
                    artwork = location.artwork,
                    rules = location.rules,
                    lore = location.lore,
                )
            },
            threads = pack.initialStoryThreads.map { thread ->
                ThreadCard(
                    id = thread.id.value,
                    title = thread.title,
                    description = thread.description,
                    stage = thread.stage,
                    statusLabel = thread.status.name.lowercase().replaceFirstChar { it.uppercase() },
                    isOpen = thread.isOpen(),
                    participantNames = thread.involvedCharacterIds.map { id ->
                        pack.character(id)?.name ?: id.value
                    },
                )
            },
            events = pack.events.map { event -> eventCard(event, pack) },
            scenarios = pack.scenarios.map { scenario -> scenarioCard(scenario, pack) },
            personas = pack.personas.map { persona -> personaCard(persona, pack) },
            factions = pack.factions.map { faction ->
                FactionCard(
                    id = faction.id,
                    name = faction.name,
                    motto = faction.motto,
                    description = faction.description,
                    memberNames = faction.memberCharacterIds.map { id -> pack.character(id)?.name ?: id.value },
                    accent = faction.colorHex.takeIf { it.isNotBlank() }
                        ?.let { dev.charaly.runtime.domain.PackColor.parse(it) }
                        ?: ResolvedTheme.of(pack.identity.theme).accent,
                )
            },
            lore = pack.lore.map { entry ->
                LoreCard(
                    id = entry.id,
                    title = entry.title,
                    content = entry.content,
                    importance = entry.importance,
                    isSecret = entry.secret,
                )
            },
            activeStories = packSessions.map { instance ->
                SessionCard(
                    id = instance.id.value,
                    title = instance.displayTitle,
                    packTitle = instance.packTitle,
                    packId = instance.storyPackId.value,
                    locationName = instance.currentLocation()?.name ?: "Somewhere",
                    chapterTitle = instance.currentChapter()?.title.orEmpty(),
                    timeLabel = instance.worldClock.now.storyLabel(),
                    lastPlayedLabel = RelativeTime.describe(nowEpochMs, instance.sessionMeta.lastPlayedAtEpochMs),
                    castNames = HomePresenter.castNames(instance, WorldDefinition(pack.characters, pack.locations)),
                    castAccents = emptyList(),
                    turnCount = instance.conversation.size,
                    memoryCount = instance.memories.size,
                    modelName = instance.modelBinding.modelDisplayName.ifBlank { "No model bound" },
                    personaName = instance.persona.name,
                    theme = ResolvedTheme.of(pack.identity.theme),
                    isBranch = instance.branchOrigin != dev.charaly.runtime.domain.BranchOrigin.NONE,
                )
            },
            stats = listOf(
                StatChip("Characters", pack.characters.size.toString()),
                StatChip("Locations", pack.locations.size.toString()),
                StatChip("Threads", pack.initialStoryThreads.size.toString()),
                StatChip("Events", pack.events.size.toString()),
                StatChip("Stories", packSessions.size.toString()),
            ),
            defaultProfileName = defaultProfileName,
            canContinue = packSessions.isNotEmpty(),
            canonLines = canonLinesOf(pack.canon),
            canonNotice = pack.canon.rightsNotice,
        )
    }

    /**
     * The snapshot for a pack, or null when the pack no longer exists.
     *
     * A pack can disappear while its detail screen is still on the back stack - deleted
     * in the creator, or a demo pack that failed to restore. Returning null lets the
     * screen render its own "missing" state; throwing would take the app down over a
     * navigation edge case.
     */
    fun buildOrNull(
        nowEpochMs: Long,
        pack: StoryPack?,
        instances: List<StoryInstance>,
        defaultProfileName: String,
    ): PackDetailSnapshot? = pack?.let { build(nowEpochMs, it, instances, defaultProfileName) }

    fun eventCard(event: PackEventDefinition, pack: StoryPack): EventCard = EventCard(
        id = event.id,
        title = event.title,
        description = event.description,
        triggerLabel = triggerLabel(event),
        conditionsLabel = dev.charaly.runtime.domain.EventConditions.describe(event.conditions),
        effects = dev.charaly.runtime.engine.effectPreview(event),
        locationName = event.locationId?.let { pack.location(it)?.name ?: it.value }.orEmpty(),
        participantNames = event.participants.map { id -> pack.character(id)?.name ?: id.value },
        categoryLabel = when (event.category) {
            dev.charaly.runtime.domain.EventCategory.STORY_START -> "At story start"
            dev.charaly.runtime.domain.EventCategory.SCHEDULED -> "Scheduled"
            dev.charaly.runtime.domain.EventCategory.CONDITIONAL -> "Conditional"
        },
        repeatable = event.repeatable,
        cooldownMinutes = event.cooldownMinutes,
    )

    fun triggerLabel(event: PackEventDefinition): String = when (val trigger = event.trigger) {
        is dev.charaly.runtime.domain.EventTrigger.StoryStart ->
            if (trigger.isUniversal) "When the story starts" else "When a chosen opening begins"
        is dev.charaly.runtime.domain.EventTrigger.AfterDelay -> "${trigger.delayMinutes} min after the start"
        is dev.charaly.runtime.domain.EventTrigger.AtStoryTime -> "At ${trigger.time.storyLabel()}"
        is dev.charaly.runtime.domain.EventTrigger.WhenConditionMet -> "Whenever the world lines up"
    }

    fun scenarioCard(scenario: StartingScenario, pack: StoryPack): ScenarioCard = ScenarioCard(
        id = scenario.id,
        title = scenario.title,
        tagline = scenario.tagline,
        description = scenario.description,
        timeLabel = scenario.startTime.storyLabel(),
        locationName = pack.location(scenario.startLocationId)?.name ?: scenario.startLocationId.value,
        focusName = scenario.focusCharacterId?.let { pack.character(it)?.name },
        castNames = scenario.castCharacterIds.map { pack.character(it)?.name ?: it.value },
        artwork = scenario.artwork,
    )

    fun personaCard(persona: PersonaTemplate, pack: StoryPack): PersonaCard = PersonaCard(
        id = persona.id,
        name = persona.name,
        tagline = persona.tagline,
        description = persona.description,
        suggestedNames = persona.suggestedCharacterIds.map { pack.character(it)?.name ?: it.value },
    )
}

/** Convenience for tests and previews: ids without the full pack. */
internal fun String.asCharacterId(): CharacterId = CharacterId(this)

internal fun String.asLocationId(): LocationId = LocationId(this)