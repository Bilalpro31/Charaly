package dev.charaly.runtime.domain

import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.engine.StoryInstanceFactory
import dev.charaly.runtime.pack.MiraculousPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CANON is not CURRENT STORY.
 *
 * The distinction the brief asks for, and the one that makes a pack a *reference* rather
 * than a save file. Marinette is Marinette and the akuma come from one emotion at night
 * whatever the player does; what changes is who knows what, who is where, and what was
 * promised. Both live in the same instance once a story runs, and confusing them is how a
 * story contradicts itself in the fifth scene with no way to say which version is wrong.
 */
class CanonBibleTest {

    private val pack = MiraculousPack.pack
    private val canon = pack.canon

    private val marinette = CharacterId("marinette")
    private val gabriel = CharacterId("gabriel")
    private val adrien = CharacterId("adrien")

    private fun instance() = StoryInstanceFactory.create(
        pack,
        StoryCreationOptions(
            instanceId = StoryInstanceId("story-canon"),
            title = "Canon",
            scenario = pack.defaultScenario(),
            focusCharacterId = marinette,
            castCharacterIds = listOf(marinette),
            nowEpochMs = 1_700_000_000_000L,
        ),
    )

    // ------------------------------------------------------------------
    // The bible is a real declaration, not a wrapper over empty lists
    // ------------------------------------------------------------------

    @Test
    fun `the shipped pack declares a setting, a tone and a visual identity`() {
        assertTrue("no universe declared", canon.universe.isNotBlank())
        assertTrue("no tone declared", canon.tone.isNotBlank())
        assertTrue("no visual identity declared", canon.visualIdentity.isNotBlank())
        assertFalse(canon.isEmpty())
        assertTrue(canon.declaresCanonSeparately())
    }

    @Test
    fun `the bible has a timeline in order, not a set`() {
        assertTrue("a media pack needs a timeline", canon.timeline.size >= 2)
        assertTrue(
            "an era with no summary says nothing",
            canon.timeline.all { it.summary.isNotBlank() },
        )
    }

    @Test
    fun `world rules state what they forbid`() {
        // This is the field that earns the bible its keep: "there is no daylight patrol"
        // as a rule is checkable, and as flavour it is not.
        assertTrue("no rules declared", canon.worldRules.size >= 4)
        assertTrue(
            "a rule with nothing it forbids is just flavour",
            canon.worldRules.count { it.forbids.isNotEmpty() } >= 3,
        )
        canon.worldRules.forEach {
            assertTrue("rule ${it.id} has no statement", it.statement.isNotBlank())
        }
    }

    @Test
    fun `canon names every fact the pack declares`() {
        assertTrue("the bible indexes nothing", canon.factIds.isNotEmpty())
        assertEquals(
            "every fact the pack declares should be in the bible, or the bible is incomplete",
            pack.initialKnowledge.facts.map { it.id }.toSet(),
            canon.factIds.toSet(),
        )
    }

    @Test
    fun `canon names the major arcs`() {
        assertEquals(pack.initialStoryThreads.size, canon.majorArcThreadIds.size)
        pack.initialStoryThreads.forEach { thread ->
            assertTrue(
                "'${thread.title}' is a thread but not a major arc",
                canon.majorArcThreadIds.contains(thread.id),
            )
        }
    }

    @Test
    fun `canon names its organisations and where they sit`() {
        assertTrue(canon.organizations.isNotEmpty())
        val college = canon.organizations.first { it.id == "org-college" }
        assertEquals(LocationId("school"), college.seatLocationId)
        assertTrue("the school has no members", college.memberCharacterIds.contains(marinette))
    }

    @Test
    fun `canon names the objects that matter and who knows about them`() {
        assertTrue(canon.importantObjects.size >= 3)
        val watch = canon.importantObjects.first { it.id == "obj-watch" }
        assertTrue("an object nobody knows about is a declared plot hook", watch.knownToCharacterIds.isNotEmpty())
    }

    @Test
    fun `secret rules are marked as such`() {
        assertTrue(
            "the akuma business is not a public fact and the bible should say so",
            canon.worldRules.any { it.secret },
        )
    }

    @Test
    fun `secret facts are derived from the facts, not listed twice`() {
        val secret = canon.secretFactIds(pack.initialKnowledge.facts.associateBy { it.id })
        assertTrue("the bible cannot find its own secrets", secret.isNotEmpty())
        // And they really are the secret ones.
        secret.forEach { id ->
            assertTrue(
                "${id.value} was reported secret but is not",
                pack.initialKnowledge.facts.first { it.id == id }.secret,
            )
        }
    }

    @Test
    fun `the rights notice is explicit rather than inferred from an author name`() {
        // A fan pack of someone else's setting has a legal obligation attached, and
        // burying it in a byline is how it ends up redistributed by accident.
        assertTrue(canon.rightsNotice.isNotBlank())
        assertTrue(
            canon.rightsNotice.lowercase().contains("fan"),
        )
    }

    @Test
    fun `the bible describes itself for a pack detail screen`() {
        val described = canon.describe()
        assertTrue(described, described.contains("Setting:"))
        assertTrue(described, described.contains("canonical periods"))
    }

    // ------------------------------------------------------------------
    // Canon is immutable; the story's deviations are separate
    // ------------------------------------------------------------------

