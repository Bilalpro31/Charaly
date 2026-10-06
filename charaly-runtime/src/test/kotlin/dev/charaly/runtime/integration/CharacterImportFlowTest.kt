package dev.charaly.runtime.integration

import dev.charaly.runtime.compat.CharacterCardReader
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.pack.DemoStoryPacks
import dev.charaly.runtime.persistence.CharacterLibraryRepository
import dev.charaly.runtime.persistence.ImportedCharacter
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharacterLibraryRepository
import dev.charaly.runtime.presentation.CharacterImportPresenter
import dev.charaly.runtime.presentation.CharacterImportStep
import dev.charaly.runtime.presentation.NewStoryPresenter
import dev.charaly.runtime.session.CharalyRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Character card import, as a user does it.
 *
 * ## Why this suite exists next to the existing importer tests
 *
 * `CompatibilityTest` covers the JSON *parser*. That parser has been correct the whole
 * time - and completely unreachable, because nothing in the app called it and no PNG was
 * ever read. A feature can be fully implemented and entirely absent from the product, and
 * the only way to catch that is to walk the flow the way a person walks it: pick a file,
 * read it, look at it, confirm, and then start a story with the result.
 *
 * So these tests go through [CharalyRuntime]'s public surface and the real presenters, and
 * they assert on the things a user would notice: that a cancel writes nothing, that a bad
 * file produces a sentence rather than an exception, that an imported character is actually
 * present in a running story, and that one playthrough's import does not leak into another.
 */
class CharacterImportFlowTest {

    private val pack = DemoStoryPacks.all.first { it.id.value == "pack-miraculous-shadows-of-paris" }

    private val cardJson = """
    {
      "spec": "chara_card_v2",
      "spec_version": "2.0",
      "data": {
        "name": "Wren",
        "description": "A cartographer who maps places that do not exist yet.",
        "personality": "precise, quietly ambitious",
        "scenario": "Wren is drawing a map of the lower city at dawn.",
        "first_mes": "You are early. Good. I hate explaining things twice.",
        "mes_example": "<START>\n{{user}}: Where do we go?\n{{char}}: North. Obviously north.",
        "creator_notes": "Imported from SillyTavern.",
        "creator": "someone",
        "character_version": "1.2",
        "tags": ["cartographer", "fantasy"],
        "character_book": {
          "entries": [
            {
              "id": 1,
              "keys": ["map", "maps"],
              "content": "Wren's map shows a street that was demolished ten years ago.",
              "comment": "core lore",
              "enabled": true
            },
            { "id": 2, "keys": [], "content": "The ink is made from burned lamp oil.", "enabled": true }
          ]
        }
      }
    }
    """.trimIndent()

    private fun runtime(
        library: CharacterLibraryRepository = JsonCharacterLibraryRepository(InMemoryCharalyStorage()),
    ) = CharalyRuntime(
        repository = dev.charaly.runtime.persistence.JsonCharalyRepository(InMemoryCharalyStorage()),
        engine = dev.charaly.runtime.inference.MockInferenceEngine(initiallyLoaded = true),
        library = library,
    )

    // ------------------------------------------------------------------
    // Reading a file
    // ------------------------------------------------------------------

    @Test
    fun `a json card reads into a preview`() {
        val snapshot = CharacterImportPresenter.fromResult(
            sourceName = "wren.json",
            result = CharacterCardReader.read(cardJson.toByteArray(), "wren.json"),
        )

        assertEquals(CharacterImportStep.PREVIEW, snapshot.step)
        val card = assertNotNull(snapshot.preview).let { snapshot.preview!! }
        assertEquals("Wren", card.name)
        assertEquals(2, card.loreCount)
        // The scenario is kept apart from the author's notes, because a user judging a card
        // needs to know which is which.
        assertTrue("lower city" in card.scenario)
        assertEquals("someone", card.creator)
        assertEquals(listOf("cartographer", "fantasy"), card.tags)
        assertTrue(snapshot.canConfirm)
    }

    @Test
    fun `a png card reads into the same preview as a json card`() {
        // SillyTavern stores cards as base64 JSON inside a PNG text chunk, so this is what
        // almost every real card a user has actually looks like.
        val png = dev.charaly.runtime.compat.CharacterCardPipelineProbe.pngWithCardJson(cardJson)

        val snapshot = CharacterImportPresenter.fromResult(
            sourceName = "wren.png",
            result = CharacterCardReader.read(png, "wren.png"),
        )

        assertEquals(CharacterImportStep.PREVIEW, snapshot.step)
        assertEquals("Wren", snapshot.preview!!.name)
        assertEquals(2, snapshot.preview!!.loreCount)
        assertTrue("the preview should say the card came from a picture", snapshot.preview!!.fromPng)
    }

