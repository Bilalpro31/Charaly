package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.CanonBible
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.Faction
import dev.charaly.runtime.domain.InitialKnowledge
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.PackAudioAsset
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.PackIdentity
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.PackSettings
import dev.charaly.runtime.domain.PackStructureSections
import dev.charaly.runtime.domain.PackWorld
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.SeedEvent
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.StoryThread
import dev.charaly.runtime.domain.VisualAsset
import dev.charaly.runtime.domain.VisualAssetType
import dev.charaly.runtime.domain.WorldLoreEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The *content* half of a Story Pack: one section per content file, plus the
 * translations that ride along beside them.
 *
 * ## Why sections and not one document
 *
 * A pack is edited by humans and diffed by version control. `characters.json` that a
 * co-author can read, argue with and merge is worth more than one 400 KB document nobody
 * can review. It is also what makes "did 1.2.0 change the cast?" a question about one
 * file instead of a question about everything.
 *
 * ## Why the split is lossless
 *
 * [StoryPack] is the runtime's model and this is the format's model, and the two meet
 * exactly twice: [compile] and [of]. Section for field, field for field, in both
 * directions - `StoryPackRoundTripTest` proves it byte for byte.
 *
 * Identity is the one asymmetry. Title, author, licence and friends live in the
 * *manifest* (they are distribution metadata), so [compile] takes them as arguments and
 * [of] hands them back on the manifest builder rather than inside a section.
 */
