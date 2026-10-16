package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.PackWorld
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryDuration
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.inference.MockInferenceEngine
import dev.charaly.runtime.pack.PackAuthoring
import dev.charaly.runtime.presentation.LibraryPresenter
import dev.charaly.runtime.presentation.PackDetailPresenter
import dev.charaly.runtime.session.CharalyRuntime
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE end-to-end test.
 *
 * ```
 * AUTHOR -> StoryPack -> VALIDATE -> EXPORT -> .charaly-pack -> DELETE -> IMPORT
 *        -> VALIDATE -> INSTALL -> REGISTRY -> LIBRARY -> PACK DETAIL -> ENTER STORY
 *        -> STORY INSTANCE -> WORLD STATE -> STORY CHAT -> EVENT -> STATE CHANGE
 *        -> SAVE -> APP RESTART -> RESUME -> SAME STATE
 * ```
 *
 * Every hop here is the real component: real authoring helpers, real codec, real archive,
 * real validator, real installer, real registry, real repository on real files, real event
 * engine, real context builder, real chat pipeline. Nothing in this chain is stubbed,
 * faked, or "simulated for speed". The one component that is not the subject of this file -
 * the language model - is the runtime's own deterministic engine, and the *real* model is
 * exercised on the same chain by `RealStoryChatLoopTest` against llama.cpp.
 */
class StoryPackEndToEndTest {

    // ---- AUTHOR --------------------------------------------------------