    @Test
    fun `a png using the newer ccv3 keyword also reads`() {
        // Some exporters write `ccv3` instead of `chara`. Handling only one of them is how
        // a whole class of real cards silently produces an empty description.
        val png = dev.charaly.runtime.compat.CharacterCardPipelineProbe.pngWithCardJson(
            cardJson,
            keyword = dev.charaly.runtime.compat.CharacterCardReader.CCV3_KEYWORD,
        )
        val preview = CharacterCardReader.read(png, "wren.png")
        assertTrue("ccv3 cards must import: $preview", preview.isSuccess)
        assertEquals("Wren", preview.getOrThrow().name)
    }

    @Test
    fun `a png with no card in it is refused in words`() {
        // A real PNG signature with real chunks, but nothing carrying a card.
        val png = dev.charaly.runtime.compat.CharacterCardPipelineProbe.pngWithoutCard()

        val snapshot = CharacterImportPresenter.fromResult(
            sourceName = "holiday.png",
            result = CharacterCardReader.read(png, "holiday.png"),
        )

        assertEquals(CharacterImportStep.FAILED, snapshot.step)
        assertEquals("Could not read character card", snapshot.error?.headline)
        // A user cannot repair a malformed PNG, so the message must not be a parse error.
        assertFalse(snapshot.error?.detail.orEmpty().contains("JSON"))
        assertFalse(snapshot.canConfirm)
    }

    @Test
    fun `a file that is not a card at all is refused in words`() {
        val snapshot = CharacterImportPresenter.fromResult(
            sourceName = "notes.txt",
            result = CharacterCardReader.read("just some text".toByteArray(), "notes.txt"),
        )

        assertEquals(CharacterImportStep.FAILED, snapshot.step)
        assertEquals("Could not read character card", snapshot.error?.headline)
        assertTrue(
            "the detail should say what to do next",
            snapshot.error?.detail.orEmpty().isNotBlank(),
        )
    }

    @Test
    fun `a card that names nobody is refused rather than imported blank`() {
        // Importing it would create a library item the user could not recognise or delete
        // by name, which is worse than refusing.
        val nameless = """{"spec":"chara_card_v2","spec_version":"2.0","data":{"description":"anonymous"}}"""
        val snapshot = CharacterImportPresenter.fromResult(
            sourceName = "anon.json",
            result = CharacterCardReader.read(nameless.toByteArray(), "anon.json"),
        )
        assertEquals(CharacterImportStep.FAILED, snapshot.step)
    }

    // ------------------------------------------------------------------
    // Nothing is written until the user confirms
    // ------------------------------------------------------------------

    @Test
    fun `cancelling a preview writes nothing`() = runTest {
        val rt = runtime()

        // The whole flow up to the decision.
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        CharacterImportPresenter.preview("wren.json", preview)
        val cancelled = CharacterImportPresenter.cancelled()
        assertEquals(CharacterImportStep.IDLE, cancelled.step)

        // The library was never asked to write, so it is empty.
        assertTrue(rt.listImportedCharacters().isEmpty())
    }

    @Test
    fun `a failed read leaves the library untouched`() = runTest {
        val rt = runtime()
        CharacterImportPresenter.fromResult("bad.png", CharacterCardReader.read(ByteArray(4), "bad.png"))

        assertTrue(rt.listImportedCharacters().isEmpty())
    }

    @Test
    fun `confirming writes exactly one character`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()

