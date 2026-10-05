package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.MemoryId
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemorySource
import dev.charaly.runtime.domain.memory.MemorySubject
import dev.charaly.runtime.domain.memory.MemoryTier
import dev.charaly.runtime.domain.memory.MemoryVisibility
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.DemoStoryPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory panel has to be readable *and* honest.
 *
 * Readable means a user never sees a tier name, a raw id or a database row. Honest means
 * it never shows a superseded claim beside its replacement, and never implies a
 * character recalls something they were not told.
 */
class MemoryPanelPresenterTest {

    // The demo packs seed authored memories at story start, which would drown out the
    // memories a test adds. This pack is stripped of them so each assertion below counts
    // exactly the memories the test put there.
    private val pack = DemoStoryPacks.all.first().copy(
        initialKnowledge = dev.charaly.runtime.domain.InitialKnowledge(),
    )
    private val marinette = CharacterId("marinette")
    private val adrien = CharacterId("adrien")

    private fun memory(
        id: String,
        owner: CharacterId = marinette,
        content: String = "Something worth remembering.",
        importance: Int = 3,
        minute: Int = 600,
        tier: MemoryTier = MemoryTier.EPISODIC,
        visibility: MemoryVisibility = MemoryVisibility.CHARACTER,
        pinned: Boolean = false,
        supersededBy: MemoryId? = null,
    ) = Memory(
        id = MemoryId(id),
        characterId = owner,
        content = content,
        importance = importance,
        createdAt = StoryTime.of(day = 1, hour = minute / 60, minute = minute % 60),
        source = MemorySource.EVENT,
        tier = tier,
        visibility = visibility,
        pinned = pinned,
        supersededBy = supersededBy,
    )