@Serializable
data class StoryPackBundle(
    val schemaVersion: Int = StoryPackFormat.SCHEMA_VERSION,
    val world: WorldSection = WorldSection(),
    val canon: CanonBible = CanonBible(),
    val characters: List<CharacterDefinition> = emptyList(),
    val locations: List<Location> = emptyList(),
    val relationships: List<Relationship> = emptyList(),
    val scenes: List<SceneTemplate> = emptyList(),
    val events: EventsSection = EventsSection(),
    val threads: List<StoryThread> = emptyList(),
    val objectives: List<PackObjective> = emptyList(),
    val scenarios: List<StartingScenario> = emptyList(),
    val personas: List<PersonaTemplate> = emptyList(),
    val knowledge: InitialKnowledge = InitialKnowledge(),
    val assets: AssetsSection = AssetsSection(),
    val presentation: PresentationSection = PresentationSection(),
) {
    /** Countable content, for the "50 events, 12 characters" style of validation summary. */
    fun census(): Census = Census(
        characters = characters.size,
        locations = locations.size,
        relationships = relationships.size,
        scenes = scenes.size,
        events = events.events.size,
        threads = threads.size,
        objectives = objectives.size,
        scenarios = scenarios.size,
        personas = personas.size,
        factions = world.factions.size,
        lore = world.lore.size,
        facts = knowledge.facts.size,
        visualAssets = assets.visualAssets.size,
        audioAssets = assets.audioAssets.size,
    )

    data class Census(
        val characters: Int,
        val locations: Int,
        val relationships: Int,
        val scenes: Int,
        val events: Int,
        val threads: Int,
        val objectives: Int,
        val scenarios: Int,
        val personas: Int,
        val factions: Int,
        val lore: Int,
        val facts: Int,
        val visualAssets: Int,
        val audioAssets: Int,
    ) {
        /** One honest line per kind of content, for a player-facing review step. */
        fun lines(): List<Pair<String, Int>> = listOf(
            "karakter" to characters,
            "mekân" to locations,
            "ilişki" to relationships,
            "sahne" to scenes,
            "olay" to events,
            "hikâye çizgisi" to threads,
            "görev" to objectives,
            "açılış" to scenarios,
            "rol" to personas,
            "topluluk" to factions,
            "bilgi" to lore + facts,
            "varlık" to visualAssets + audioAssets,
        ).filter { it.second > 0 }
    }

    companion object {

        /**
         * Splits a pack into its content files.
         *
         * Pure: no IO, no ids invented, nothing dropped.
         */
        fun of(pack: StoryPack): StoryPackBundle = StoryPackBundle(
            world = WorldSection(
                world = pack.world,
                initialWorldState = pack.initialWorldState,
                factions = pack.factions,
                lore = pack.lore,
                defaultNarrativeStyle = pack.defaultNarrativeStyle,
                defaultModelProfileId = pack.defaultModelProfileId,
                modelProfileIds = pack.modelProfileIds,
                settings = pack.settings,
            ),
            canon = pack.canon,
            characters = pack.characters,
            locations = pack.locations,
            relationships = pack.initialRelationships,
            scenes = pack.scenes,
            events = EventsSection(events = pack.events, initialEvents = pack.initialEvents),
            threads = pack.initialStoryThreads,
            objectives = pack.objectives,
            scenarios = pack.scenarios,
            personas = pack.personas,
            knowledge = pack.initialKnowledge,
            assets = AssetsSection(visualAssets = pack.visualAssets, audioAssets = pack.audioAssets),
            presentation = PresentationSection(
                identity = pack.identity,
                tags = pack.tags,
                distribution = ManifestIdentity(
                    packId = pack.id.value,
                    title = pack.title,
                    description = pack.description,
                    premise = pack.identity.premise,
                    author = pack.author,
                    publisher = pack.publisher,
                    language = pack.language,
                    license = pack.license,
                    tags = pack.tags,
                    cover = PackManifestBuilder.coverRefOf(pack),
                    entryPoint = pack.entryPoint,
                    packVersion = pack.semanticVersion,
                    minimumEngineVersion = pack.minimumEngineVersion,
                    maximumEngineVersion = pack.maximumEngineVersion,
                    createdAtEpochMs = pack.createdAtEpochMs,
                    updatedAtEpochMs = pack.updatedAtEpochMs,
                ),
            ),
        )
    }

    /**
     * Reassembles the runtime model.
     *
     * Identity arrives as an argument because it lives on the manifest. Throwing here is
     * correct and expected: the validation layer catches [PackFormatException] and turns
     * it into a `Kritik Hata` a person can read, which is far better than a pack that
     * half-decodes into something playable.
     */
    fun compile(
        packId: String,
        identity: ManifestIdentity = presentation.distribution.copy(
            packId = presentation.distribution.packId.ifBlank { packId },
        ),
    ): StoryPack {
        // The manifest wins where it speaks and is silent where it is blank, so a bundle
        // read with no manifest at all is still exactly the pack that was exported.
        val mergedIdentity = presentation.identity.copy(
            premise = identity.premise.ifBlank { presentation.identity.premise },
            cover = presentation.identity.cover.copy(
                seed = identity.cover.ifBlank { presentation.identity.cover.seed },
            ),
        )
        return try {
            StoryPack(
                id = StoryPackId(identity.packId.ifBlank { packId }),
                title = identity.title,
                description = identity.description,
                author = identity.author,
                packVersion = identity.packVersion,
                publisher = identity.publisher,
                license = identity.license,
                language = identity.language,
                createdAtEpochMs = identity.createdAtEpochMs,
                updatedAtEpochMs = identity.updatedAtEpochMs,
                minimumEngineVersion = identity.minimumEngineVersion,
                maximumEngineVersion = identity.maximumEngineVersion,
                entryPoint = identity.entryPoint,
                tags = presentation.tags.ifEmpty { identity.tags },
                characters = characters,
                locations = locations,
                initialWorldState = world.initialWorldState,
                initialRelationships = relationships,
                initialKnowledge = knowledge,
                initialStoryThreads = threads,
                initialEvents = events.initialEvents,
                identity = mergedIdentity,
                factions = world.factions,
                lore = world.lore,
                events = events.events,
                scenarios = scenarios,
                personas = personas,
                defaultModelProfileId = world.defaultModelProfileId,
                defaultNarrativeStyle = world.defaultNarrativeStyle,
                canon = canon,
                modelProfileIds = world.modelProfileIds,
                visualAssets = assets.visualAssets,
                audioAssets = assets.audioAssets,
                world = world.world,
                scenes = scenes,
                objectives = objectives,
                settings = world.settings,
            )
        } catch (cause: IllegalArgumentException) {
            throw PackFormatException("content does not form a valid story pack: ${cause.message}")
        }
    }
}

/** The identity fields a manifest carries, in one place for [StoryPackBundle.compile]. */
@Serializable
data class ManifestIdentity(
    val packId: String,
    val title: String,
    val description: String = "",
    val premise: String = "",
    val author: String = "",
    val publisher: String = "",
    val language: String = "tr",
    val license: String = "",
    val tags: List<String> = emptyList(),
    val cover: String = "",
    val entryPoint: String = "",
    val packVersion: String = "1.0.0",
    val minimumEngineVersion: String = "",
    val maximumEngineVersion: String = "",
    val createdAtEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
)

/** Setting, starting world, groups, background, and the pack's model opinions. */
@Serializable
data class WorldSection(
    val world: PackWorld = PackWorld(),
    val initialWorldState: InitialWorldState = InitialWorldState(),
    val factions: List<Faction> = emptyList(),
    val lore: List<WorldLoreEntry> = emptyList(),
    val defaultNarrativeStyle: String = "",
    val defaultModelProfileId: String = "",
    val modelProfileIds: List<String> = emptyList(),
    val settings: PackSettings = PackSettings(),
)

