package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.ThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validation: the gate.
 *
 * The tests below are phrased as *claims about a broken pack*, one per failure family,
 * because the point of a validator is what it refuses. A test that asserts "a good pack is
 * fine" proves only that the gate opens.
 */
class StoryPackValidationTest {

    private fun reportOf(pack: StoryPack): ValidationReport {
        val decoded = StoryPackDecoder.decode(PackFixtures.sampleArchive(pack))
        return ValidationReport(decoded.issues, decoded.bundle?.census())
    }

    @Test
    fun `a complete pack installs with nothing to report`() {
        val report = reportOf(PackFixtures.cleanPack())
        val blocking = report.errors.map { "${it.code}: ${it.message}" }
        assertEquals("unexpected criticals: $blocking", emptyList<ValidationIssue>(), report.errors)
    }

    @Test
    fun `a duplicate id is a critical error`() {
        val pack = PackFixtures.pack()
        val withDuplicateEvent = PackEventDefinition(
            id = "evt-opening",
            title = "Ikinci",
            trigger = EventTrigger.StoryStart(),
        )
        val bundle = StoryPackBundle.of(pack).let { it.copy(events = it.events.copy(events = it.events.events + withDuplicateEvent)) }
        val issues = StoryPackValidator.validateBundle(bundle)
        assertTrue(issues.any { it.code == IssueCode.DUPLICATE_ID && it.severity == Severity.CRITICAL })
    }

