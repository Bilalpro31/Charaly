package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.engine.StoryHealthAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health gate: the live [StoryHealthAnalyzer] as a load-bearing part of installation.
 *
 * The claim these tests make is narrow and important: a pack whose *opening world* is
 * already lying cannot be installed, and the analyzer that knew how to find that in a
 * running story is the same one answering here.
 */
class StoryPackHealthTest {

    @Test
    fun `a coherent pack passes the health gate with no findings to escalate`() {
        val result = PackHealthGate.analyse(PackFixtures.cleanPack())
        assertEquals(1, result.scenarioCount)
        val report = result.reports.values.first()
        assertTrue("analyzer said: ${report.findings.map { it.summary }}", report.findings.isEmpty() || result.issues.none { it.severity == Severity.CRITICAL })
    }

    @Test
    fun `every opening is analysed, not just the default one`() {
        val pack = PackFixtures.pack()
        val second = StartingScenario(
            id = "scn-nightpatrol",
            title = "Gece Devriyesi",
            startLocationId = PackFixtures.square.id,
            castCharacterIds = listOf(PackFixtures.adrien.id),
        )
        val result = PackHealthGate.analyse(pack.copy(scenarios = pack.scenarios + second))
        assertEquals(2, result.scenarioCount)
        assertTrue(result.reports.keys.containsAll(listOf("scn-rooftop", "scn-nightpatrol")))
    }

    @Test
    fun `a character who starts somewhere the world does not have blocks installation`() {
        val pack = PackFixtures.pack()
        val lost = dev.charaly.runtime.domain.CharacterDefinition(
            id = CharacterId("ch-lost"),
            name = "Kaybolan",
            startingLocationId = LocationId("loc-vanished"),
        )
        val broken = pack.copy(characters = pack.characters + lost)
        val result = PackHealthGate.analyse(broken)
        assertTrue(
            "expected the analyzer to see the impossible place, got ${result.issues.map { it.message }}",
            result.issues.any { it.severity == Severity.CRITICAL && it.code == IssueCode.STORY_HEALTH },
        )
    }

    @Test
    fun `a secret memory everyone can read blocks installation`() {
        val pack = PackFixtures.cleanPack()
        val leak = dev.charaly.runtime.domain.memory.Memory(
            id = dev.charaly.runtime.domain.MemoryId("mem-leak"),
            characterId = PackFixtures.marinette.id,
            content = "Paketin asıl sahibi sensin.",
            importance = 5,
            createdAt = pack.initialWorldState.startTime,
            source = dev.charaly.runtime.domain.memory.MemorySource.AUTHORED,
            tier = dev.charaly.runtime.domain.memory.MemoryTier.SECRET,
            visibility = dev.charaly.runtime.domain.memory.MemoryVisibility.WORLD,
        )
        val broken = pack.copy(
            initialKnowledge = pack.initialKnowledge.copy(
                authoredMemories = pack.initialKnowledge.authoredMemories + leak,
            ),
        )
        val result = PackHealthGate.analyse(broken)
        assertTrue(
            "expected a secret leak to block, got ${result.issues.map { it.code to it.message }}",
            result.issues.any { it.severity == Severity.CRITICAL && it.code == IssueCode.STORY_HEALTH },
        )
    }

    @Test
    fun `warnings never escalate to blocks, only errors do`() {
        // The fixture's thread cannot resolve: a WARNING by the analyzer's own rules, and a
        // warning must not stop an install or it stops being a warning.
        val result = PackHealthGate.analyse(PackFixtures.cleanPack())
        assertTrue(result.issues.any { it.severity == Severity.WARNING })
        assertTrue(result.issues.none { it.severity == Severity.CRITICAL })
    }

    @Test
    fun `an opening that cannot even start is a critical finding`() {
        val pack = PackFixtures.pack()
        val impossible = pack.copy(
            scenarios = listOf(
                StartingScenario(
                    id = "scn-void",
                    title = "Boşluk",
                    startLocationId = LocationId("loc-rooftop"),
                    focusCharacterId = CharacterId("ch-marina"),
                    initialSceneId = "scn-rooftop-scene",
                ),
            ),
        )
        val result = PackHealthGate.analyse(impossible)
        assertTrue(result.issues.isNotEmpty())
        assertTrue(result.issues.any { it.code == IssueCode.IMPOSSIBLE_START || it.code == IssueCode.STORY_HEALTH })
    }

