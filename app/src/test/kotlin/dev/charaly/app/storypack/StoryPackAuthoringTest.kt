package dev.charaly.app.storypack

import dev.charaly.app.ui.packstore.AuthoringCompiler
import dev.charaly.app.ui.packstore.AuthoringDraft
import dev.charaly.app.ui.packstore.BranchDraft
import dev.charaly.app.ui.packstore.CharacterDraft
import dev.charaly.app.ui.packstore.EffectDraft
import dev.charaly.app.ui.packstore.EffectKind
import dev.charaly.app.ui.packstore.EventDraft
import dev.charaly.app.ui.packstore.LoreDraft
import dev.charaly.app.ui.packstore.LocationDraft
import dev.charaly.app.ui.packstore.ObjectiveDraft
import dev.charaly.app.ui.packstore.PersonaDraft
import dev.charaly.app.ui.packstore.RelationshipDraft
import dev.charaly.app.ui.packstore.ScenarioDraft
import dev.charaly.app.ui.packstore.SceneDraft
import dev.charaly.app.ui.packstore.TextDraft
import dev.charaly.app.ui.packstore.ThreadDraft
import dev.charaly.app.ui.packstore.stableSlug
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.WorldVariableSet
import dev.charaly.runtime.engine.BranchEngine
import dev.charaly.runtime.engine.EventApplication
import dev.charaly.runtime.engine.EventEngine
import dev.charaly.runtime.engine.ObjectiveEngine
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.storypack.InMemoryStoryPackRegistry
import dev.charaly.runtime.storypack.PackInstallStore
import dev.charaly.runtime.storypack.StoryPackDecoder
import dev.charaly.runtime.storypack.StoryPackInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE AUTHORING PIPELINE, END TO END.
 *
 * ## What these tests are for
 *
 * The wizard's whole promise is "what you write becomes a real pack". That claim has four
 * moving parts - the draft model, the compiler, the validators and the `.charaly-pack`
 * format - and each one can fail invisibly: a slug collision merging two characters, a
 * branch whose variable no objective watches, an export the installer would refuse. So the
 * tests drive the *finished* artifacts, not the intermediate ones: a draft is compiled,
 * reviewed by the real validators, exported through the real installer's `exportPack`,
 * decoded by the real `StoryPackDecoder`, and - in the sharpest test - actually *played* far
 * enough to take a branch and complete an objective through `StoryInstanceFactory` and
 * `BranchEngine`.
 *
 * ## Why the empty-draft expectations are exact strings
 *
 * Because the findings are product copy: "Paketin kadrosu boş" is what an author reads when
 * they race ahead. A test that only asserted "some error exists" would let that sentence
 * quietly become an engineer's log line.
 */
class StoryPackAuthoringTest {

    // ==================================================================
    // A draft that is actually a story
    // ==================================================================