/** Authored events, plus the raw seed events that fire at story start. */
@Serializable
data class EventsSection(
    val events: List<PackEventDefinition> = emptyList(),
    val initialEvents: List<SeedEvent> = emptyList(),
)

/** Packaged artwork and sound: the inventory, never the bytes. */
@Serializable
data class AssetsSection(
    val visualAssets: List<VisualAsset> = emptyList(),
    val audioAssets: List<PackAudioAsset> = emptyList(),
)

/**
 * How the pack presents itself.
 *
 * Split from the manifest deliberately: the manifest is what a *store* needs to list and
 * verify a pack, this is what a *reader* needs to feel invited by one. Both describe the
 * same pack, and where they overlap the manifest wins on import - one source of truth per
 * fact, stated rather than hoped.
 */
@Serializable
data class PresentationSection(
    val identity: PackIdentity = PackIdentity(),
    val tags: List<String> = emptyList(),
    /**
     * The distribution half of the pack's identity: title, author, licence, version.
     *
     * A mirror of the manifest's identity fields, kept *inside the content* so a bundle is
     * self describing - a content tree with no manifest is still a readable pack - while
     * the manifest stays authoritative whenever both speak. That is also why a round trip
     * is lossless: what goes into the manifest comes back out of the content.
     */
    val distribution: ManifestIdentity = ManifestIdentity(packId = "", title = ""),
)

/**
 * Reads and writes the content files of a pack.
 *
 * Every file is an envelope:
 *
 * ```json
 * { "schemaVersion": 1, "kind": "characters", "data": [ ... ] }
 * ```
 *
 * The envelope buys two things for six bytes per file: an explicit version to refuse
 * politely instead of misreading, and a `kind` so a swapped file is detected as a swap
 * rather than decoded as garbage.
 */
object StoryPackCodec {
    fun encode(pack: StoryPack): Map<String, String> = encodeBundle(StoryPackBundle.of(pack))