    private fun story(vararg memories: Memory) = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-mem"),
            scenario = pack.defaultScenario(),
            nowEpochMs = 0L,
        ),
    ).let { it.evolved(memories = it.memories.addAll(memories.toList())) }

    // ------------------------------------------------------------------
    // Tabs
    // ------------------------------------------------------------------

    @Test
    fun `every tab the brief asks for exists`() {
        val snapshot = MemoryPanelPresenter.build(story(memory("m1")), pack)
        assertEquals(
            listOf("Recent", "Important", "Character", "Story"),
            snapshot.tabs.map { it.label },
        )
    }

    @Test
    fun `an empty story shows an empty state rather than blank tabs`() {
        val snapshot = MemoryPanelPresenter.build(story(), pack)
        assertTrue(snapshot.isEmpty)
        assertNotNull(snapshot.emptyState)
        assertEquals(0, snapshot.totalCount)
        snapshot.tabs.forEach { tab ->
            assertTrue("${tab.label} needs its own empty wording", tab.emptyLabel.isNotBlank())
        }
    }

    @Test
    fun `the important tab shows only important memories`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory("m-trivial", content = "The weather.", importance = 1),
                memory("m-defining", content = "Marinette lost her notebook.", importance = 5),
                memory("m-important", content = "The player helped Marinette.", importance = 4),
            ),
            pack,
            activeTab = MemoryTabId.IMPORTANT,
        )
        val shown = snapshot.groups.flatMap { it.cards }.map { it.id }
        assertTrue("m-defining" in shown)
        assertTrue("m-important" in shown)
        assertFalse("a trivial memory must not appear here", "m-trivial" in shown)
    }

    @Test
    fun `the character tab groups memories by who remembers them`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory("m-m1", owner = marinette, content = "Marinette remembers the notebook."),
                memory("m-m2", owner = marinette, content = "Marinette remembers the park."),
                memory("m-a1", owner = adrien, content = "Adrien remembers the museum."),
            ),
            pack,
            activeTab = MemoryTabId.CHARACTER,
        )
        assertEquals(2, snapshot.groups.size)
        val marinetteGroup = snapshot.groups.first { it.cards.any { c -> c.ownerId == marinette.value } }
        assertEquals(2, marinetteGroup.cards.size)
        assertTrue(marinetteGroup.subtitle.contains("memories"))
    }

    @Test
    fun `the story tab separates settled canon from things still moving`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory("m-canon", content = "Andre runs the shop.", tier = MemoryTier.CANON),
                memory("m-episodic", content = "The player bought ice cream.", tier = MemoryTier.EPISODIC),
            ),
            pack,
            activeTab = MemoryTabId.STORY,
        )
        val canon = snapshot.groups.first { it.title == "Part of the world" }
        val moving = snapshot.groups.first { it.title == "Still moving" }
        assertTrue(canon.cards.any { it.id == "m-canon" })
        assertTrue(moving.cards.any { it.id == "m-episodic" })
    }

    @Test
    fun `pinned memories are surfaced above recent ones`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory("m-old-pinned", content = "An old pinned fact.", minute = 60, pinned = true),
                memory("m-new", content = "Something that just happened.", minute = 1200),
            ),
            pack,
        )
        assertEquals("Pinned", snapshot.groups.first().title)
        assertEquals("m-old-pinned", snapshot.groups.first().cards.first().id)
    }

    // ------------------------------------------------------------------
    // Honesty
    // ------------------------------------------------------------------

    @Test
    fun `a superseded claim is never shown as current`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory("m-old", content = "The player lives in Paris.", minute = 100),
                memory("m-new", content = "The player moved to Lyon.", minute = 1200),
                memory("m-old", content = "The player lives in Paris.", minute = 100, supersededBy = MemoryId("m-new")),
            ),
            pack,
        )
        val shown = snapshot.groups.flatMap { it.cards }.map { it.id }
        assertTrue("the newer claim must be shown", "m-new" in shown)
        assertFalse("a superseded claim must not be shown as current", shown.count { it == "m-old" } > 0)
    }

    @Test
    fun `a secret is marked as one rather than presented as an ordinary memory`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory(
                    "m-secret",
                    content = "The player told Marinette something in confidence.",
                    visibility = MemoryVisibility.SECRET,
                ),
            ),
            pack,
        )
        val card = snapshot.groups.flatMap { it.cards }.single()
        assertTrue("a secret must be labelled", card.isSecret)
    }

    @Test
    fun `no card exposes an internal tier or id as its main label`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory("m1", content = "A fact.", tier = MemoryTier.CANON),
                memory("m2", content = "An event.", tier = MemoryTier.EPISODIC),
                memory("m3", content = "A scene.", tier = MemoryTier.SCENE),
            ),
            pack,
        )
        snapshot.groups.flatMap { it.cards }.forEach { card ->
            assertTrue(card.tierLabel.isNotBlank())
            listOf("WORKING", "SCENE", "EPISODIC", "CANON", "RELATIONSHIP").forEach { internal ->
                assertFalse(
                    "the internal tier name '$internal' must never be shown",
                    card.tierLabel.contains(internal),
                )
            }
            assertFalse("importance must be a word", card.importanceLabel.all { it.isDigit() })
        }
    }

    @Test
    fun `every card carries the owner the time and a source`() {
        val snapshot = MemoryPanelPresenter.build(
            story(memory("m1", owner = marinette, content = "Something happened.")),
            pack,
        )
        val card = snapshot.groups.flatMap { it.cards }.single()
        assertTrue(card.ownerName.isNotBlank())
        assertTrue(card.timeLabel.isNotBlank())
        assertTrue(card.sourceLabel.isNotBlank())
        assertTrue(card.canForget)
    }

    @Test
    fun `tab counts match what each tab actually shows`() {
        val snapshot = MemoryPanelPresenter.build(
            story(
                memory("m1", owner = marinette, importance = 5, tier = MemoryTier.CANON),
                memory("m2", owner = adrien, importance = 2),
            ),
            pack,
        )
        assertEquals(2, snapshot.tabCounts[MemoryTabId.RECENT])
        assertEquals(1, snapshot.tabCounts[MemoryTabId.IMPORTANT])
        assertEquals(2, snapshot.tabCounts[MemoryTabId.CHARACTER])
        assertEquals(1, snapshot.tabCounts[MemoryTabId.STORY])
    }

    @Test
    fun `a story with no pack still renders memory names`() {
        val snapshot = MemoryPanelPresenter.build(
            story(memory("m1", owner = marinette, content = "Something happened.")),
            pack = null,
        )
        assertFalse(snapshot.isEmpty)
        assertTrue(snapshot.groups.flatMap { it.cards }.single().ownerName.isNotBlank())
    }

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    @Test
    fun `consolidation can be previewed without changing anything`() {
        val store = story(
            memory("m1", content = "The player helped Marinette find her lost notebook."),
            memory("m2", content = "The player helped Marinette find her lost notebook.", minute = 700),
        )
        val preview = MemoryOperations.previewConsolidation(store)
        assertTrue("a duplicate should be visible in the preview", preview.changesAnything)
        assertEquals(
            "previewing must not change the store",
            2,
            store.memories.current().size,
        )
    }

    @Test
    fun `the player sees world-visible memories no character has been told`() {
        // Visibility is per memory, not "everything is visible to everyone". A
        // WORLD-scoped fact is visible to all; a CHARACTER-scoped one is not.
        val store = story(
            memory(
                "m-world",
                content = "The bakery opens early on market day.",
                visibility = MemoryVisibility.WORLD,
            ),
            memory("m-private", content = "Marinette's private thought.", visibility = MemoryVisibility.PRIVATE),
        )
        val visible = MemoryOperations.visibleTo(store).map { it.id.value }
        assertTrue("m-world" in visible)
        assertFalse("a private memory must not be offered to the player view", "m-private" in visible)
    }

    @Test
    fun `a secret is retrievable by its owner but not by a bystander`() {
        val store = story(
            memory(
                "m-secret",
                content = "The player told Marinette something in confidence.",
                visibility = MemoryVisibility.SECRET,
            ),
        )
        assertTrue(
            MemoryOperations.visibleTo(store, MemorySubject.Character(marinette)).any { it.id.value == "m-secret" },
        )
        assertFalse(
            "a bystander must not retrieve a secret",
            MemoryOperations.visibleTo(store, MemorySubject.Character(adrien)).any { it.id.value == "m-secret" },
        )
    }
}