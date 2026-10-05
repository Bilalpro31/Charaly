package dev.charaly.app.ui

import dev.charaly.runtime.inference.InferenceError
import dev.charaly.runtime.session.CharalyError
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.presentation.HomePresenter
import dev.charaly.runtime.presentation.LibraryPresenter
import dev.charaly.runtime.presentation.MemoryPresenter
import dev.charaly.runtime.presentation.ModelLibraryPresenter
import dev.charaly.runtime.presentation.NewStoryPresenter
import dev.charaly.runtime.presentation.NarrativeSegments
import dev.charaly.runtime.presentation.PackDetailPresenter
import dev.charaly.runtime.presentation.PackSortOrder
import dev.charaly.runtime.presentation.RelativeTime
import dev.charaly.runtime.presentation.ResolvedTheme
import dev.charaly.runtime.presentation.RegistryModelStatus
import dev.charaly.runtime.presentation.SegmentKind
import dev.charaly.runtime.presentation.SessionsPresenter
import dev.charaly.runtime.presentation.StoryPresenter
import dev.charaly.runtime.presentation.WorldPanelPresenter
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.model.BuiltInModelCatalog
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelOrigin
import dev.charaly.runtime.model.ModelProfileLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens are pure functions of real state.
 *
 * That is the whole reason the presenters live in the runtime module rather than next
 * to the composables: every assertion below is about what the UI will actually show,
 * and it runs in milliseconds with no device and no emulator.
 */
class PresentationTest {