    @Test
    fun `a fresh story deviates from canon in no way`() {
        assertTrue(instance().canonDeviations.activeDeviations().isEmpty())
    }

    @Test
    fun `a deviation records both what canon said and what this story has`() {
        val ledger = CanonLedger.EMPTY.with(
            CanonDeviation(
                canonId = "fact-adrien-is-catnoir",
                canonSaid = "Adrien Agreste is Cat Noir. Almost nobody knows this.",
                storyHas = "Adrien Agreste is nobody at all, and the brooch was never moved",
                affectedCharacterIds = listOf(adrien, marinette),
                because = "the player told Nathalie instead",
            ),
        )
        val deviation = ledger.deviation("fact-adrien-is-catnoir")!!
        assertEquals(1, ledger.activeDeviations().size)
        assertTrue(deviation.storyHas.isNotBlank())
        assertFalse(deviation.isRestored)
        assertEquals(
            "Adrien is listed as affected, so his situation is no longer the canon one",
            1,
            ledger.divergedFor(adrien).size,
        )
        assertTrue(
            "and so is Marinette",
            ledger.divergedFor(marinette).isNotEmpty(),
        )
        assertTrue(
            "while someone uninvolved is not dragged in",
            ledger.divergedFor(gabriel).isEmpty(),
        )
    }

    @Test
    fun `restoring canon is recorded rather than forgotten`() {
        val deviation = CanonDeviation(
            canonId = "fact-adrien-is-catnoir",
            canonSaid = "Adrien is Cat Noir",
            storyHas = "",
        )
        assertTrue("an empty storyHas means canon was restored", deviation.isRestored)
        assertTrue(
            "and it must still be in the history",
            CanonLedger.EMPTY.with(deviation).deviations.isNotEmpty(),
        )
        assertTrue(
            "but not in the active list",
            CanonLedger.EMPTY.with(deviation).activeDeviations().isEmpty(),
        )
    }

    @Test
    fun `one deviation per canon entry, latest wins`() {
        val ledger = CanonLedger.EMPTY
            .with(CanonDeviation("fact-x", "canon said A", "this story has B"))
            .with(CanonDeviation("fact-x", "canon said A", "this story has C"))
        assertEquals(1, ledger.deviations.size)
        assertEquals("this story has C", ledger.deviations.single().storyHas)
    }

    @Test
    fun `the ledger explains itself for the developer panel`() {
        assertEquals(
            "This story is following canon exactly.",
            CanonLedger.EMPTY.describe(),
        )
        val described = CanonLedger.EMPTY
            .with(CanonDeviation("fact-x", "canon said A", "this story has B"))
            .describe()
        assertTrue(described, described.contains("1 deviations"))
        assertTrue(described, described.contains("canon said \"canon said A\""))
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    @Test
    fun `the bible survives persistence`() {
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val encoded = codec.encodeToString(CanonBible.serializer(), canon)
        val decoded = codec.decodeFromString(CanonBible.serializer(), encoded)
        assertEquals(canon, decoded)
        assertTrue(decoded.worldRules.any { it.forbids.isNotEmpty() })
    }

    @Test
    fun `a story with deviations survives persistence`() {
        val story = instance().copy(
            canonDeviations = CanonLedger.EMPTY.with(
                CanonDeviation("fact-x", "canon said A", "this story has B"),
            ),
        )
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val decoded = codec.decodeFromString(
            StoryInstance.serializer(),
            codec.encodeToString(StoryInstance.serializer(), story),
        )
        assertEquals(story.canonDeviations, decoded.canonDeviations)
    }

    @Test
    fun `a pack saved before the bible existed still opens`() {
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val legacy = """{"id":"pack-old","title":"Old","author":"Charaly","version":1}"""
        val decoded = codec.decodeFromString(StoryPack.serializer(), legacy)
        assertNotNull(decoded)
        assertTrue(
            "an older pack has no canon and must not pretend to",
            decoded.canon.isEmpty(),
        )
        assertFalse(decoded.canon.declaresCanonSeparately())
    }

    @Test
    fun `a story saved before deviations existed still opens`() {
        val codec = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
        val raw = dev.charaly.runtime.persistence.JsonCharalyRepository.defaultJson
            .encodeToString(StoryInstance.serializer(), instance())
            .replace(Regex("\"canonDeviations\":\\{[^}]*\\}"), "\"canonDeviations\":{}")
        val decoded = codec.decodeFromString(StoryInstance.serializer(), raw)
        assertTrue(decoded.canonDeviations.activeDeviations().isEmpty())
    }

    // ------------------------------------------------------------------
    // The bible must reference things that exist
    // ------------------------------------------------------------------

    @Test
    fun `the bible references only ids the pack actually declares`() {
        val characters = pack.characters.map { it.id }.toSet()
        val locations = pack.locations.map { it.id }.toSet()
        val threads = pack.initialStoryThreads.map { it.id }.toSet()

        canon.organizations.forEach { org ->
            org.seatLocationId?.let {
                assertTrue("${org.id} is seated at unknown $it", it in locations)
            }
            org.memberCharacterIds.forEach {
                assertTrue("${org.id} has unknown member $it", it in characters)
            }
        }
        canon.majorArcThreadIds.forEach {
            assertTrue("bible names unknown thread $it", it in threads)
        }
        assertTrue(canon.factIds.isNotEmpty())
    }
}