    @Test
    fun `a dangling character reference is a critical error`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            initialRelationships = pack.initialRelationships.map {
                it.copy(targetId = CharacterId("ch-ghost"))
            },
        )
        val issues = StoryPackValidator.validateReferences(broken)
        assertTrue(issues.any { it.code == IssueCode.INVALID_CHARACTER_REF && it.isBlocking })
        // Messages name people, not ids.
        assertFalse(issues.any { it.message.contains("ch-ghost") })
    }

    @Test
    fun `a dangling location reference is a critical error`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            // With the declared start map emptied, the character's own start place is what
            // applies - and it points at a place the world does not have.
            initialWorldState = pack.initialWorldState.copy(startLocations = emptyMap()),
            characters = pack.characters.map { it.copy(startingLocationId = LocationId("loc-nowhere")) },
        )
        val issues = StoryPackValidator.validateContent(broken, StoryPackBundle.of(broken))
        assertTrue(issues.any { it.code == IssueCode.INVALID_LOCATION_REF && it.isBlocking })
    }

    @Test
    fun `an event cannot reference an unknown thread`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            initialStoryThreads = emptyList(),
        )
        val issues = StoryPackValidator.validateReferences(broken) +
            StoryPackValidator.validateContent(broken, StoryPackBundle.of(broken))
        assertTrue(issues.any { it.isBlocking })
    }

    @Test
    fun `an effect that moves a ghost is refused`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            events = listOf(
                PackEventDefinition(
                    id = "evt-broken",
                    title = "Bozuk",
                    trigger = EventTrigger.StoryStart(),
                    effects = listOf(
                        EventEffect.MoveCharacter(CharacterId("ch-ghost"), LocationId("loc-ghost")),
                    ),
                ),
            ),
        )
        val issues = StoryPackValidator.validateReferences(broken)
        assertTrue(issues.any { it.code == IssueCode.INVALID_EFFECT && it.isBlocking })
    }

    @Test
    fun `a condition that watches a ghost is refused`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            events = listOf(
                PackEventDefinition(
                    id = "evt-cond",
                    title = "Koşul",
                    trigger = EventTrigger.WhenConditionMet(),
                    conditions = listOf(EventCondition.CharacterKnowsFact(CharacterId("ch-ghost"), dev.charaly.runtime.domain.FactId("fact-nope"))),
                    effects = listOf(EventEffect.SetVariable("x", "y")),
                ),
            ),
        )
        val issues = StoryPackValidator.validateReferences(broken)
        assertTrue(issues.any { it.code == IssueCode.INVALID_CONDITION && it.isBlocking })
    }

    @Test
    fun `an unschedulable nested event is refused`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            events = listOf(
                PackEventDefinition(
                    id = "evt-parent",
                    title = "Ebeveyn",
                    trigger = EventTrigger.StoryStart(),
                    effects = listOf(EventEffect.ScheduleEvent("evt-does-not-exist", 5)),
                ),
            ),
        )
        val issues = StoryPackValidator.validateReferences(broken)
        assertTrue(issues.any { it.code == IssueCode.INVALID_EVENT_REF && it.isBlocking })
    }

    @Test
    fun `an impossible starting state is refused`() {
        val broken = PackFixtures.pack().copy(locations = emptyList())
        val issues = StoryPackValidator.validateContent(broken, StoryPackBundle.of(broken))
        assertTrue(issues.any { it.code == IssueCode.IMPOSSIBLE_START && it.isBlocking })
    }

    @Test
    fun `an opening that starts nowhere real is refused`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            scenarios = pack.scenarios.map { it.copy(startLocationId = LocationId("loc-vanished")) },
        )
        val issues = StoryPackValidator.validateContent(broken, StoryPackBundle.of(broken))
        assertTrue(issues.any { it.code == IssueCode.INVALID_SCENARIO && it.isBlocking })
    }

    @Test
    fun `an objective that can never complete is refused`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            objectives = listOf(PackObjective(id = "obj-x", title = "İmkânsız", scenarioIds = listOf("scn-rooftop"))),
        )
        val issues = StoryPackValidator.validateContent(broken, StoryPackBundle.of(broken))
        assertTrue(issues.any { it.code == IssueCode.INVALID_OBJECTIVE && it.isBlocking })
    }

    @Test
    fun `a branch that changes nothing is a broken branch`() {
        val pack = PackFixtures.pack()
        val scene = pack.scenes.first()
        val broken = pack.copy(
            scenes = listOf(scene.copy(choices = listOf(SceneChoice(id = "flat", label = "Hiçbir şey yapma")))),
        )
        val issues = StoryPackValidator.validateContent(broken, StoryPackBundle.of(broken))
        assertTrue(issues.any { it.code == IssueCode.BROKEN_BRANCHING && it.isBlocking })
    }

    @Test
    fun `content nothing can reach is warned about, not refused`() {
        val pack = PackFixtures.pack()
        val orphan = dev.charaly.runtime.domain.StoryThread(
            id = ThreadId("thr-orphan"),
            title = "Kimse bilmiyor",
            status = StoryThreadStatus.ACTIVE,
        )
        val issues = StoryPackValidator.validateReachability(pack.copy(initialStoryThreads = pack.initialStoryThreads + orphan))
        val finding = issues.firstOrNull { it.subject == "thr-orphan" }
        assertEquals(Severity.WARNING, finding?.severity)
        assertFalse(finding?.isBlocking == true)
    }

    @Test
    fun `a character nobody mentions is a warning with their name on it`() {
        val pack = PackFixtures.pack()
        val lonely = dev.charaly.runtime.domain.CharacterDefinition(
            id = CharacterId("ch-lonely"),
            name = "Bernadette",
        )
        val issues = StoryPackValidator.validateReachability(pack.copy(characters = pack.characters + lonely))
        val finding = issues.firstOrNull { it.code == IssueCode.UNREACHABLE_CONTENT && it.subject == "ch-lonely" }
        assertEquals(Severity.WARNING, finding?.severity)
        assertTrue(finding?.message.orEmpty().contains("Bernadette"))
    }

    @Test
    fun `messages never contain internal ids but details always do`() {
        val pack = PackFixtures.pack().copy(
            initialRelationships = packRelationships(),
        )
        // Same broken relationship, asserted from two angles.
        val issues = StoryPackValidator.validateReferences(pack)
        val finding = issues.firstOrNull { it.code == IssueCode.INVALID_CHARACTER_REF }
        assertTrue(finding != null)
        assertFalse(finding!!.message.contains("ch-"))
        assertTrue(finding.detail.contains("ch-ghost"))
    }

    private fun packRelationships() = PackFixtures.pack().initialRelationships.map {
        it.copy(targetId = CharacterId("ch-ghost"))
    }

    @Test
    fun `a translation key that names nothing is a warning`() {
        val pack = PackFixtures.pack()
        val tables = mapOf(
            "tr" to mapOf("character.ch-marina.name" to "Marinette"),
            "en" to mapOf("character.nobody.name" to "Nobody"),
        )
        val issues = StoryPackValidator.validateLocalization(pack, tables)
        assertTrue(issues.any { it.code == IssueCode.INVALID_LOCALIZATION && it.severity == Severity.WARNING })
    }

    @Test
    fun `a pack with no translations at all warns and falls back`() {
        val issues = StoryPackValidator.validateLocalization(PackFixtures.pack(), emptyMap())
        assertEquals(1, issues.size)
        assertEquals(Severity.WARNING, issues.first().severity)
    }

    @Test
    fun `the report headline speaks Turkish in counts`() {
        val empty = ValidationReport(emptyList())
        assertEquals("Kontrol tamamlandı, sorun bulunamadı", empty.headline())

        val warnings = ValidationReport(
            listOf(
                ValidationIssue.warning(IssueCode.UNKNOWN_FILE, "u"),
                ValidationIssue.warning(IssueCode.UNKNOWN_FILE, "u"),
            ),
        )
        assertEquals("2 uyarı ile kurulabilir", warnings.headline())

        val mixed = ValidationReport(
            listOf(
                ValidationIssue.critical(IssueCode.DUPLICATE_ID, "k"),
                ValidationIssue.critical(IssueCode.DUPLICATE_ID, "k"),
                ValidationIssue.critical(IssueCode.DUPLICATE_ID, "k"),
                ValidationIssue.warning(IssueCode.UNKNOWN_FILE, "u"),
            ),
        )
        assertEquals("3 kritik hata, 1 uyarı", mixed.headline())
    }

    @Test
    fun `the census line counts content without naming internals`() {
        val report = ValidationReport(emptyList(), StoryPackBundle.of(PackFixtures.pack()).census())
        val line = report.censusLine()
        assertTrue(line.contains("2 karakter"))
        assertTrue(line.contains("2 mekan") || line.contains("2 mekân"))
        assertFalse(line.contains("ch-"))
    }

    @Test
    fun `verdict is the worst severity present`() {
        assertEquals(Severity.OK, ValidationIssue.verdictOf(emptyList()))
        assertEquals(
            Severity.WARNING,
            ValidationIssue.verdictOf(listOf(ValidationIssue.warning(IssueCode.UNKNOWN_FILE, "w"))),
        )
        assertEquals(
            Severity.CRITICAL,
            ValidationIssue.verdictOf(
                listOf(
                    ValidationIssue.warning(IssueCode.UNKNOWN_FILE, "w"),
                    ValidationIssue.critical(IssueCode.DUPLICATE_ID, "c"),
                ),
            ),
        )
    }

    @Test
    fun `activities and variables are checked when effects use them`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            events = listOf(
                PackEventDefinition(
                    id = "evt-activity",
                    title = "Aktivite",
                    trigger = EventTrigger.StoryStart(),
                    effects = listOf(EventEffect.ChangeActivity(CharacterId("ch-ghost"), CharacterActivity.WORKING)),
                ),
            ),
        )
        val issues = StoryPackValidator.validateReferences(broken)
        assertTrue(issues.any { it.code == IssueCode.INVALID_EFFECT })
    }
}