    private val packs = DemoStoryPacks.all
    private val definitions = packs.associate { pack ->
        pack.id.value to dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations)
    }

    private fun story(packIndex: Int = 0, title: String = "Test story"): dev.charaly.runtime.domain.StoryInstance {
        val pack = packs[packIndex]
        val scenario = pack.defaultScenario()
        return StoryInstanceFactory.create(
            pack,
            StoryCreationOptions(
                instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-$packIndex"),
                title = title,
                scenario = scenario,
                focusCharacterId = scenario?.focusCharacterId,
                castCharacterIds = scenario?.castCharacterIds.orEmpty(),
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
    }

    private fun noModel() = RegistryModelStatus(
        installed = null,
        engineLabel = "llama",
        ready = false,
    )

    private fun readyModel(model: InstalledModel) = RegistryModelStatus(
        installed = model,
        engineLabel = "llama",
        ready = true,
    )

    // ---------------------------------------------------------------- Home

    @Test
    fun `home leads with continue story when a story exists`() {
        val instance = story()
        val snapshot = HomePresenter.build(
            nowEpochMs = 1_700_000_600_000L,
            instances = listOf(instance),
            packs = packs,
            definitions = definitions,
            model = noModel(),
        )

        val card = snapshot.continueCard
        assertNotNull(card)
        assertTrue(snapshot.hasContinue)
        assertEquals(instance.id.value, card!!.storyId)
        assertEquals(3, snapshot.packs.size)
        assertEquals("10 min ago", card.lastPlayedLabel)
        assertTrue(card.companionName.isNotBlank())
    }

    @Test
    fun `home shows every demo pack and no raw engine data`() {
        val snapshot = HomePresenter.build(
            nowEpochMs = 1_700_000_000_000L,
            instances = emptyList(),
            packs = packs,
            definitions = definitions,
            model = noModel(),
        )
        assertFalse(snapshot.hasContinue)
        assertEquals(3, snapshot.packs.size)
        packs.forEach { pack ->
            val card = snapshot.packs.first { it.id == pack.id.value }
            assertEquals(pack.characters.size, card.characterCount)
            assertEquals(pack.locations.size, card.locationCount)
            assertTrue(card.tagline.isNotBlank())
            assertTrue(card.genres.isNotEmpty())
        }
        assertEquals("No model installed", snapshot.modelStatus.modelName)
    }

    @Test
    fun `home surfaces a ready model in one line`() {
        val model = InstalledModel(
            id = "local-qwen",
            displayName = "Qwen 3 4B",
            absolutePath = "/data/models/qwen.gguf",
            sizeBytes = 4_200_000_000L,
            origin = ModelOrigin.IMPORTED,
        )
        val snapshot = HomePresenter.build(
            nowEpochMs = 1L,
            instances = emptyList(),
            packs = packs,
            definitions = definitions,
            model = readyModel(model),
        )
        assertEquals("Qwen 3 4B", snapshot.modelStatus.modelName)
        assertEquals("Ready", snapshot.modelStatus.stateLabel)
        assertTrue(snapshot.modelStatus.detailLabel.contains("3.9 GB"))
        assertTrue(snapshot.modelStatus.isReady)
    }

    // ------------------------------------------------------------- Library

    @Test
    fun `library renders all three worlds in real sections`() {
        val snapshot = LibraryPresenter.build(
            nowEpochMs = 1L,
            packs = packs,
            instances = emptyList(),
        )
        assertEquals(3, snapshot.totalPacks)
        assertEquals(3, snapshot.visiblePacks)
        assertTrue(snapshot.sections.isNotEmpty())
        assertNull(snapshot.emptyState)

        val ids = snapshot.sections.flatMap { section -> section.packs.map { it.id } }
        assertEquals(3, ids.toSet().size)
        assertTrue(snapshot.sections.any { it.title == "Featured" })
    }

    @Test
    fun `library search and genre filter really filter`() {
        val byQuery = LibraryPresenter.build(
            nowEpochMs = 1L,
            packs = packs,
            instances = emptyList(),
            query = "cyberpunk",
        )
        assertEquals(1, byQuery.visiblePacks)
        assertEquals("pack-neon-district-afterlight", byQuery.sections.first().packs.first().id)

        val byGenre = LibraryPresenter.build(
            nowEpochMs = 1L,
            packs = packs,
            instances = emptyList(),
            selectedGenres = setOf("Fantasy"),
        )
        assertTrue(byGenre.visiblePacks in 1..2)

        val nothing = LibraryPresenter.build(
            nowEpochMs = 1L,
            packs = packs,
            instances = emptyList(),
            query = "zzzz",
        )
        assertEquals(0, nothing.visiblePacks)
        assertNotNull(nothing.emptyState)
        assertEquals("Nothing matches that.", nothing.emptyState!!.title)
    }

    @Test
    fun `library sorts by the requested order`() {
        val byCharacters = LibraryPresenter.build(
            nowEpochMs = 1L,
            packs = packs,
            instances = emptyList(),
            sortOrder = PackSortOrder.CHARACTERS,
        )
        val counts = byCharacters.sections.flatMap { it.packs }.map { it.characterCount }
        assertEquals(counts.sortedDescending(), counts)

        val alphabetical = LibraryPresenter.build(
            nowEpochMs = 1L,
            packs = packs,
            instances = emptyList(),
            sortOrder = PackSortOrder.ALPHABETICAL,
        )
        val titles = alphabetical.sections.flatMap { it.packs }.map { it.title.lowercase() }
        assertEquals(titles.sorted(), titles)
    }

    @Test
    fun `library empty state has real copy`() {
        val snapshot = LibraryPresenter.build(nowEpochMs = 1L, packs = emptyList(), instances = emptyList())
        assertNotNull(snapshot.emptyState)
        assertEquals("No story packs yet.", snapshot.emptyState!!.title)
    }

    // -------------------------------------------------------- Pack detail

    @Test
    fun `pack detail shows characters locations threads and events from data`() {
        packs.forEach { pack ->
            val snapshot = PackDetailPresenter.build(
                nowEpochMs = 1L,
                pack = pack,
                instances = emptyList(),
                defaultProfileName = ModelProfileLibrary.default.name,
            )
            assertEquals(pack.characters.size, snapshot.characters.size)
            assertEquals(pack.locations.size, snapshot.locations.size)
            assertEquals(pack.initialStoryThreads.size, snapshot.threads.size)
            assertEquals(pack.events.size, snapshot.events.size)
            assertTrue(snapshot.scenarios.isNotEmpty())
            assertTrue(snapshot.personas.isNotEmpty())
            assertFalse(snapshot.canContinue)
            snapshot.characters.forEach { character ->
                assertTrue(character.name.isNotBlank())
                assertTrue(character.tagline.isNotBlank())
            }
            snapshot.events.forEach { event ->
                assertTrue(event.triggerLabel.isNotBlank())
            }
        }
    }

    @Test
    fun `pack detail reports the fan notice for the Miraculous pack`() {
        val snapshot = PackDetailPresenter.build(1L, packs[0], emptyList(), "Balanced")
        assertTrue(snapshot.fandomNotice.contains("Fan-made"))
    }

    @Test
    fun `pack detail can continue once a story exists`() {
        val instance = story()
        val snapshot = PackDetailPresenter.build(1L, packs[0], listOf(instance), "Balanced")
        assertTrue(snapshot.canContinue)
        assertEquals(1, snapshot.activeStories.size)
        assertEquals(instance.id.value, snapshot.activeStories.first().id)
    }

    @Test
    fun `each pack has its own accent so worlds do not look alike`() {
        val themes = packs.map { ResolvedTheme.of(it.identity.theme) }
        assertEquals(3, themes.map { it.primary }.toSet().size)
        assertEquals(3, themes.map { it.surface }.toSet().size)
    }

    // ------------------------------------------------------- New story

    @Test
    fun `new story offers only its own pack's content`() {
        packs.forEach { pack ->
            val draft = NewStoryPresenter.initialDraft(pack)
            val snapshot = NewStoryPresenter.build(
                draft = draft,
                pack = pack,
                installedModels = emptyList(),
                activeModelId = "",
            )
            assertEquals(pack.scenarios.size, snapshot.scenarios.size)
            assertEquals(pack.personas.size, snapshot.personas.size)
            assertEquals(pack.characters.size, snapshot.castable.size)
            val castNames = snapshot.castable.map { it.name }.toSet()
            assertTrue(castNames.isNotEmpty())
            pack.characters.forEach { character ->
                assertTrue("${character.name} missing from the cast picker", character.name in castNames)
            }
            // Isolation: nothing from another pack may appear in this wizard.
            val foreignNames = DemoStoryPacks.all
                .filter { it.id != pack.id }
                .flatMap { other -> other.characters.map { it.name } }
                .toSet()
            assertTrue(
                "the cast picker leaked another world",
                castNames.none { it in foreignNames },
            )
            assertTrue(snapshot.review.worldIsolated)
        }
    }

    @Test
    fun `new story review states the world role cast place and model`() {
        val pack = packs[0]
        val snapshot = NewStoryPresenter.build(
            draft = NewStoryPresenter.initialDraft(pack),
            pack = pack,
            installedModels = emptyList(),
            activeModelId = "",
        )
        assertEquals(pack.title, snapshot.review.worldTitle)
        assertTrue(snapshot.review.roleName.isNotBlank())
        assertTrue(snapshot.review.locationName.isNotBlank())
        assertTrue(snapshot.review.timeLabel.isNotBlank())
        assertEquals(ModelProfileLibrary.default.name, snapshot.review.profileName)
        assertFalse(snapshot.modelReady)
    }

    // ----------------------------------------------------------- Story

    @Test
    fun `story snapshot opens with an intentional empty state`() {
        val instance = story()
        val snapshot = StoryPresenter.build(
            instance = instance,
            pack = packs[0],
            definition = definitions[packs[0].id.value]!!,
        )
        assertTrue(snapshot.lines.isEmpty())
        assertNotNull(snapshot.emptyState)
        assertTrue(snapshot.emptyState!!.title.startsWith("You are in"))
        assertTrue(snapshot.chapterLabel.isNotBlank())
        assertTrue(snapshot.sceneLine.isNotBlank())
        assertEquals(instance.worldClock.now.formatClock(), snapshot.timeLabel)
        assertTrue(snapshot.participants.isNotEmpty())
    }

    @Test
    fun `story scene line reads like a place and a time`() {
        val instance = story(packIndex = 1)
        val snapshot = StoryPresenter.build(
            instance = instance,
            pack = packs[1],
            definition = definitions[packs[1].id.value]!!,
        )
        // "Metro Core · 00:20" style, never a raw id.
        assertFalse(snapshot.sceneLine.contains("afterlight-market"))
        assertTrue(snapshot.sceneLine.contains(instance.worldClock.now.formatClock()))
    }

    @Test
    fun `narration and dialogue are typeset differently`() {
        val segments = NarrativeSegments.parse(
            "She looked at the clock. \"You're late,\" Marinette said. *slipped the photo under her phone*",
        )
        assertEquals(
            listOf(SegmentKind.NARRATION, SegmentKind.DIALOGUE, SegmentKind.NARRATION, SegmentKind.ACTION),
            segments.map { it.kind },
        )
        assertEquals("You're late,", segments[1].text)
    }

    @Test
    fun `unterminated prose is preserved rather than swallowed`() {
        val segments = NarrativeSegments.parse("She began to say something about the akuma and then")
        assertEquals(1, segments.size)
        assertEquals(SegmentKind.NARRATION, segments.first().kind)
        assertTrue(segments.first().text.contains("akuma"))
    }

    @Test
    fun `generation state is shown as a person, not a spinner`() {
        val instance = story()
        val definition = definitions[packs[0].id.value]!!
        val focusId = instance.focusCharacterId!!
        val speaker = definition.nameOf(focusId)
        val snapshot = StoryPresenter.build(
            instance = instance,
            pack = packs[0],
            definition = definition,
            phase = dev.charaly.runtime.presentation.GenerationPhase.THINKING,
        )
        assertTrue(snapshot.phaseLabel.startsWith(speaker))
        assertTrue(snapshot.phaseLabel.contains("thinking"))
        assertTrue(snapshot.isGenerating)
        assertFalse(snapshot.composerEnabled)
    }

    @Test
    fun `a model failure disables the composer and offers the fix`() {
        val snapshot = StoryPresenter.build(
            instance = story(),
            pack = packs[0],
            definition = definitions[packs[0].id.value]!!,
            phase = dev.charaly.runtime.presentation.GenerationPhase.FAILED,
            failureMessage = "This story needs a local model before it can continue.",
            modelReady = false,
        )
        assertFalse(snapshot.composerEnabled)
        assertTrue(snapshot.composerHint.contains("local model"))
        assertEquals(listOf("Open Models"), snapshot.failureActions)
    }

    @Test
    fun `world panel is an inspection view, never json`() {
        val snapshot = WorldPanelPresenter.build(story(), packs[0])
        assertEquals(packs[0].characters.size, snapshot.characters.size)
        assertTrue(snapshot.characters.all { it.locationName.isNotBlank() })
        assertTrue(snapshot.characters.any { it.relationshipSummary.isNotBlank() })
        assertFalse(snapshot.isEmpty)
        assertTrue(snapshot.revision > 0)
    }

    @Test
    fun `memory panel groups memories by importance and owner`() {
        val snapshot = MemoryPresenter.build(story(), packs[0])
        assertTrue(snapshot.totalCount > 0)
        assertNull(snapshot.emptyState)
        assertTrue(snapshot.sections.any { it.title == "Important memories" })
        snapshot.sections.forEach { section ->
            section.memories.forEach { memory ->
                assertTrue(memory.importanceLabel.isNotBlank())
                assertTrue(memory.ownerName.isNotBlank())
                assertTrue(memory.sourceLabel.isNotBlank())
            }
        }
    }

    @Test
    fun `memory panel has a real empty state`() {
        val empty = dev.charaly.runtime.domain.StoryInstance(
            id = dev.charaly.runtime.domain.StoryInstanceId("empty"),
            storyPackId = packs[0].id,
            packTitle = packs[0].title,
            worldState = dev.charaly.runtime.domain.WorldState.EMPTY,
        )
        val snapshot = MemoryPresenter.build(empty, packs[0])
        assertNotNull(snapshot.emptyState)
        assertEquals("Nothing remembered yet.", snapshot.emptyState!!.title)
    }

    // --------------------------------------------------------- Sessions

    @Test
    fun `sessions screen renders real cards and renames by title`() {
        val instance = story(title = "Rooftop, again")
        val snapshot = SessionsPresenter.build(
            nowEpochMs = 1_700_000_000_000L,
            instances = listOf(instance),
            packs = packs,
        )
        assertEquals(1, snapshot.sessions.size)
        val card = snapshot.sessions.first()
        assertEquals("Rooftop, again", card.title)
        assertEquals(packs[0].title, card.packTitle)
        assertTrue(card.locationName.isNotBlank())
        assertTrue(card.castNames.isNotEmpty())
    }

    @Test
    fun `sessions empty state invites the user into a world`() {
        val snapshot = SessionsPresenter.build(1L, emptyList(), packs)
        assertNotNull(snapshot.emptyState)
        assertEquals("No stories yet.", snapshot.emptyState!!.title)
        assertEquals("Explore Story Packs", snapshot.emptyState!!.actionLabel)
    }

    // ----------------------------------------------------------- Models

    @Test
    fun `model library shows installed models and honest catalog entries`() {
        val installed = InstalledModel(
            id = "local-qwen",
            displayName = "Qwen 3 4B",
            absolutePath = "/models/qwen.gguf",
            sizeBytes = 4_200_000_000L,
            architecture = "Qwen3",
            quantization = "Q4_K_M",
            contextLength = 8192,
        )
        val snapshot = ModelLibraryPresenter.build(
            installedModels = listOf(installed),
            catalog = BuiltInModelCatalog.DEFAULT,
            activeModelId = installed.id,
            loadedModelId = installed.id,
            downloadsAvailable = false,
            availableRamBytes = 8_000_000_000L,
        )

        assertEquals(1, snapshot.installed.size)
        assertEquals("Ready", snapshot.installed.first().stateLabel)
        assertTrue(snapshot.installed.first().actions.canUse)
        assertTrue(snapshot.installed.first().detailRows.any { it.label == "Context" })

        assertTrue(snapshot.recommended.isNotEmpty())
        assertFalse(snapshot.canDownload)
        assertTrue(snapshot.downloadNote.contains("no network access"))
        snapshot.others.forEach { card ->
            assertFalse("a non-installed card must not offer a download", card.actions.canDownload)
            assertTrue(card.actions.disabledReason.isNotBlank())
        }
    }

    @Test
    fun `model detail explains a model that is too large for the device`() {
        val installed = InstalledModel(
            id = "local-huge",
            displayName = "Mistral Nemo 12B",
            absolutePath = "/models/nemo.gguf",
            sizeBytes = 7_100_000_000L,
        )
        val snapshot = ModelLibraryPresenter.detail(
            model = installed,
            binding = null,
            storiesUsing = emptyList(),
            isLoaded = false,
            availableRamBytes = 2_000_000_000L,
        )
        assertEquals("TOO_LARGE", snapshot.verdictLevel)
        assertTrue(snapshot.verdictMessage.contains("memory"))
        assertTrue(snapshot.builtInProfiles.isNotEmpty())
    }

    @Test
    fun `a model that failed to load says so instead of pretending`() {
        val installed = InstalledModel(
            id = "local-broken",
            displayName = "Broken",
            absolutePath = "/models/broken.gguf",
            compatibility = dev.charaly.runtime.model.ModelCompatibility(
                loadFailed = true,
                failureReason = "This model file could not be loaded.",
            ),
        )
        val snapshot = ModelLibraryPresenter.detail(
            model = installed,
            binding = null,
            storiesUsing = emptyList(),
            isLoaded = false,
            availableRamBytes = 0L,
        )
        assertEquals("Could not load", snapshot.stateLabel)
        assertFalse(snapshot.actions.canUse)
        assertEquals("This model file could not be loaded.", snapshot.verdictMessage)
    }

    @Test
    fun `advanced sampler rows only appear for a bound story`() {
        val binding = dev.charaly.runtime.model.ModelBinding.from(
            installedModelId = "local-qwen",
            modelDisplayName = "Qwen 3 4B",
            profile = ModelProfileLibrary.resolve(ModelProfileLibrary.CREATIVE),
            maxContextTokens = 8192,
        )
        val rows = ModelLibraryPresenter.advancedRows(binding)
        assertTrue(rows.any { it.label == "Temperature" })
        assertTrue(rows.any { it.label == "Min P" })
        assertEquals("1.05", rows.first { it.label == "Temperature" }.value)
        assertTrue(rows.none { it.value.isBlank() })
    }

    // ---------------------------------------------------------- Copy

    @Test
    fun `relative time reads like a person wrote it`() {
        val now = 1_700_000_000_000L
        assertEquals("Just now", RelativeTime.describe(now, now - 5_000L))
        assertEquals("12 min ago", RelativeTime.describe(now, now - 12 * 60_000L))
        assertEquals("3 h ago", RelativeTime.describe(now, now - 3 * 3_600_000L))
        assertEquals("Yesterday", RelativeTime.describe(now, now - 30 * 3_600_000L))
        assertEquals("3 days ago", RelativeTime.describe(now, now - 3 * 86_400_000L))
        assertEquals("never", RelativeTime.describe(now, 0L))
    }

    @Test
    fun `engine failures become sentences a person can act on`() {
        val sentences = listOf(
            InferenceError.ModelNotLoaded(),
            InferenceError.ModelNotFound("/x"),
            InferenceError.InvalidModel("/x", "bad magic"),
            InferenceError.OutOfMemory(8L shl 30),
            InferenceError.GenerationFailed("boom"),
            InferenceError.Cancelled(),
            InferenceError.Unsupported("no native lib"),
            CharalyError.ModelNotLoaded(),
            CharalyError.Generation("boom"),
            CharalyError.NoCharacter(),
            CharalyError.NoScene(),
            CharalyError.Persistence("disk"),
        ).map { error ->
            when (error) {
                is InferenceError -> ErrorMessages.of(error)
                is CharalyError -> ErrorMessages.of(error)
                else -> error.message.orEmpty()
            }
        }

        sentences.forEach { sentence ->
            assertTrue("a message was empty: $sentence", sentence.isNotBlank())
            assertFalse("a message leaked an exception type: $sentence", sentence.contains("Exception"))
            assertFalse("a message leaked a class name: $sentence", sentence.contains("InferenceError"))
            assertFalse("a message leaked a path: $sentence", sentence.contains("/x"))
        }
        assertTrue(sentences.first().contains("local model"))
    }
}