    /**
     * A complete, small pack: a harbour town, two people, one scene with three real forks,
     * one objective, one thread, one conditional event.
     *
     * Deliberately *wired*: the first branch writes `fener_teslim_edildi` and the objective
     * watches exactly that - the property that makes an authored pack playable rather than
     * decorative, and the property the last test proves in the engine.
     */
    private fun filledDraft(): AuthoringDraft = AuthoringDraft(
        title = "Son Fener",
        premise = "Feneri kaybolmuş bir liman kasabası.",
        description = "Kasaba fenersiz kalınca herkesin bildiği bir sır ortaya çıkar.",
        author = "Deniz Kaya",
        publisher = "Kıyı Yayınları",
        tagsRaw = "gizem, kasaba",
        language = "tr",
        coverSeed = "son-fener-kapak",
        version = "1.2.0",
        universe = "Karadeniz kıyısında bir kasaba",
        era = "Çağdaş",
        geography = "Bir liman kasabası ve çevresi",
        rules = listOf(TextDraft(rowId = "r-1", text = "Fener her akşam yakılır.")),
        worldFacts = listOf(TextDraft(rowId = "wf-1", text = "Kasabada elektrik yok.")),
        locations = listOf(
            LocationDraft(rowId = "l-iskele", name = "İskele", description = "Tahta bir iskele.", interior = false),
            LocationDraft(rowId = "l-fener", name = "Fener odası", description = "Fenerin içi.", interior = true),
        ),
        lore = listOf(
            LoreDraft(rowId = "lore-1", title = "Fenerin geçmişi", content = "Fener yüz yıldır yanıyor."),
        ),
        characters = listOf(
            CharacterDraft(
                rowId = "c-deniz",
                name = "Deniz",
                tagline = "Kayıkçı",
                description = "Kasabanın kayıkçısı.",
                personality = "Sabırlı",
                startLocationRowId = "l-iskele",
                role = CharacterRole.PROTAGONIST,
            ),
            CharacterDraft(
                rowId = "c-ayse",
                name = "Ayşe",
                tagline = "Bakkal",
                description = "Bakkalın sahibi.",
                personality = "Meraklı",
                startLocationRowId = "l-fener",
                role = CharacterRole.NPC,
            ),
        ),
        personas = listOf(
            PersonaDraft(
                rowId = "p-1",
                name = "Çaylak gazeteci",
                tagline = "Kasabaya yeni geldi.",
                rolePrompt = "Kasabaya yeni gelen bir gazetecisin.",
                abilitiesRaw = "not almak, soru sormak",
                restrictionsRaw = "silah taşıyamaz",
            ),
        ),
        relationships = listOf(
            RelationshipDraft(
                rowId = "rel-1",
                sourceRowId = "c-deniz",
                targetRowId = "c-ayse",
                typeName = "FRIEND",
                trust = 60,
                affinity = 70,
                tension = 10,
                familiarity = 40,
            ),
        ),
        scenarios = listOf(
            ScenarioDraft(
                rowId = "s-1",
                title = "İlk Gün",
                tagline = "Fenerin söndüğü sabah.",
                startLocationRowId = "l-iskele",
                focusCharacterRowId = "c-deniz",
                castRowIds = setOf("c-deniz", "c-ayse"),
            ),
        ),
        scenes = listOf(
            SceneDraft(
                rowId = "sc-1",
                name = "İskelede buluşma",
                description = "Deniz ve Ayşe iskelede konuşur.",
                atmosphere = "gergin",
                locationRowId = "l-iskele",
                participants = setOf("c-deniz", "c-ayse"),
                branches = listOf(
                    BranchDraft(
                        rowId = "b-1",
                        label = "Feneri kıyıya götür",
                        effect = EffectDraft(
                            kind = EffectKind.SET_VARIABLE,
                            variableKey = "fener_teslim_edildi",
                            variableValue = "evet",
                        ),
                    ),
                    BranchDraft(
                        rowId = "b-2",
                        label = "Ayşe'ye yardım et",
                        effect = EffectDraft(
                            kind = EffectKind.TRUST_SHIFT,
                            sourceRowId = "c-deniz",
                            targetRowId = "c-ayse",
                            trustDelta = 15,
                        ),
                    ),
                    BranchDraft(
                        rowId = "b-3",
                        label = "Aramayı genişlet",
                        effect = EffectDraft(kind = EffectKind.ADVANCE_THREAD, threadRowId = "t-1"),
                    ),
                ),
            ),
        ),
        objectives = listOf(
            ObjectiveDraft(
                rowId = "o-1",
                title = "Feneri sahibine ulaştır",
                description = "Kayıp feneri bul ve sahibine ver.",
                completionKey = "fener_teslim_edildi",
                completionValue = "evet",
                failureKey = "fener_kayboldu",
                failureValue = "evet",
            ),
        ),
        threads = listOf(
            ThreadDraft(rowId = "t-1", title = "Kayıp fener", description = "Fener kim tarafından alındı?"),
        ),
        event = EventDraft(
            rowId = "e-1",
            title = "Fener bulundu",
            whenKey = "fener_bulundu",
            whenValue = "evet",
            effect = EffectDraft(
                kind = EffectKind.TRUST_SHIFT,
                sourceRowId = "c-ayse",
                targetRowId = "c-deniz",
                trustDelta = 10,
            ),
        ),
    )

