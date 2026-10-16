package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.InitialKnowledge
import dev.charaly.runtime.domain.InitialWorldState
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.PackSettings
import dev.charaly.runtime.domain.PackWorld
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.StoryThread
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.domain.settingEra
import dev.charaly.runtime.domain.settingUniverse

/**
 * A small, complete, valid pack: the fixture almost every pack test builds on.
 *
 * Written out longhand rather than imported from the demo packs on purpose - a validator
 * test that shares its subject with production content stops finding anything the moment
 * the demo content changes to please the validator.
 */
object PackFixtures {

    /** The variable every branch and objective in the fixture pivots on. */
    const val playerHelped = "player_helped_marinette"

    val paris: Location = Location(
        id = LocationId("loc-rooftop"),
        name = "Paris Rooftops",
        description = "Tiles, antennas, and the whole city breathing below.",
        connections = listOf(LocationId("loc-square")),
    )

    val square: Location = Location(
        id = LocationId("loc-square"),
        name = "Place des Vosges",
        description = "Gas lamps and wet stone.",
        connections = listOf(LocationId("loc-rooftop")),
    )

    val marinette: CharacterDefinition = CharacterDefinition(
        id = CharacterId("ch-marina"),
        name = "Marinette",
        description = "A courier with a secret and tired eyes.",
        personality = "brave, deflects with jokes",
        storyRole = CharacterRole.PROTAGONIST,
        startingLocationId = paris.id,
    )

    val adrien: CharacterDefinition = CharacterDefinition(
        id = CharacterId("ch-adrien"),
        name = "Adrien",
        description = "Rich, gentle, extremely followed.",
        storyRole = CharacterRole.NPC,
        startingLocationId = square.id,
    )

    val guardianFact: Fact = Fact(
        id = FactId("fact-guardian"),
        subject = "the guardians",
        predicate = "are watching",
        description = "The guardians have been following the courier for a week.",
        secret = true,
    )

    fun scenario(): StartingScenario = StartingScenario(
        id = "scn-rooftop",
        title = "Rooftop Meeting",
        tagline = "Two people, one secret, and a long way down.",
        startTime = StoryTime(day = 1, hour = 21, minute = 0),
        startLocationId = paris.id,
        focusCharacterId = marinette.id,
        castCharacterIds = listOf(marinette.id, adrien.id),
        objectiveIds = listOf("obj-trust"),
        initialSceneId = "scn-rooftop-scene",
        openingChoices = listOf(
            SceneChoice(
                id = "open-climb",
                label = "Tırmandığını söyle",
                description = "Dürüst ol.",
                effects = listOf(
                    EventEffect.ChangeRelationship(
                        sourceId = marinette.id,
                        targetId = adrien.id,
                        delta = dev.charaly.runtime.domain.RelationshipDelta(trust = 5),
                    ),
                ),
                outcomeNote = "Marinette omuz silkti.",
            ),
            SceneChoice(
                id = "open-lie",
                label = "Asansörle çıktığını söyle",
                description = "Yalan söyle.",
                effects = listOf(
                    EventEffect.SetVariable(playerHelped, "no"),
                    EventEffect.ChangeRelationship(
                        sourceId = marinette.id,
                        targetId = adrien.id,
                        delta = dev.charaly.runtime.domain.RelationshipDelta(trust = -5, tension = 5),
                    ),
                ),
            ),
        ),
        eventIds = listOf("evt-opening"),
    )

    fun scene(): SceneTemplate = SceneTemplate(
        id = "scn-rooftop-scene",
        name = "Rooftop Meeting",
        description = "Wind, tiles, and a conversation nobody planned.",
        atmosphere = "tense",
        locationId = paris.id,
        participantCharacterIds = listOf(marinette.id),
        objective = "Find out who she is running from",
        entryConditions = emptyList(),
        exitConditions = listOf(EventCondition.VariableEquals(playerHelped, "yes")),
        choices = listOf(
            SceneChoice(
                id = "choice-help",
                label = "Ona yardım et",
                conditions = emptyList(),
                effects = listOf(
                    EventEffect.SetVariable(playerHelped, "yes"),
                    EventEffect.ChangeRelationship(
                        sourceId = marinette.id,
                        targetId = adrien.id,
                        delta = dev.charaly.runtime.domain.RelationshipDelta(trust = 15, affinity = 10),
                    ),
                    EventEffect.AdvanceThread(ThreadId("thr-trust"), stage = 1),
                ),
                outcomeNote = "Yardımın karşılığını henüz görmedin.",
            ),
            SceneChoice(
                id = "choice-betray",
                label = "Onu ele ver",
                conditions = emptyList(),
                effects = listOf(
                    EventEffect.SetVariable(playerHelped, "no"),
                    EventEffect.ChangeRelationship(
                        sourceId = marinette.id,
                        targetId = adrien.id,
                        delta = dev.charaly.runtime.domain.RelationshipDelta(trust = -30, tension = 25),
                    ),
                ),
                outcomeNote = "Bu karar geri alınamaz.",
            ),
            SceneChoice(
                id = "choice-guarded",
                label = "Sessiz kal",
                conditions = listOf(EventCondition.RelationshipTrustAtLeast(marinette.id, adrien.id, 40)),
                effects = listOf(EventEffect.SetVariable(playerHelped, "maybe")),
            ),
        ),
    )

    fun objective(): PackObjective = PackObjective(
        id = "obj-trust",
        title = "Kazanılan Güven",
        description = "Marinette'in sana güvenmesini sağla.",
        threadId = ThreadId("thr-trust"),
        relatedCharacterIds = listOf(marinette.id),
        scenarioIds = listOf("scn-rooftop"),
        completionConditions = listOf(EventCondition.VariableEquals(playerHelped, "yes")),
        failureConditions = listOf(EventCondition.VariableEquals(playerHelped, "no")),
        onComplete = listOf(
            EventEffect.GrantKnowledge(adrien.id, FactId("fact-guardian"), via = "güven"),
        ),
        priority = 70,
    )