    fun encodeBundle(bundle: StoryPackBundle): Map<String, String> {
        val json = StoryPackFormat.json
        fun file(kind: String, data: JsonElement): String =
            json.encodeToString(
                ContentEnvelope.serializer(),
                ContentEnvelope(schemaVersion = StoryPackFormat.SCHEMA_VERSION, kind = kind, data = data),
            )

        return linkedMapOf(
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.WORLD_FILE}" to
                file("world", json.encodeToJsonElement(WorldSection.serializer(), bundle.world)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.CANON_FILE}" to
                file("canon", json.encodeToJsonElement(CanonBible.serializer(), bundle.canon)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.CHARACTERS_FILE}" to
                file("characters", json.encodeToJsonElement(BUNDLE_CHARACTERS, bundle.characters)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.LOCATIONS_FILE}" to
                file("locations", json.encodeToJsonElement(BUNDLE_LOCATIONS, bundle.locations)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.RELATIONSHIPS_FILE}" to
                file("relationships", json.encodeToJsonElement(BUNDLE_RELATIONSHIPS, bundle.relationships)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.SCENES_FILE}" to
                file(PackStructureSections.SCENES, json.encodeToJsonElement(BUNDLE_SCENES, bundle.scenes)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.EVENTS_FILE}" to
                file("events", json.encodeToJsonElement(EventsSection.serializer(), bundle.events)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.THREADS_FILE}" to
                file("threads", json.encodeToJsonElement(BUNDLE_THREADS, bundle.threads)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.OBJECTIVES_FILE}" to
                file(PackStructureSections.OBJECTIVES, json.encodeToJsonElement(BUNDLE_OBJECTIVES, bundle.objectives)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.SCENARIOS_FILE}" to
                file("scenarios", json.encodeToJsonElement(BUNDLE_SCENARIOS, bundle.scenarios)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.PERSONAS_FILE}" to
                file("personas", json.encodeToJsonElement(BUNDLE_PERSONAS, bundle.personas)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.KNOWLEDGE_FILE}" to
                file("knowledge", json.encodeToJsonElement(InitialKnowledge.serializer(), bundle.knowledge)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.ASSETS_FILE}" to
                file("assets", json.encodeToJsonElement(AssetsSection.serializer(), bundle.assets)),
            "${StoryPackFormat.CONTENT_DIR}/${StoryPackFormat.PRESENTATION_FILE}" to
                file("presentation", json.encodeToJsonElement(PresentationSection.serializer(), bundle.presentation)),
        )
    }

    /**
     * Decodes a bundle from already-extracted `path -> text` files.
     *
     * A missing content file is *tolerated* as an empty section so that a hand-built or
     * slim pack still loads - but it will have been flagged by validation first, so
     * tolerance here is never the reason a broken pack is playable.
     */
    fun decode(files: Map<String, String>): DecodeResult {
        val problems = mutableListOf<String>()

        fun <T> read(
            name: String,
            serializer: kotlinx.serialization.KSerializer<T>,
            default: T,
        ): T {
            val path = "${StoryPackFormat.CONTENT_DIR}/$name"
            val raw = files[path] ?: run { problems += "$name dosyası yok"; return default }
            val envelope = runCatching {
                StoryPackFormat.json.decodeFromString(ContentEnvelope.serializer(), raw)
            }.getOrElse {
                problems += "$name dosyası okunamadı"
                return default
            }
            if (envelope.schemaVersion > StoryPackFormat.SCHEMA_VERSION) {
                problems += "$name dosyası bu sürüm için çok yeni bir şema taşıyor (${envelope.schemaVersion})"
                return default
            }
            if (envelope.schemaVersion < StoryPackFormat.MINIMUM_SCHEMA_VERSION) {
                problems += "$name dosyası desteklenmeyen bir şema taşıyor (${envelope.schemaVersion})"
                return default
            }
            return runCatching { StoryPackFormat.json.decodeFromJsonElement(serializer, envelope.data) }
                .getOrElse { problems += "$name dosyası çözümlenemedi: ${it.message}"; default }
        }

        val bundle = StoryPackBundle(
            world = read(StoryPackFormat.WORLD_FILE, WorldSection.serializer(), WorldSection()),
            canon = read(StoryPackFormat.CANON_FILE, CanonBible.serializer(), CanonBible()),
            characters = read(StoryPackFormat.CHARACTERS_FILE, BUNDLE_CHARACTERS, emptyList()),
            locations = read(StoryPackFormat.LOCATIONS_FILE, BUNDLE_LOCATIONS, emptyList()),
            relationships = read(StoryPackFormat.RELATIONSHIPS_FILE, BUNDLE_RELATIONSHIPS, emptyList()),
            scenes = read(StoryPackFormat.SCENES_FILE, BUNDLE_SCENES, emptyList()),
            events = read(StoryPackFormat.EVENTS_FILE, EventsSection.serializer(), EventsSection()),
            threads = read(StoryPackFormat.THREADS_FILE, BUNDLE_THREADS, emptyList()),
            objectives = read(StoryPackFormat.OBJECTIVES_FILE, BUNDLE_OBJECTIVES, emptyList()),
            scenarios = read(StoryPackFormat.SCENARIOS_FILE, BUNDLE_SCENARIOS, emptyList()),
            personas = read(StoryPackFormat.PERSONAS_FILE, BUNDLE_PERSONAS, emptyList()),
            knowledge = read(StoryPackFormat.KNOWLEDGE_FILE, InitialKnowledge.serializer(), InitialKnowledge()),
            assets = read(StoryPackFormat.ASSETS_FILE, AssetsSection.serializer(), AssetsSection()),
            presentation = read(StoryPackFormat.PRESENTATION_FILE, PresentationSection.serializer(), PresentationSection()),
        )
        return DecodeResult(bundle = bundle, problems = problems)
    }

    /** The bundle, plus every section that failed to decode. */
    data class DecodeResult(val bundle: StoryPackBundle, val problems: List<String>) {
        val isClean: Boolean get() = problems.isEmpty()
    }

    // kotlinx needs concrete serializers for generics; one per list type.
    private val BUNDLE_CHARACTERS = kotlinx.serialization.builtins.ListSerializer(CharacterDefinition.serializer())
    private val BUNDLE_LOCATIONS = kotlinx.serialization.builtins.ListSerializer(Location.serializer())
    private val BUNDLE_RELATIONSHIPS = kotlinx.serialization.builtins.ListSerializer(Relationship.serializer())
    private val BUNDLE_SCENES = kotlinx.serialization.builtins.ListSerializer(SceneTemplate.serializer())
    private val BUNDLE_THREADS = kotlinx.serialization.builtins.ListSerializer(StoryThread.serializer())
    private val BUNDLE_OBJECTIVES = kotlinx.serialization.builtins.ListSerializer(PackObjective.serializer())
    private val BUNDLE_SCENARIOS = kotlinx.serialization.builtins.ListSerializer(StartingScenario.serializer())
    private val BUNDLE_PERSONAS = kotlinx.serialization.builtins.ListSerializer(PersonaTemplate.serializer())
}