    // ==================================================================
    // 1. A filled draft is a real, installable pack
    // ==================================================================

    /**
     * The acceptance criterion: compile -> review -> export -> decode -> installable.
     *
     * `exportPack` and `StoryPackDecoder.decode` are the *installer's own* halves, so this
     * is the same gauntlet a sideloaded `.charaly-pack` faces - if this passes, "Kütüphaneye
     * Yükle" on step 6 has nothing left to discover about the bytes.
     */
    @Test
    fun `a filled draft compiles clean and exports as an installable archive`() {
        val pack = AuthoringCompiler.compile(filledDraft())

        val report = AuthoringCompiler.review(pack)
        assertEquals(
            "a complete draft must compile to zero critical findings: " + report.debugLines(),
            emptyList<String>(),
            report.errors.map { it.message },
        )
        assertTrue(report.isInstallable)

        val root = java.nio.file.Files.createTempDirectory("charaly-authoring-").toFile()
        try {
            val installer = installerFor(root)
            val bytes = installer.exportPack(pack)
            assertTrue("the export produced no bytes", bytes.isNotEmpty())

            val decoded = dev.charaly.runtime.storypack.StoryPackDecoder.decode(bytes)
            assertTrue(
                "the exported archive must decode installable: " + decoded.issues.map { it.message },
                decoded.installable,
            )
            assertEquals("Son Fener", decoded.pack?.title)
            assertEquals("1.2.0", decoded.pack?.semanticVersion)
            assertEquals(1, decoded.pack?.scenes?.size)
            assertEquals(3, decoded.pack?.scenes?.first()?.choices?.size)
        } finally {
            root.deleteRecursively()
        }
    }

    // ==================================================================
    // 2. An empty or half-written draft produces the expected findings
    // ==================================================================

    /**
     * A title and nothing else: exactly the three "nothing to play" findings, worded for a
     * person. No extras - a review that cries wolf on an empty form teaches authors to skip
     * the review step.
     */
    @Test
    fun `an empty draft reports exactly the missing content findings in turkish`() {
        val pack = AuthoringCompiler.compile(AuthoringDraft(title = "Boş Paket"))
        val report = AuthoringCompiler.review(pack)

        assertEquals(
            listOf(
                "Paketin kadrosu boş. Kimse yoksa hikâye başlayamaz.",
                "Paketin hiç mekânı yok. Geçilecek bir dünya yok.",
                "Pakette hiç açılış senaryosu yok; hikâyenin nasıl başlayacağı tanımlı değil.",
            ),
            report.errors.map { it.message },
        )
    }

