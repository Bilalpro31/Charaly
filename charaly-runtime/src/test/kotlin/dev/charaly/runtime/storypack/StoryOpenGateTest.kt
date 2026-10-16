package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.engine.StoryCreationOptions
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyError
import dev.charaly.runtime.session.GenerationUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE STORY-OPEN GATE: every story-open decision, with real installer parts.
 *
 * Phase 4 found `compatibilityFor` answering questions nobody asked - story-open resumed a
 * save pinned to a missing edition against whatever edition was active. This file pins the
 * contract the app now actually consumes (`openDecision`, wired through
 * `CharalyViewModel.openStory`):
 *
 *  - a pin that is present opens;
 *  - a pin that is gone blocks as `MIGRATION_REQUIRED` and offers only import - no pretend
 *    migration, because no migration operation exists;
 *  - a pack that is gone blocks as `PACK_MISSING` with catalog + import;
 *  - the ACTIVE edition never stands in for the PINNED one;
 *  - a cold start (restart) reaches the same verdict;
 *  - a refused open mutates the Story Instance in no way at all;
 *  - saves from before pinning keep opening on the installed edition.
 *
 * Everything runs against the real `StoryPackInstaller` / `JsonStoryPackRegistry` /
 * `PackInstallStore` / `JsonCharalyRepository` on a real temp tree - the same four objects
 * `CharalyApplication` wires - with only the inference engine faked (not the subject).
 */
class StoryOpenGateTest {

    private val packId = "pack.paris.afterdark"

    /** Installs [PackFixtures.pack] at [version] and pins a fresh story to it. */
    private suspend fun pinnedStory(
        env: PackTestEnv,
        version: String = "1.0.0",
        storyId: String = "story-gate",
    ): Pair<StoryPack, StoryInstance> {
        env.install(PackFixtures.pack(version = version))
        val pack = env.installer.load(packId, version)!!.pack
        val story = env.runtime.startStory(
            pack,
            StoryCreationOptions(instanceId = StoryInstanceId(storyId)),
        )
        return pack to story
    }

    // ------------------------------------------------------------------
    // The six decisions
    // ------------------------------------------------------------------