/**
 * The on-disk envelope for one content file.
 *
 * `data` stays a raw [JsonElement] on purpose: the envelope must be readable even when
 * the section body is from a future schema, so the two are decoded in two steps.
 */
@Serializable
data class ContentEnvelope(
    /**
     * Written unconditionally (no default) so it survives `encodeDefaults = false`: the
     * version is the one field a reader must never have to guess.
     */
    val schemaVersion: Int,
    val kind: String = "",
    val data: JsonElement,
)

/** Raised when pack content cannot be formed into a valid [StoryPack]. */
class PackFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Builds a manifest for a pack, computing the file inventory and the pack digest.
 *
 * The only place a manifest is minted. Hand-writing one is allowed (they are just JSON)
 * and the validator will hold it to exactly the same rules.
 */
object PackManifestBuilder {

    fun build(
        pack: StoryPack,
        files: Map<String, ByteArray>,
        localization: Map<String, Map<String, String>> = emptyMap(),
        extraAssetPaths: List<String> = emptyList(),
    ): PackManifest {
        val entries = files.map { (path, bytes) ->
            PackFileEntry(
                path = path,
                sizeBytes = bytes.size.toLong(),
                sha256 = Sha256.of(bytes),
                required = requiredFor(path),
            )
        }
        val localizationEntries = localization.map { (language, table) ->
            val bytes = LocalizationCodec.encode(StoryPackFormat.json, table).toByteArray(Charsets.UTF_8)
            PackFileEntry(
                path = localizationPath(language),
                sizeBytes = bytes.size.toLong(),
                sha256 = Sha256.of(bytes),
                required = language == pack.language,
            )
        }
        // Assets that ship as binary payloads are inventoried here even though their bytes
        // are written by the archiver, so the digest covers them like everything else.
        val assetEntries = extraAssetPaths.distinct().sorted().map { path ->
            val bytes = files[path]
            PackFileEntry(
                path = path,
                sizeBytes = bytes?.size?.toLong() ?: 0L,
                sha256 = bytes?.let(Sha256::of) ?: "",
                required = requiredAsset(path, pack),
            )
        }
        val all = (entries + localizationEntries + assetEntries)
            .distinctBy { it.path }
            .sortedBy { it.path }
        return PackManifest(
            packId = pack.id.value,
            packVersion = pack.semanticVersion,
            title = pack.title,
            description = pack.description,
            premise = pack.identity.premise,
            author = pack.author,
            publisher = pack.publisher,
            language = pack.language,
            license = pack.license,
            tags = pack.genreChips(),
            cover = coverRefOf(pack),
            entryPoint = pack.entryPoint,
            minimumEngineVersion = pack.minimumEngineVersion,
            maximumEngineVersion = pack.maximumEngineVersion,
            createdAtEpochMs = pack.createdAtEpochMs,
            updatedAtEpochMs = pack.updatedAtEpochMs,
            sizeBytes = all.sumOf { it.sizeBytes },
            sha256 = PackDigest.of(all),
            files = all,
        )
    }

    /** Where a language's translations live. */
    fun localizationPath(language: String): String =
        "${StoryPackFormat.LOCALIZATION_DIR}/${language.lowercase().ifBlank { "en" }}.json"

    /** Whether the absence of this file would make the pack unusable. */
    fun requiredFor(path: String): Boolean = when {
        path == StoryPackFormat.MANIFEST_PATH -> true
        path.startsWith("${StoryPackFormat.CONTENT_DIR}/") -> true
        path.startsWith("${StoryPackFormat.LOCALIZATION_DIR}/") -> false
        path.startsWith("${StoryPackFormat.ASSET_DIR}/") -> true
        else -> false
    }

    private fun requiredAsset(path: String, pack: StoryPack): Boolean {
        val requiredRefs = buildSet {
            pack.visualAssets.filter { it.isPresent }.forEach { add(it.preferredRef()) }
            pack.audioAssets.filter { it.required }.forEach { add(it.path) }
        }
        return path in requiredRefs
    }

    /** The cover as a portable reference: a relative asset path, or blank for generated art. */
    fun coverRefOf(pack: StoryPack): String {
        pack.visualAssets
            .firstOrNull { it.type == VisualAssetType.PACK_COVER && it.isPresent }
            ?.let { return it.preferredRef() }
        val seed = pack.identity.cover.uri.orEmpty()
        return if (seed.startsWith("${StoryPackFormat.ASSET_DIR}/")) seed else ""
    }
}