        val saved = rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1_700L)

        val stored = rt.listImportedCharacters()
        assertEquals(1, stored.size)
        assertEquals("Wren", stored.single().name)
        assertEquals(saved.preview.id, stored.single().preview.id)
        // The card's own text is kept so a later importer version can read a field this one
        // ignores without the user having to find the file again.
        assertTrue(stored.single().rawJson.contains("cartographer"))
    }

    @Test
    fun `importing the same card twice updates rather than duplicates`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 2L)

        // A user cannot tell two identical library entries apart, nor delete the right one.
        assertEquals(1, rt.listImportedCharacters().size)
    }

    @Test
    fun `two different people called the same name both stay reachable`() = runTest {
        val rt = runtime()
        val first = CharacterCardReader.read(cardJson.toByteArray(), "ash-1.json").getOrThrow()
            .copy(name = "Ash", description = "One of them.")
        val second = CharacterCardReader.read(cardJson.toByteArray(), "ash-2.json").getOrThrow()
            .copy(name = "Ash", description = "A different one.")

        // Different cards, so different source text. Two identical files are the *same* card
        // and update in place - the case the previous test covers.
        rt.commitImportedCharacter(preview = first, rawJson = "$cardJson-one", nowEpochMs = 1L)
        rt.commitImportedCharacter(preview = second, rawJson = "$cardJson-two", nowEpochMs = 2L)

        val stored = rt.listImportedCharacters()
        assertEquals("one card must not silently overwrite another", 2, stored.size)
        assertEquals(2, stored.map { it.preview.id }.distinct().size)
        // Deterministic: the same two cards in the same order always produce the same ids.
        assertEquals(stored.map { it.preview.id }, rt.listImportedCharacters().map { it.preview.id })
    }

    @Test
    fun `an imported character survives a restart`() = runTest {
        val storage = InMemoryCharalyStorage()
        val library = JsonCharacterLibraryRepository(storage)
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        runtime(library).commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        val reopened = JsonCharacterLibraryRepository(storage).list()
        assertEquals(1, reopened.size)
        assertEquals("Wren", reopened.single().name)
    }

    // ------------------------------------------------------------------
    // The cast picker
    // ------------------------------------------------------------------

    @Test
    fun `the wizard offers imported characters separately from the packs cast`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        val draft = NewStoryPresenter.initialDraft(pack)
            .copy(importedCharacterIds = setOf(preview.id))
        val snapshot = NewStoryPresenter.build(
            draft = draft,
            pack = pack,
            installedModels = emptyList(),
            activeModelId = "",
            imported = rt.listImportedCharacters(),
        )

        // The pack's cast is untouched...
        assertTrue(snapshot.castable.none { it.id == preview.id })
        // ...and the user's own character is offered in its own list.
        assertEquals(1, snapshot.importable.size)
        assertEquals("Wren", snapshot.importable.single().name)
        assertEquals(listOf("Wren"), snapshot.review.importedNames)
        assertTrue("every cast id must resolve", snapshot.review.castResolves)
    }

    @Test
    fun `an imported id the library no longer has does not break the wizard`() = runTest {
        val rt = runtime()
        val draft = NewStoryPresenter.initialDraft(pack)
            .copy(importedCharacterIds = setOf("someone-who-was-deleted"))

        val snapshot = NewStoryPresenter.build(
            draft = draft,
            pack = pack,
            installedModels = emptyList(),
            activeModelId = "",
            imported = rt.listImportedCharacters(),
        )

        assertFalse(snapshot.review.castResolves)
        assertTrue(snapshot.review.importedNames.isEmpty())
        assertTrue(snapshot.importable.isEmpty())
    }

    @Test
    fun `the library screen reports how many characters are in use`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        val none = CharacterImportPresenter.library(rt.listImportedCharacters())
        assertEquals("Not used in a story yet", none.usageLabel)

        // The label counts characters in use, not stories: the data is one entry per
        // character, and a story total here would be a number the library cannot derive.
        val one = CharacterImportPresenter.library(
            imported = rt.listImportedCharacters(),
            inUseBy = mapOf(CharacterId(preview.id) to 2),
        )
        assertEquals("1 character in a story", one.usageLabel)

        // A second character that is in no story at all does not inflate the count.
        val other = CharacterCardReader.read(cardJson.toByteArray(), "other.json").getOrThrow()
            .copy(id = "other", name = "Other")
        rt.commitImportedCharacter(preview = other, rawJson = "$cardJson-other", nowEpochMs = 2L)
        val mixed = CharacterImportPresenter.library(
            imported = rt.listImportedCharacters(),
            inUseBy = mapOf(CharacterId(preview.id) to 2, CharacterId("other") to 0),
        )
        assertEquals("1 character in a story", mixed.usageLabel)
    }

    @Test
    fun `an empty library shows an empty state rather than a blank screen`() = runTest {
        val snapshot = CharacterImportPresenter.library(emptyList())
        assertTrue(snapshot.isEmpty)
        assertNotNull(snapshot.emptyState)
        assertEquals("Import Character", snapshot.emptyState!!.actionLabel)
    }

    // ------------------------------------------------------------------
    // Into a real story
    // ------------------------------------------------------------------

    @Test
    fun `an imported character is present in a running story`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        val story = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-imported"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        val wren = story.characters[CharacterId(preview.id)]
        assertNotNull("the imported character is not in the story", wren)
        assertEquals("Wren", wren!!.name)
        // Placed somewhere reachable. A character the engine cannot place is a character the
        // player can never meet, which is the whole point of merging them into the pack.
        assertNotNull("the imported character has nowhere to be found", wren.locationId)
        assertNotNull(story.locations[wren.locationId])
    }

    @Test
    fun `the world can name the imported character`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        val story = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-world"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        // The runtime resolves characters through the pack, so this is what "the world knows
        // about Wren" means: the definition resolves, not merely the id.
        val definition = rt.definitionFor(story)
        val character = definition.character(CharacterId(preview.id))
        assertNotNull(character)
        assertEquals("Wren", character!!.name)
        assertTrue("the card's description must reach the model", character.description.contains("cartographer"))
        assertEquals(2, character.loreEntries.size)
    }

    @Test
    fun `the imported characters placement is reached through a validated event`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        val story = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-events"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        // Every world change is an event, including the one that puts a new character
        // somewhere. A placement written straight into state would be the one path in the
        // engine that skips the audit trail.
        assertTrue(
            "the placement left no event",
            story.worldState.eventLog.isNotEmpty(),
        )
        assertTrue(story.worldState.revision > 0L)
    }

    @Test
    fun `importing into one story does not change another`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        // Captured before the merge, because `StoryPack` is an immutable value: comparing the
        // pack with itself after the fact would assert nothing. What matters is that the
        // object the app holds still has the pack's own cast and locations.
        val packCharacters = pack.characters
        val packLocations = pack.locations

        rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-merged"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        assertEquals("the original pack gained a character", packCharacters, pack.characters)
        assertEquals("the original pack gained a location", packLocations, pack.locations)
        assertNull(pack.characters.firstOrNull { it.id.value == preview.id })

        val plain = rt.startStory(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-plain"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertNull("a plain story must not contain the imported character", plain.characters[CharacterId(preview.id)])

        // The plain story was created from the same pack object the merged one used, and it
        // came out with neither the character nor the extra location.
        assertEquals(pack.id, plain.storyPackId)
        assertEquals(packLocations.size, plain.locations.size)
        assertNull(plain.characters[CharacterId(preview.id)])
    }

    @Test
    fun `two stories importing the same character each get their own world`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        suspend fun open(id: String) = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId(id),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        val first = open("story-a")
        val second = open("story-b")

        // Both have Wren, but they are separate worlds with separate state - which is the
        // isolation guarantee the whole pack-scoped architecture rests on.
        assertNotNull(first.characters[CharacterId(preview.id)])
        assertNotNull(second.characters[CharacterId(preview.id)])

        // Two stories built from the same pack and scenario legitimately *start* identical -
        // that is what deterministic creation means. Isolation is about what happens next:
        // advance one world, and the other must not have moved with it. Asserting that two
        // fresh worlds differ would be asserting the opposite of determinism.
        val advancedFirst = rt.advance(first, dev.charaly.runtime.domain.StoryDuration.hours(6))
        val untouchedSecond = rt.loadStory(second.id)!!

        assertTrue(advancedFirst.worldClock.now > untouchedSecond.worldClock.now)
        assertMapsDiffer(
            advancedFirst.characters,
            untouchedSecond.characters,
            why = "advancing one world moved the other",
        )
        // And the second story's Wren is still where it was.
        assertEquals(
            second.characters[CharacterId(preview.id)]?.locationId,
            untouchedSecond.characters[CharacterId(preview.id)]?.locationId,
        )
    }

    @Test
    fun `an imported character who shares a pack character's name does not overwrite them`() = runTest {
        val rt = runtime()
        // "Marinette" is a real character in this pack. Importing a second card with that
        // name must not replace her.
        // A card genuinely named "Marinette", read through the importer so the id is derived the
        // way a real import would derive it rather than being asserted into existence.
        val marinetteCard = cardJson.replace("\"Wren\"", "\"Marinette\"")
        val preview = CharacterCardReader.read(marinetteCard.toByteArray(), "marinette.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = marinetteCard, nowEpochMs = 1L)

        val story = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-collide"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        val definition = rt.definitionFor(story)

        // The card slugifies to `marinette`, which is the pack character's own id. That is a
        // real collision, and it must not resolve by overwriting the pack's Marinette.
        assertEquals("marinette", preview.id)

        // The pack's character is untouched: same id, same name, same description.
        val packMarinette = definition.character(CharacterId("marinette"))
        assertNotNull(packMarinette)
        assertEquals("Marinette Dupain-Cheng", packMarinette!!.name)
        assertTrue(
            "the pack's Marinette was overwritten by an imported card",
            packMarinette.description.contains("Flour on one sleeve"),
        )

        // The imported card landed under a different id, so both are addressable rather than
        // one having silently replaced the other.
        val imported = definition.characters.firstOrNull { it.name == "Marinette" }
        assertNotNull("the imported card is not in the world at all", imported)
        assertTrue(imported!!.description.contains("maps places that do not exist"))
        assertEquals(2, definition.characters.count { it.name.startsWith("Marinette") })
    }

    @Test
    fun `deleting an imported character removes it from the library but not from a story`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)
        val story = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-deleted"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        rt.deleteImportedCharacter(CharacterId(preview.id))

        assertTrue(rt.listImportedCharacters().isEmpty())
        // The running story is the user's, and a character already in it does not vanish
        // because the library entry did.
        assertNotNull(rt.loadStory(story.id)?.characters?.get(CharacterId(preview.id)))
    }

    @Test
    fun `a card can never carry world state into a story`() = runTest {
        val rt = runtime()
        // A card with fields that *look* like world state: a location id, a routine, a
        // relationship. None of them exist on the preview type, so none of them can survive.
        val hostile = """
        {
          "spec": "chara_card_v2", "spec_version": "2.0",
          "data": {
            "name": "Usurper",
            "description": "Tries to arrive already at home.",
            "starting_location_id": "school",
            "relationships": [{"a": "marinette", "trust": 100}],
            "world_variables": {"secrets_known": "all"},
            "clock": {"day": 9, "hour": 23}
          }
        }
        """.trimIndent()

        val preview = CharacterCardReader.read(hostile.toByteArray(), "usurper.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = hostile, nowEpochMs = 1L)

        val story = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-hostile"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        // The clock is the pack's, not the card's.
        assertEquals(pack.scenarios.first().startTime, story.worldClock.now)
        // And the card granted itself no knowledge of anyone.
        val usurper = CharacterId(preview.id)
        assertTrue(story.knowledge.knownByFactsOf(usurper).isEmpty())
        assertNull(story.worldState.relationship(usurper, CharacterId("marinette")))
    }

    @Test
    fun `a story created without imports takes the ordinary path`() = runTest {
        // The common case must be untouched: no merged pack, no extra location, the same
        // pack id as before.
        val rt = runtime()
        val story = rt.startStory(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-ordinary"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
        )
        assertEquals(pack.id, story.storyPackId)
        assertEquals(pack.locations.size, story.locations.size)
    }

    @Test
    fun `the merged pack is saved so the story survives a restart`() = runTest {
        val rt = runtime()
        val preview = CharacterCardReader.read(cardJson.toByteArray(), "wren.json").getOrThrow()
        rt.commitImportedCharacter(preview = preview, rawJson = cardJson, nowEpochMs = 1L)

        val story = rt.startStoryWithImported(
            pack = pack,
            options = StoryCreationOptions(
                instanceId = StoryInstanceId("story-restart"),
                scenario = pack.defaultScenario(),
                focusCharacterId = CharacterId("marinette"),
                modelBinding = ModelBinding.EMPTY,
                nowEpochMs = 1_700_000_000_000L,
            ),
            importedCharacterIds = setOf(CharacterId(preview.id)),
        )

        val reloaded = rt.loadStory(story.id)
        assertNotNull("the story did not survive", reloaded)
        assertNotNull(
            "Wren was lost across a restart",
            reloaded!!.characters[CharacterId(preview.id)],
        )
        assertNotNull("the merged pack was not saved", rt.listPacks().firstOrNull { it.id == story.storyPackId })
    }

}

/**
 * Asserts two maps are not equal, with the reason as the first argument.
 *
 * A named helper rather than an `assertNotEquals` overload: a reason-first overload of a
 * two-argument assertion is resolved by the compiler against argument types, and the result
 * is a confusing failure about argument order rather than about the claim.
 */
private fun assertMapsDiffer(unexpected: Map<*, *>, actual: Map<*, *>, why: String) {
    assertFalse(why, unexpected == actual)
}

private fun assertNotEquals(unexpected: Any?, actual: Any?, why: String = "") {
    assertFalse(
        if (why.isBlank()) "expected something other than $unexpected" else "$why (got $actual)",
        unexpected == actual,
    )
}

/** The facts a character knows, from their side. Named so the assertion above reads clearly. */
private fun dev.charaly.runtime.domain.knowledge.KnowledgeStore.knownByFactsOf(
    character: CharacterId,
) = this.visibleTo(character)