    @Test
    fun `a story whose pinned edition is present opens normally`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env, version = "1.1.0")

            val report = env.installer.compatibilityFor(story)
            assertEquals(PackCompatibility.COMPATIBLE, report.status)
            val decision = report.openDecision()
            assertTrue("the gate must let this through, got $decision", decision is PackOpenDecision.Open)
        }
        env.cleanup()
    }

    @Test
    fun `a missing pinned edition blocks as MIGRATION_REQUIRED with only real recovery`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env, version = "1.0.0")

            // The edition vanishes and the pack moves on - the Phase 4 scenario.
            env.store.deleteEdition(packId, "1.0.0")
            env.install(PackFixtures.pack(version = "2.0.0"))

            val decision = env.installer.compatibilityFor(story).openDecision()
            assertTrue("must be blocked, got $decision", decision is PackOpenDecision.Blocked)
            decision as PackOpenDecision.Blocked
            assertEquals(PackCompatibility.MIGRATION_REQUIRED, decision.report.status)
            assertFalse("no silent migration", decision.report.isPlayable)
            assertEquals(
                "the only real recovery is restoring the pinned edition by import",
                listOf(PackRecoveryAction.IMPORT_ARCHIVE),
                decision.recovery,
            )
            assertTrue(decision.report.message.contains("1.0.0"))
            assertTrue(decision.report.message.contains("2.0.0"))
        }
        env.cleanup()
    }

    @Test
    fun `a missing pack blocks as PACK_MISSING with catalog and import`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env)
            env.installer.delete(packId, force = true)

            val decision = env.installer.compatibilityFor(story).openDecision()
            assertTrue("must be blocked, got $decision", decision is PackOpenDecision.Blocked)
            decision as PackOpenDecision.Blocked
            assertEquals(PackCompatibility.PACK_MISSING, decision.report.status)
            assertEquals(
                listOf(PackRecoveryAction.OPEN_CATALOG, PackRecoveryAction.IMPORT_ARCHIVE),
                decision.recovery,
            )
        }
        env.cleanup()
    }

    @Test
    fun `the active edition never stands in for the pinned one`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env, version = "1.0.0")
            assertEquals("1.0.0", story.packVersion)

            env.store.deleteEdition(packId, "1.0.0")
            env.install(PackFixtures.pack(version = "2.0.0"))

            // 2.0.0 is installed, active and perfectly playable - and still not this
            // story's edition. The verdict follows the pin, never the shelf.
            assertEquals("2.0.0", env.registry.get(packId)!!.packVersion)
            val decision = env.installer.compatibilityFor(story).openDecision()
            assertEquals(PackCompatibility.MIGRATION_REQUIRED, (decision as PackOpenDecision.Blocked).report.status)
            assertEquals("1.0.0", decision.report.pinnedVersion)
            assertEquals("2.0.0", decision.report.installedVersion)
        }
        env.cleanup()
    }

    @Test
    fun `a restart reaches the same verdict for the same save`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env, version = "1.0.0")
            env.store.deleteEdition(packId, "1.0.0")
            env.install(PackFixtures.pack(version = "2.0.0"))
            val before = env.installer.compatibilityFor(story)

            // A cold start: brand-new registry, store, installer and repository over the
            // same tree - exactly what killing and reopening the app gives the flow.
            val restartedRegistry = JsonStoryPackRegistry(env.storage)
            val restartedStore = PackInstallStore(env.root.resolve("installed-packs"))
            val restartedInstaller = StoryPackInstaller(
                packs = JsonCharalyRepository(env.storage),
                registry = restartedRegistry,
                store = restartedStore,
            )
            val reopened = JsonCharalyRepository(env.storage).getInstance(story.id)!!

            val after = restartedInstaller.compatibilityFor(reopened)
            assertEquals("the decision survives the restart", before, after)
            assertEquals(
                "and so does the gate's answer",
                before.openDecision().let { it is PackOpenDecision.Blocked },
                after.openDecision().let { it is PackOpenDecision.Blocked },
            )
        }
        env.cleanup()
    }

    @Test
    fun `a refused open mutates the Story Instance in no way whatsoever`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env, version = "1.0.0")
            env.store.deleteEdition(packId, "1.0.0")
            env.install(PackFixtures.pack(version = "2.0.0"))
            val saved = env.runtime.loadStory(story.id)!!

            // The whole gate path - the exact calls `openStory` makes before it decides -
            repeat(2) {
                val decision = env.installer.compatibilityFor(saved).openDecision()
                assertTrue(decision is PackOpenDecision.Blocked)
            }

            val reloaded = env.runtime.loadStory(story.id)!!
            assertEquals("no rewrite, no touch", saved, reloaded)
            assertEquals("the pin is exactly where it was", "1.0.0", reloaded.packVersion)
            assertEquals(story.id, reloaded.id)
        }
        env.cleanup()
    }

    @Test
    fun `a save from before pinning still opens on the installed edition`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env)
            val legacy = story.copy(packVersion = "")

            val decision = env.installer.compatibilityFor(legacy).openDecision()
            assertTrue(
                "backward compatibility: unpinned saves keep playing, got $decision",
                decision is PackOpenDecision.Open,
            )
            assertEquals(PackCompatibility.UNPINNED_LEGACY, (decision as PackOpenDecision.Open).report.status)
        }
        env.cleanup()
    }

    // ------------------------------------------------------------------
    // Defence in depth: the generation path refuses too
    // ------------------------------------------------------------------

    /**
     * Even if a screen forgot the gate, a turn must not generate against substituted canon.
     *
     * With 2.0.0 active and 1.0.0 gone, the old fallback would happily run the turn on the
     * new edition's events and semantics - rewriting the story mid-sentence. `respond`
     * refuses instead, with a reason the UI turns into a sentence.
     */
    @Test
    fun `a turn on a story whose pinned edition vanished is refused, not silently re-pointed`() {
        val env = PackTestEnv()
        runBlocking {
            val (_, story) = pinnedStory(env, version = "1.0.0")
            env.store.deleteEdition(packId, "1.0.0")
            env.install(PackFixtures.pack(version = "2.0.0"))

            val updates = mutableListOf<GenerationUpdate>()
            env.runtime.respond(story, "Bir şeyler söyle.").collect { updates += it }

            val failure = updates.filterIsInstance<GenerationUpdate.Failed>().singleOrNull()
            assertNotNull("the turn must fail loudly, got $updates", failure)
            assertTrue(
                "and the reason must be the missing edition, got ${failure!!.error}",
                failure.error is CharalyError.PackUnavailable,
            )
            assertEquals("nothing was generated against the wrong canon", 0, updates.count { it is GenerationUpdate.Chunk })

            // And the story on disk is untouched by the refusal.
            val reloaded = env.runtime.loadStory(story.id)!!
            assertEquals("1.0.0", reloaded.packVersion)
            assertTrue("no transcript residue", reloaded.conversation.entries.isEmpty())
        }
        env.cleanup()
    }
}