    /** The one required field refuses compilation in Turkish rather than in a stack trace. */
    @Test
    fun `a draft without a title refuses to compile with the wizard's own sentence`() {
        val failure = runCatching { AuthoringCompiler.compile(AuthoringDraft()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(AuthoringCompiler.MISSING_TITLE, failure?.message)
    }

    /**
     * A branch whose effect is half-written compiles to a choice with no effect - which the
     * branching validator then reports as the sharp thing it is: "this choice changes
     * nothing". Rescuing the branch with a fabricated variable would hide the mistake.
     */
    @Test
    fun `a half-written branch is reported as a choice that changes nothing`() {
        val draft = AuthoringDraft(
            title = "Yarım Paket",
            locations = listOf(LocationDraft(rowId = "l", name = "İskele")),
            characters = listOf(CharacterDraft(rowId = "c", name = "Deniz", startLocationRowId = "l")),
            scenarios = listOf(ScenarioDraft(rowId = "s", title = "İlk Gün", startLocationRowId = "l")),
            scenes = listOf(
                SceneDraft(
                    rowId = "sc",
                    name = "Buluşma",
                    locationRowId = "l",
                    branches = listOf(
                        BranchDraft(
                            rowId = "b",
                            label = "Bir şey yap",
                            // The key is blank: the effect cannot be built at all.
                            effect = EffectDraft(kind = EffectKind.SET_VARIABLE),
                        ),
                    ),
                ),
            ),
        )

        val report = AuthoringCompiler.review(AuthoringCompiler.compile(draft))
        assertEquals(
            listOf("\"Buluşma\" sahnesindeki \"Bir şey yap\" seçeneği hiçbir şeyi değiştirmiyor."),
            report.errors.map { it.message },
        )
    }

    // ==================================================================
    // 3. Slugs are stable, ASCII and unique
    // ==================================================================

    /**
     * The id folding is a *format* decision: an id becomes a file name and a URL path
     * segment, so "Kaçış Yıldızı" has to leave as `kacis-yildizi` on every device. Stable
     * means deterministic - the same draft compiled twice is the same pack twice.
     */
    @Test
    fun `slugs fold turkish letters and stay stable`() {
        assertEquals("kacis-yildizi", stableSlug("Kaçış Yıldızı!"))
        assertEquals("istanbul-bogazi", stableSlug("İstanbul Boğazı"))
        assertEquals("son-fener", stableSlug("Son Fener"))
        assertEquals(stableSlug("Ada"), stableSlug("Ada"))
    }

    /**
     * Two rows of one kind with one name must not merge into one id.
     *
     * `StoryPack` itself refuses duplicate character ids, so this is not cosmetic: a slug
     * collision would make the compiler crash on perfectly ordinary content - two islands
     * both called "Ada".
     */
    @Test
    fun `colliding names get unique ids and compiling twice is identical`() {
        val draft = AuthoringDraft(
            title = "İkiz Ada",
            locations = listOf(
                LocationDraft(rowId = "l1", name = "Ada"),
                LocationDraft(rowId = "l2", name = "Ada"),
            ),
            characters = listOf(
                CharacterDraft(rowId = "c1", name = "Ada", startLocationRowId = "l1"),
                CharacterDraft(rowId = "c2", name = "Ada", startLocationRowId = "l2"),
            ),
            scenarios = listOf(ScenarioDraft(rowId = "s1", title = "Ada", startLocationRowId = "l1")),
        )

        val first = AuthoringCompiler.compile(draft)
        val second = AuthoringCompiler.compile(draft)

        assertEquals(listOf("ada", "ada-2"), first.locations.map { it.id.value })
        assertEquals(listOf("ada", "ada-2"), first.characters.map { it.id.value })
        // And each twin keeps its own start: rows do not silently share a reference.
        assertEquals(
            mapOf("ada" to "ada", "ada-2" to "ada-2"),
            first.initialWorldState.startLocations.mapKeys { it.key.value }.mapValues { it.value.value },
        )
        assertEquals(first.id, second.id)
        assertEquals(first.characters.map { it.id }, second.characters.map { it.id })
        assertEquals(first.scenes.map { it.id }, second.scenes.map { it.id })
    }

    // ==================================================================
    // 4. The compiler wires the graph
    // ==================================================================

    /**
     * The auto-linking promise: the opening names its cast, its place, every objective and
     * its initial scene - and a scene declares the threads its own branches advance. The
     * author wrote rows; the pack is a graph.
     */
    @Test
    fun `the scenario auto links cast start location objectives and initial scene`() {
        val pack = AuthoringCompiler.compile(filledDraft())

        val scenario = pack.scenarios.single()
        assertEquals(stableSlug("İlk Gün"), scenario.id)
        assertEquals(stableSlug("İskele"), scenario.startLocationId.value)
        assertEquals(
            listOf(stableSlug("Deniz"), stableSlug("Ayşe")),
            scenario.castCharacterIds.map { it.value },
        )
        assertEquals(stableSlug("Deniz"), scenario.focusCharacterId?.value)
        assertEquals(listOf(stableSlug("Feneri sahibine ulaştır")), scenario.objectiveIds)
        assertEquals(stableSlug("İskelede buluşma"), scenario.initialSceneId)

        // The scene declares the thread its own branch advances, so the reachability
        // validator sees the wiring rather than an orphan.
        val scene = pack.scenes.single()
        assertEquals(listOf(stableSlug("Kayıp fener")), scene.threadIds.map { it.value })

        // The variables every writer and watcher agrees on are declared in the starting
        // world, with a blank value so the win condition cannot be true at turn zero.
        val keys = pack.initialWorldState.variables.map { it.key }.toSet()
        assertTrue("fener_teslim_edildi yazılan yerde tanımlı olmalı", "fener_teslim_edildi" in keys)
        assertTrue("fener_kayboldu görevin izlediği yerde tanımlı olmalı", "fener_kayboldu" in keys)
        assertTrue("fener_bulundu olayın beklediği yerde tanımlı olmalı", "fener_bulundu" in keys)
    }

    // ==================================================================
    // 5. The authored pack is playable
    // ==================================================================

    /**
     * The end-to-end proof: the branch's variable *is* the variable the objective watches -
     * demonstrated by playing it.
     *
     * `StoryInstanceFactory.create` opens the story from the compiled scenario, `BranchEngine`
     * offers and compiles the authored fork into a typed payload, the `EventEngine` applies
     * it, and `ObjectiveEngine` resolves the objective against the new world. If any link in
     * that chain were decorative - a variable key mistyped, a scene that never opens, a
     * condition watching nothing - this test is where it would die.
     */
    @Test
    fun `a branch writes the variable an objective watches and the objective completes`() {
        val pack = AuthoringCompiler.compile(filledDraft())
        val scenario = pack.scenarios.single()

        val instance = StoryInstanceFactory.create(
            pack,
            StoryCreationOptions(
                instanceId = StoryInstanceId("story-authoring-test"),
                scenario = scenario,
                castCharacterIds = scenario.castCharacterIds,
            ),
        )

        // The opening scene really opened...
        assertNotNull("açılış sahnesi hikâye başında açık olmalı", instance.currentScene())
        // ...and its forks are actually on offer.
        val offered = BranchEngine.offered(instance, pack)
        val fork = offered.firstOrNull { it.label == "Feneri kıyıya götür" }
        assertNotNull("açılışta sunulan dallar arasında biri olmalı", fork)

        val choice = BranchEngine.choiceFor(instance, pack, fork!!.sceneId, fork.choiceId)
        assertNotNull(choice)
        val payloads = BranchEngine.compile(instance, fork.sceneId, choice!!, instance.worldClock.now)

        // The branch writes one variable...
        val writes = payloads.filterIsInstance<WorldVariableSet>()
        assertEquals("dal tek bir değişken yazmalı", 1, writes.size)
        // ...and the objective watches exactly that key and value. The same variable, by
        // construction: this is what "playable end to end" means for an authored pack.
        val objective = pack.objectives.single()
        val watch = objective.completionConditions.single() as EventCondition.VariableEquals
        assertEquals(writes.single().key, watch.key)
        assertEquals(writes.single().value, watch.value)

        // Take the branch for real - through the engine, never by writing fields.
        val engine = EventEngine(WorldDefinition(pack.characters, pack.locations))
        var live = instance
        payloads.forEach { payload ->
            val application = engine.applyImmediately(live, payload)
            assertTrue("dalın sonucu uygulanmalı: $application", application is EventApplication.Applied)
            live = (application as EventApplication.Applied).instance
        }

        // And the objective resolves against the new world.
        val outcome = ObjectiveEngine.evaluate(live, pack)
        assertTrue("görev durumu değişmeli", outcome.changed)
        assertEquals(ObjectiveStatus.COMPLETED, outcome.objectives.single().status)
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** The installer the app's composition root builds, minus its Android halves. */
    private fun installerFor(root: File): StoryPackInstaller = StoryPackInstaller(
        packs = JsonCharalyRepository(InMemoryCharalyStorage()),
        registry = InMemoryStoryPackRegistry(),
        store = PackInstallStore(root),
        engineVersion = "0.8.0",
        storyCountFor = { 0 },
    )
}