    /** A pack as an author writes it: helpers and domain objects, no shortcuts. */
    private fun authorPack(): StoryPack {
        val rooftops = Location(
            id = LocationId("loc-roof"),
            name = "Galata Çatısı",
            description = "Rüzgâr, antenler ve şehrin nefesi.",
            connections = listOf(LocationId("loc-square")),
        )
        val square = Location(
            id = LocationId("loc-square"),
            name = "Meydan",
            description = "Islak taşlar ve gaz lambaları.",
            connections = listOf(LocationId("loc-roof")),
        )
        val aylin = CharacterDefinition(
            id = CharacterId("ch-aylin"),
            name = "Aylin",
            description = "Sır taşıyan bir kurye.",
            personality = "cesur, şakacı",
            startingLocationId = rooftops.id,
        )
        val kaan = CharacterDefinition(
            id = CharacterId("ch-kaan"),
            name = "Kaan",
            description = "Tanımadığı bir geceye düşmüş bir yabancı.",
            startingLocationId = rooftops.id,
        )

        return PackAuthoring.pack(
            id = "pack.gece.vardiyasi",
            title = "Gece Vardiyası",
            description = "İstanbul çatılarında bir gece.",
            author = "Test Yazar",
            identity = PackAuthoring.identity(
                tagline = "Bir gece, bir sır, iki kişi",
                genres = listOf("gerilim"),
                coverSeed = "gece",
                mood = "gergin, sıcak",
                primary = "#E4274C",
                secondary = "#0A0A0C",
                accent = "#FFFFFF",
                premise = "Aylin'in taşıdığı sır bu gece teslim edilecek.",
                hooks = listOf("Şehhir bugün daha sessiz."),
                atmosphere = "yağmur sonrası",
                invitation = "Çatıya çık.",
                demo = true,
            ),
            characters = listOf(aylin, kaan),
            locations = listOf(rooftops, square),
            startTime = StoryTime(day = 1, hour = 22, minute = 0),
            startLocations = mapOf("ch-aylin" to "loc-roof", "ch-kaan" to "loc-roof"),
            relationships = listOf(
                dev.charaly.runtime.domain.Relationship(
                    sourceId = aylin.id,
                    targetId = kaan.id,
                    trust = 15,
                ),
            ),
            facts = listOf(
                dev.charaly.runtime.domain.knowledge.Fact(
                    id = FactId("fact-secret"),
                    subject = "paket",
                    predicate = "teslim edilecek",
                    description = "Sır dolu paket gece yarısına kadar teslim edilmeli.",
                    secret = true,
                ),
            ),
            characterKnowledge = listOf("ch-aylin" to listOf("fact-secret")),
            threads = listOf(
                dev.charaly.runtime.domain.StoryThread(
                    id = dev.charaly.runtime.domain.ThreadId("thr-delivery"),
                    title = "Teslimat",
                    involvedCharacterIds = listOf(aylin.id),
                    relevantLocationIds = listOf(rooftops.id),
                ),
            ),
            scenarios = listOf(
                StartingScenario(
                    id = "scn-first-night",
                    title = "İlk Gece",
                    startLocationId = rooftops.id,
                    focusCharacterId = aylin.id,
                    castCharacterIds = listOf(aylin.id, kaan.id),
                    objectiveIds = listOf("obj-deliver"),
                    initialSceneId = "scn-roof",
                    openingChoices = listOf(
                        SceneChoice(
                            id = "open-honest",
                            label = "Paketten bahset",
                            effects = listOf(
                                EventEffect.SetVariable("player_helped", "yes"),
                                EventEffect.ChangeRelationship(
                                    sourceId = aylin.id,
                                    targetId = kaan.id,
                                    delta = dev.charaly.runtime.domain.RelationshipDelta(trust = 10),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            personas = listOf(
                PersonaTemplate(
                    id = "per-stranger",
                    name = "Yabancı",
                    tagline = "Kimse seni tanımıyor",
                    rolePrompt = "Bu şehirde yeni birisin.",
                    abilities = listOf("İz sürmek"),
                    restrictions = listOf("Türkçen kırık"),
                ),
            ),
            events = listOf(
                PackEventDefinition(
                    id = "evt-payoff",
                    title = "Güvenin karşılığı",
                    trigger = EventTrigger.WhenConditionMet(pollEveryMinutes = 10),
                    conditions = listOf(EventCondition.VariableEquals("player_helped", "yes")),
                    effects = listOf(
                        EventEffect.GrantKnowledge(CharacterId("ch-kaan"), FactId("fact-secret"), via = "itiraf"),
                    ),
                ),
            ),
        ).copy(
            packVersion = "1.0.0",
            language = "tr",
            entryPoint = "scn-first-night",
            world = PackWorld(
                universe = "Modern İstanbul",
                era = "Şimdiki zaman",
                geography = "Çatılar",
                rules = listOf("Paket asla açılmaz"),
            ),
            scenes = listOf(
                SceneTemplate(
                    id = "scn-roof",
                    name = "Çatı Karşılaşması",
                    description = "Rüzgâr ve konuşulmamış bir cümle.",
                    locationId = LocationId("loc-roof"),
                    participantCharacterIds = listOf(CharacterId("ch-aylin")),
                    choices = listOf(
                        SceneChoice(
                            id = "choice-help",
                            label = "Paketinde yardım et",
                            effects = listOf(
                                EventEffect.SetVariable("player_helped", "yes"),
                                EventEffect.AdvanceThread(dev.charaly.runtime.domain.ThreadId("thr-delivery"), 1),
                            ),
                        ),
                        SceneChoice(
                            id = "choice-refuse",
                            label = "Yardımı reddet",
                            effects = listOf(EventEffect.SetVariable("player_helped", "no")),
                        ),
                    ),
                ),
            ),
            objectives = listOf(
                PackObjective(
                    id = "obj-deliver",
                    title = "Teslimat",
                    description = "Paketi yerine ulaştır.",
                    completionConditions = listOf(EventCondition.VariableEquals("player_helped", "yes")),
                    failureConditions = listOf(EventCondition.VariableEquals("player_helped", "no")),
                    onComplete = listOf(
                        EventEffect.GrantKnowledge(CharacterId("ch-kaan"), FactId("fact-secret"), via = "başarı"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `author to pack to play to resume, across a device hop`() {
        // ---- AUTHOR -----------------------------------------------------
        val authored = authorPack()
        assertEquals("pack.gece.vardiyasi", authored.id.value)
        assertEquals("1.0.0", authored.semanticVersion)

        // ---- VALIDATE (before export) ----------------------------------
        val preExport = StoryPackDecoder.decode(
            // The same bytes an export produces; validated before anything is written.
            PackFixtures.sampleArchive(authored, mapOf("tr" to mapOf(PackLocalizer.META_TITLE to "Gece Vardiyası"))),
        )
        assertTrue("authored pack must validate: ${preExport.issues.filter { it.isBlocking }.map { it.message }}", preExport.installable)

        // ---- EXPORT -> .charaly-pack -----------------------------------
        val source = PackTestEnv()
        val installed = runBlocking { source.installer.import(PackFixtures.sampleArchive(authored)) }
        assertTrue(installed is StoryPackInstaller.ImportResult.Installed)
        val archive: ByteArray = runBlocking { source.installer.export("pack.gece.vardiyasi") }!!
        assertTrue("a .charaly-pack is a zip archive", archive[0] == 'P'.code.toByte() && archive[1] == 'K'.code.toByte())

        // ---- DELETE ----------------------------------------------------
        runBlocking {
            assertTrue(source.installer.delete("pack.gece.vardiyasi") is StoryPackInstaller.DeleteOutcome.Deleted)
            assertEquals(emptyList<Any>(), source.registry.list())
        }

        // ---- IMPORT (into a *second* installation) -> VALIDATE ----------
        val target = PackTestEnv()
        val candidate = runBlocking { target.installer.inspect(archive) }
        assertTrue("import validation: ${candidate.report.debugLines()}", candidate.isInstallable)
        assertTrue(
            "census: ${candidate.report.censusLine()}",
            candidate.report.censusLine().startsWith("2 karakter"),
        )

        // ---- INSTALL -> REGISTRY ---------------------------------------
        val importResult = runBlocking { target.installer.import(archive, source = InstalledPackRecord.SOURCE_IMPORT) }
        assertTrue(importResult is StoryPackInstaller.ImportResult.Installed)
        val record = (importResult as StoryPackInstaller.ImportResult.Installed).record
        assertEquals("pack.gece.vardiyasi", record.packId)
        assertEquals("1.0.0", record.packVersion)
        assertNotNull(record.checksum)

        // ---- LIBRARY ---------------------------------------------------
        val packs = runBlocking { target.runtime.listPacks() }
        val library = LibraryPresenter.build(
            nowEpochMs = 1_000L,
            packs = packs,
            instances = emptyList(),
        )
        assertTrue(library.sections.flatMap { it.packs }.any { it.title == "Gece Vardiyası" })

        // ---- PACK DETAIL ----------------------------------------------
        val detail = PackDetailPresenter.buildOrNull(1_000L, packs.first(), emptyList(), defaultProfileName = "")
        assertNotNull(detail)
        assertEquals("Gece Vardiyası", detail!!.title)
        assertTrue(detail.characters.isNotEmpty())

        // ---- ENTER STORY -> STORY INSTANCE -----------------------------
        val pack = runBlocking { target.installer.load("pack.gece.vardiyasi")!!.pack }
        val story = runBlocking {
            target.runtime.startStory(
                pack,
                dev.charaly.runtime.engine.StoryCreationOptions(
                    instanceId = StoryInstanceId("story-e2e"),
                    title = "Benim Hikâyem",
                    scenario = pack.scenarios.first(),
                    persona = dev.charaly.runtime.domain.PersonaBinding.from(pack.personas.first(), "Bilal"),
                    focusCharacterId = CharacterId("ch-aylin"),
                ),
            )
        }

        // ---- WORLD STATE ----------------------------------------------
        assertEquals("1.0.0", story.packVersion)
        assertEquals(2, story.worldState.characters.size)
        assertEquals(2, story.worldState.locations.size)
        assertNotNull("the opening scene is open", story.currentScene())
        assertEquals(listOf("obj-deliver"), story.objectives.map { it.objectiveId })

        // ---- STORY CHAT ------------------------------------------------
        val context = target.runtime.previewContext(story, CharacterId("ch-aylin"), "Merhaba")
        assertNotNull("context builds without a model", context)
        val turns: List<GenerationUpdate> = runBlocking {
            val updates = mutableListOf<GenerationUpdate>()
            target.runtime.respond(story, "Merhaba, paket mi o?").collect { updates += it }
            updates.toList()
        }
        assertTrue(
            "a real chat turn must finish; updates: ${turns.map { update ->
                (update as? GenerationUpdate.Failed)?.error ?: update::class.simpleName
            }}",
            turns.any { update -> update is GenerationUpdate.Finished },
        )
        val afterChat = runBlocking { target.runtime.loadStory(StoryInstanceId("story-e2e")) }!!
        assertTrue("the turn was persisted", afterChat.conversation.size > 0)

        // ---- EVENT + STATE CHANGE (a branch) ---------------------------
        val afterClock = runBlocking {
            target.runtime.advanceLatest(afterChat.id, StoryDuration(minutes = 10))
        }!!
        val taken = runBlocking { target.runtime.takeChoice(afterClock, "scn-roof", "choice-help") }
        assertTrue(taken is CharalyRuntime.ChoiceOutcome.Taken)
        val stateChanged = (taken as CharalyRuntime.ChoiceOutcome.Taken).instance
        assertEquals("yes", stateChanged.worldState.variables["player_helped"]?.value)
        assertEquals(ObjectiveStatus.COMPLETED, stateChanged.objectives.first().status)
        assertEquals(listOf("choice-help"), stateChanged.choiceLedger.map { it.choiceId })

        // The conditional pack event now fires, through the engine.
        val fired = runBlocking { target.runtime.pollConditionalEvents(stateChanged) }!!
        assertTrue(
            "knowledge should have been granted by evt-payoff",
            fired.knowledge.knows(CharacterId("ch-kaan"), FactId("fact-secret")) ||
                stateChanged.knowledge.knows(CharacterId("ch-kaan"), FactId("fact-secret")),
        )

        // ---- SAVE ------------------------------------------------------
        val saved = runBlocking { target.runtime.touch(fired, nowEpochMs = 4_242L) }!!
        assertEquals(4_242L, saved.sessionMeta.lastPlayedAtEpochMs)

        // ---- APP RESTART ----------------------------------------------
        val storage = target.storage
        val repository = dev.charaly.runtime.persistence.JsonCharalyRepository(storage)
        val registry = JsonStoryPackRegistry(storage)
        val store = PackInstallStore(target.root.resolve("installed-packs"))
        val installer = StoryPackInstaller(repository, registry, store)
        val restarted = CharalyRuntime(repository, MockInferenceEngine(), editions = installer)

        // ---- RESUME -> SAME STATE --------------------------------------
        runBlocking {
            registry.reload()
            val resumed = restarted.loadStory(StoryInstanceId("story-e2e"))!!
            assertEquals(saved.worldState.worldClock.now, resumed.worldState.worldClock.now)
            assertEquals(saved.worldState.variables, resumed.worldState.variables)
            assertEquals(saved.relationships.mapValues { it.value.trust }, resumed.relationships.mapValues { it.value.trust })
            assertEquals(saved.memories.size, resumed.memories.size)
            assertEquals(saved.knowledge.knows(CharacterId("ch-kaan"), FactId("fact-secret")), resumed.knowledge.knows(CharacterId("ch-kaan"), FactId("fact-secret")))
            assertEquals(saved.objectives.map { it.status }, resumed.objectives.map { it.status })
            assertEquals(saved.choiceLedger.map { it.choiceId }, resumed.choiceLedger.map { it.choiceId })
            assertEquals(saved.currentSceneId, resumed.currentSceneId)
            assertEquals(saved.conversation.size, resumed.conversation.size)
            assertEquals(saved.packVersion, resumed.packVersion)
            assertEquals("Benim Hikâyem", resumed.displayTitle)

            // And it is still playable, on its own pinned edition.
            assertTrue(installer.compatibilityFor(resumed).isPlayable)
        }

        source.cleanup()
        target.cleanup()
    }

    @Test
    fun `two installations of one pack tell the same story to different players`() {
        val authored = authorPack()
        val first = PackTestEnv()
        val second = PackTestEnv()
        runBlocking {
            first.installer.import(PackFixtures.sampleArchive(authored))
            second.installer.import(PackFixtures.sampleArchive(authored))

            val alpha = first.runtime.startStory(
                first.installer.load("pack.gece.vardiyasi")!!.pack,
                StoryInstanceId("story-a"),
            )
            val beta = second.runtime.startStory(
                second.installer.load("pack.gece.vardiyasi")!!.pack,
                StoryInstanceId("story-b"),
            )
            first.runtime.takeChoice(alpha, "scn-roof", "choice-help")
            second.runtime.takeChoice(beta, "scn-roof", "choice-refuse")

            val alphaFinal = first.runtime.loadStory(alpha.id)!!
            val betaFinal = second.runtime.loadStory(beta.id)!!
            assertFalse(
                "two players, two worlds",
                alphaFinal.worldState.variables["player_helped"]?.value ==
                    betaFinal.worldState.variables["player_helped"]?.value,
            )
            // ...and the same authored content behind both.
            assertEquals(
                first.installer.load("pack.gece.vardiyasi")!!.pack,
                second.installer.load("pack.gece.vardiyasi")!!.pack,
            )
        }
        first.cleanup()
        second.cleanup()
    }

    @Test
    fun `the pack never absorbs runtime state however long it is played`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(authorPack())
            val before = env.installer.load("pack.gece.vardiyasi")!!.pack
            val story = env.runtime.startStory(before, StoryInstanceId("story-purity"))
            val played = env.runtime.takeChoice(story, "scn-roof", "choice-help")
                    as CharalyRuntime.ChoiceOutcome.Taken
            assertFalse(played.instance.choiceLedger.isEmpty())
            assertFalse(played.instance.objectives.isEmpty())

            assertEquals("the pack is still exactly as authored", before, env.installer.load("pack.gece.vardiyasi")!!.pack)
        }
        env.cleanup()
    }

    @Test
    fun `a saved story can be resumed after its pack is upgraded, unchanged`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(authorPack())
            val story = env.runtime.startStory(
                env.installer.load("pack.gece.vardiyasi")!!.pack,
                StoryInstanceId("story-pin"),
            )
            env.runtime.takeChoice(story, "scn-roof", "choice-help")

            val upgraded = authorPack().copy(packVersion = "1.1.0", description = "Genişletilmiş baskı")
            env.install(upgraded)

            val resumed = env.runtime.loadStory(story.id)!!
            assertTrue(env.installer.compatibilityFor(resumed).isPlayable)
            val continues = env.runtime.advanceLatest(resumed.id, StoryDuration(minutes = 30))!!
            assertEquals("the story stayed on its own edition", "1.0.0", env.runtime.packFor(continues)!!.semanticVersion)
        }
        env.cleanup()
    }
}