    fun event(): PackEventDefinition = PackEventDefinition(
        id = "evt-opening",
        title = "Rüzgâr yön değiştirdi",
        description = "Sahnenin havasını kuran küçük bir olay.",
        trigger = EventTrigger.StoryStart(),
        effects = listOf(EventEffect.ChangeActivity(marinette.id, CharacterActivity.TALKING)),
        participants = listOf(marinette.id),
        locationId = paris.id,
    )

    fun conditionalEvent(): PackEventDefinition = PackEventDefinition(
        id = "evt-trust-payoff",
        title = "Güvenin karşılığı",
        trigger = EventTrigger.WhenConditionMet(pollEveryMinutes = 15),
        conditions = listOf(EventCondition.VariableEquals(playerHelped, "yes")),
        effects = listOf(
            EventEffect.GrantKnowledge(marinette.id, FactId("fact-guardian"), via = "itiraf"),
        ),
        threadChanges = listOf(
            dev.charaly.runtime.domain.ThreadChange(ThreadId("thr-trust"), stage = 2),
        ),
    )

    fun pack(
        id: String = "pack.paris.afterdark",
        version: String = "1.0.0",
        withObjectives: Boolean = true,
        withScenes: Boolean = true,
    ): StoryPack = StoryPack(
        id = StoryPackId(id),
        title = "Paris After Dark",
        description = "A fan-made night above Paris.",
        author = "Charaly",
        packVersion = version,
        publisher = "Charaly Packs",
        license = "CC BY-NC 4.0",
        language = "tr",
        entryPoint = "scn-rooftop",
        minimumEngineVersion = "0.8.0",
        tags = listOf("aksiyon"),
        characters = listOf(marinette, adrien),
        locations = listOf(paris, square),
        initialWorldState = InitialWorldState(
            startTime = StoryTime(day = 1, hour = 21, minute = 0),
            variables = listOf(
                dev.charaly.runtime.domain.WorldVariable(
                    key = playerHelped,
                    type = dev.charaly.runtime.domain.WorldVariableType.BOOLEAN,
                    value = "unset",
                    description = "Whether the player helped",
                ),
            ),
            startLocations = mapOf(marinette.id to paris.id, adrien.id to square.id),
        ),
        initialRelationships = listOf(
            Relationship(
                sourceId = marinette.id,
                targetId = adrien.id,
                trust = 20,
                familiarity = 10,
            ),
        ),
        initialKnowledge = InitialKnowledge(
            facts = listOf(guardianFact),
            characterKnowledge = mapOf(marinette.id to listOf(guardianFact.id.value)),
        ),
        initialStoryThreads = listOf(
            StoryThread(
                id = ThreadId("thr-trust"),
                title = "Güven",
                status = StoryThreadStatus.ACTIVE,
                stage = 0,
                involvedCharacterIds = listOf(marinette.id, adrien.id),
                relevantLocationIds = listOf(paris.id),
            ),
        ),
        events = listOf(event(), conditionalEvent()),
        scenarios = listOf(scenario()),
        personas = listOf(
            PersonaTemplate(
                id = "per-stranger",
                name = "Yabancı",
                tagline = "Kimse seni tanımıyor",
                rolePrompt = "Sen bu şehirde yeni birisin.",
                startingKnowledgeFactIds = listOf(),
                abilities = listOf("İz sürmek"),
                restrictions = listOf("Fransızcan bozuk"),
            ),
        ),
        scenes = if (withScenes) listOf(scene()) else emptyList(),
        objectives = if (withObjectives) listOf(objective()) else emptyList(),
        world = PackWorld(
            universe = "Miraculous",
            era = "Modern Paris",
            geography = "Çatılar ve meydanlar",
            rules = listOf("Kimlikler gizlidir"),
        ),
        settings = PackSettings(minimumCapability = "2 GB RAM"),
    )

    /** A pack that passes validation with no warnings at all. */
    fun cleanPack(): StoryPack = pack()

    fun sampleArchive(
        pack: StoryPack = pack(),
        localization: Map<String, Map<String, String>> = emptyMap(),
    ): ByteArray {
        val content = StoryPackCodec.encode(pack)
        val files = LinkedHashMap<String, ByteArray>()
        content.forEach { (path, text) -> files[path] = text.toByteArray(Charsets.UTF_8) }
        val manifest = PackManifestBuilder.build(pack, files, localization)
        val all = LinkedHashMap<String, ByteArray>()
        all[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
            .encodeToString(PackManifest.serializer(), manifest)
            .toByteArray(Charsets.UTF_8)
        all.putAll(files)
        localization.forEach { (language, table) ->
            all[PackManifestBuilder.localizationPath(language)] =
                LocalizationCodec.encode(StoryPackFormat.json, table).toByteArray(Charsets.UTF_8)
        }
        return StoryPackArchive.write(all)
    }

    fun localization(): Map<String, Map<String, String>> = mapOf(
        "tr" to mapOf(
            PackLocalizer.META_TITLE to "Paris Gecesi",
            PackLocalizer.META_PREMISE to "Paris'in üzerinde bir gece.",
            PackLocalizer.META_DESCRIPTION to "Hayran yapımı bir gece.",
            "character.ch-marina.name" to "Marinette",
        ),
        "en" to mapOf(
            PackLocalizer.META_TITLE to "Paris After Dark",
            PackLocalizer.META_PREMISE to "One night above Paris.",
            PackLocalizer.META_DESCRIPTION to "A fan-made night.",
            "character.ch-marina.name" to "Marinette",
        ),
    )
}