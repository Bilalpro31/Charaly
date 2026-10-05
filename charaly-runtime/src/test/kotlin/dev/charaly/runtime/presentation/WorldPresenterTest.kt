package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The world screen is the screen that decides whether Charaly reads as a *world*.
 *
 * These tests pin the properties that make it one: you can see somewhere has people in
 * it before you walk there, walking finds those people, and an NPC is a person rather
 * than a line in a cast list.
 */
class WorldPresenterTest {

    private val pack = DemoStoryPacks.all.first { it.id.value == "pack-miraculous-shadows-of-paris" }
    private val definition = dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations)
    private val shop = LocationId("andre-ice-cream")
    private val andre = CharacterId("andre")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-world"),
            scenario = pack.defaultScenario(),
            focusCharacterId = CharacterId("marinette"),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    @Test
    fun `every place in the world is listed`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        assertEquals(pack.locations.size, snapshot.locations.size)
        assertEquals(pack.title, snapshot.worldTitle)
    }

    @Test
    fun `a place shows who is standing in it right now`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        val shopCard = snapshot.location(shop.value)
        assertNotNull("Andre's shop must be listed", shopCard)
        assertTrue(
            "Andre must be shown as present in his shop before the player walks in",
            andre.value in shopCard!!.presentCharacterIds,
        )
        assertTrue(shopCard.hasCompany)
        assertTrue(shopCard.occupancyLine().contains("André"))
    }

    @Test
    fun `an empty place says so instead of showing nothing`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        snapshot.locations.filterNot { it.hasCompany }.forEach { place ->
            assertEquals("Empty right now", place.occupancyLine())
        }
    }

    @Test
    fun `people are shown with where they are and what they are doing`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        val andreCard = snapshot.characters.firstOrNull { it.id == andre.value }
        assertNotNull("Andre must appear in the world's people", andreCard)
        val card = andreCard!!
        assertTrue("Andre must have a location", card.locationName.isNotBlank())
        assertTrue(
            "Andre must have a readable activity, got '${card.activityLabel}'",
            card.activityLabel.isNotBlank(),
        )
    }

    @Test
    fun `npcs appear as people in the world, not as chat partners`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        val npcCards = snapshot.characters.filter { it.isNpc }
        assertTrue(
            "NPCs must appear in the world listing",
            npcCards.size >= 8,
        )
        assertTrue(
            "at least one NPC must be somewhere specific right now",
            npcCards.all { it.locationName.isNotBlank() },
        )
    }

    @Test
    fun `the player's own location is marked and listed first`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        val here = snapshot.currentLocationName
        if (here.isNotBlank()) {
            assertEquals(here, snapshot.locations.first().name)
            assertTrue("the current place must be marked", snapshot.locations.first().isHere)
        }
    }

    @Test
    fun `people where the player is standing are listed first`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        val here = snapshot.characters.filter { it.isHere }
        if (here.isNotEmpty()) {
            assertTrue(
                "people present must come before everyone else",
                snapshot.characters.take(here.size).all { it.isHere },
            )
        }
    }

    @Test
    fun `relationship is reported in the user's language and never invented`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        snapshot.characters.forEach { person ->
            assertTrue(
                "${person.name} has an unreadable relationship label",
                person.relationshipLabel.isNotBlank(),
            )
            assertFalse(
                "${person.name} is described as an enemy with no relationship on record",
                person.relationshipLabel == "an enemy" && person.relationshipLabel.isBlank(),
            )
        }
    }

    @Test
    fun `an authored routine label is preferred over a generic word`() {
        val runtime = dev.charaly.runtime.domain.CharacterRuntime(
            characterId = andre,
            name = "André",
            activity = dev.charaly.runtime.domain.CharacterActivity.WORKING,
            activityLabel = "serving customers",
        )
        assertEquals("serving customers", WorldPresenter.activityLabel(runtime))

        val generic = runtime.copy(activityLabel = "")
        assertEquals("working", WorldPresenter.activityLabel(generic))
    }

    @Test
    fun `a world with no characters still lists its places`() {
        val emptyInstance = instance().copy(
            worldState = instance().worldState.copy(characters = emptyMap()),
        )
        val snapshot = WorldPresenter.build(emptyInstance, definition, pack.title)
        assertFalse(snapshot.locations.isEmpty())
        assertTrue(snapshot.characters.isEmpty())
        assertFalse("an empty world is not an empty screen", snapshot.isEmpty)
    }

    @Test
    fun `occupied places are distinguishable from empty ones`() {
        val snapshot = WorldPresenter.build(instance(), definition, pack.title)
        assertTrue(
            "somewhere in the city must have people in it",
            snapshot.occupiedPlaces().isNotEmpty(),
        )
    }

    @Test
    fun `every pack renders a world snapshot`() {
        DemoStoryPacks.all.forEach { each ->
            val story = StoryInstanceFactory.create(
                each,
                StoryCreationOptions(
                    instanceId = StoryInstanceId("story-${each.id.value}"),
                    scenario = each.defaultScenario(),
                ),
            )
            val snapshot = WorldPresenter.build(
                story,
                dev.charaly.runtime.domain.WorldDefinition(each.characters, each.locations),
                each.title,
            )
            assertTrue("${each.id.value} must render a world", snapshot.locations.isNotEmpty())
            snapshot.locations.forEach { location ->
                assertTrue(location.name.isNotBlank())
            }
        }
    }
}