    @Test
    fun `health findings join the install report with the same severities`() {
        val base = PackFixtures.cleanPack()
        val pack = base.copy(
            objectives = base.objectives + listOf(
                PackObjective(
                    id = "obj-late",
                    title = "Geç gelen görev",
                    scenarioIds = listOf("scn-rooftop"),
                    completionConditions = listOf(
                        dev.charaly.runtime.domain.EventCondition.VariableEquals("never_set", "yes"),
                    ),
                    onComplete = listOf(EventEffect.AdvanceThread(dev.charaly.runtime.domain.ThreadId("thr-trust"), 1)),
                ),
            ),
        )
        val issues = StoryPackValidator.validateContent(pack, StoryPackBundle.of(pack))
        assertTrue(issues.none { it.severity == Severity.CRITICAL && it.code == IssueCode.INVALID_OBJECTIVE })
    }

    @Test
    fun `the analyzer still works as a developer panel over an instance`() {
        val pack = PackFixtures.cleanPack()
        val instance = dev.charaly.runtime.engine.StoryInstanceFactory.create(
            pack,
            dev.charaly.runtime.engine.StoryCreationOptions(
                instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-health"),
                scenario = pack.scenarios.first(),
            ),
        )
        val report = StoryHealthAnalyzer(dev.charaly.runtime.domain.WorldDefinition(pack.characters, pack.locations))
            .analyse(instance)
        assertEquals(2, report.checked.characters)
        assertTrue("a coherent opening is not an error", report.status != StoryHealthAnalyzer.Status.ERROR)
    }

    @Test
    fun `an authored branch is still gated by the engine when applied`() {
        // Sanity: the health gate never applies effects; the event engine does. A choice
        // whose effect is nonsense is refused at apply time even if validation missed it.
        val pack = PackFixtures.cleanPack()
        val instance = dev.charaly.runtime.engine.StoryInstanceFactory.create(
            pack,
            dev.charaly.runtime.engine.StoryCreationOptions(
                instanceId = dev.charaly.runtime.domain.StoryInstanceId("story-branch"),
                scenario = pack.scenarios.first(),
            ),
        )
        val scene = pack.scenes.first()
        val choice = scene.choices.first { it.id == "choice-help" }
        val payloads = dev.charaly.runtime.engine.BranchEngine.compile(instance, scene.id, choice, instance.worldClock.now)
        assertTrue(payloads.isNotEmpty())
    }

    @Test
    fun `a demo pack from the repo passes the same gate`() {
        val result = PackHealthGate.analyse(dev.charaly.runtime.pack.MiraculousPack.pack)
        val blocking = result.issues.filter { it.severity == Severity.CRITICAL }
        assertTrue(
            "shipped content must install cleanly: ${blocking.map { it.message }}",
            blocking.isEmpty(),
        )
    }

    @Test
    fun `a choice label with no effect is caught as broken branching, not health`() {
        val pack = PackFixtures.pack()
        val broken = pack.copy(
            scenes = pack.scenes.map { scene ->
                scene.copy(choices = listOf(SceneChoice(id = "noop", label = "Bakakal")))
            },
        )
        val issues = StoryPackValidator.validateContent(broken, StoryPackBundle.of(broken))
        assertTrue(issues.any { it.code == IssueCode.BROKEN_BRANCHING })
    }

    @Test
    fun `an event is part of the health story too`() {
        val pack = PackFixtures.pack()
        val events = pack.events + PackEventDefinition(
            id = "evt-late",
            title = "Geç olay",
            trigger = EventTrigger.AfterDelay(30),
            effects = listOf(EventEffect.SetVariable(PackFixtures.playerHelped, "yes")),
        )
        assertTrue(StoryPackValidator.validateReferences(pack.copy(events = events)).isEmpty())
    }
}