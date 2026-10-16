package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryInstanceId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stress: the pack system under repetition and abuse.
 *
 * Cycles, not vibes. Every assertion here is the kind that only fails after the twentieth
 * iteration - leaks in staging, registries that accumulate ghosts, saves that rot when a
 * pack is churned, installs that leave a file behind and lie about it.
 */
class StoryPackStressTest {

    @Test
    fun `twenty import and delete cycles leave the device exactly as clean as it started`() {
        val env = PackTestEnv()
        runBlocking {
            repeat(20) { cycle ->
                val result = env.installer.import(PackFixtures.sampleArchive(PackFixtures.pack()))
                assertTrue(result is StoryPackInstaller.ImportResult.Installed)
                val deleted = env.installer.delete("pack.paris.afterdark", force = true)
                assertTrue(deleted is StoryPackInstaller.DeleteOutcome.Deleted)

                assertEquals("cycle $cycle leaked a claim", emptyList<Any>(), env.registry.list())
                assertEquals("cycle $cycle leaked content", emptyList<Any>(), env.store.listEditions("pack.paris.afterdark"))
            }
            env.installer.sweep()
            assertEquals(emptyList<Any>(), env.store.orphanEditions(emptyMap()))
        }
        env.cleanup()
    }

    @Test
    fun `twenty export and import hops never drift`() {
        val source = PackTestEnv()
        var current = PackFixtures.sampleArchive()
        runBlocking {
            repeat(20) { hop ->
                val env = PackTestEnv()
                val result = env.installer.import(current)
                assertTrue("hop $hop: ${(result as? StoryPackInstaller.ImportResult.Rejected)?.report?.debugLines()}", result is StoryPackInstaller.ImportResult.Installed)
                val reloaded = env.installer.load("pack.paris.afterdark")!!.pack
                assertEquals(PackFixtures.pack(), reloaded)
                current = env.installer.export("pack.paris.afterdark")!!
                env.cleanup()
            }
        }
        source.cleanup()
    }

    @Test
    fun `ten stories of one pack churn without interfering`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val stories = (1..10).map { env.runtime.startStory(pack, StoryInstanceId("story-churn-$it")) }

            stories.forEachIndexed { index, story ->
                val advanced = env.runtime.advanceLatest(story.id, dev.charaly.runtime.domain.StoryDuration(minutes = (index + 1) * 30L))!!
                env.runtime.takeChoice(
                    advanced,
                    "scn-rooftop-scene",
                    if (index % 2 == 0) "choice-help" else "choice-betray",
                )
            }

            val finals = env.runtime.listInstances()
            assertEquals(10, finals.size)
            val helped = finals.count { it.worldState.variables[PackFixtures.playerHelped]?.value == "yes" }
            val betrayed = finals.count { it.worldState.variables[PackFixtures.playerHelped]?.value == "no" }
            assertEquals(5, helped)
            assertEquals(5, betrayed)
        }
        env.cleanup()
    }

    @Test
    fun `ten editions churn without losing an old story's content`() {
        val env = PackTestEnv()
        runBlocking {
            (1..10).forEach { minor ->
                env.install(PackFixtures.pack(version = "1.$minor.0"))
            }
            val oldest = env.runtime.startStory(
                env.installer.editionFor("pack.paris.afterdark", "1.1.0")!!,
                StoryInstanceId("story-from-first"),
            )
            assertEquals("1.1.0", oldest.packVersion)
            assertEquals("1.1.0", env.runtime.packFor(oldest)!!.semanticVersion)
            assertEquals(10, env.store.listEditions("pack.paris.afterdark").size)
        }
        env.cleanup()
    }

    @Test
    fun `a hundred hostile documents are all refused without a crash`() {
        val env = PackTestEnv()
        val traversal = java.io.ByteArrayOutputStream().let { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry(StoryPackFormat.MANIFEST_PATH))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("../../escape.json"))
                zip.write("x".toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }
        val hostile = listOf(
            ByteArray(0),
            ByteArray(1),
            ByteArray(64) { 0 },
            ByteArray(1024) { it.toByte() },
            "not json".toByteArray(),
            "{\"schemaVersion\":1}".toByteArray(),
            "{}".toByteArray(),
            PackFixtures.sampleArchive().copyOfRange(0, 200),
            StoryPackArchive.write(mapOf("manifest.json" to "{}".toByteArray())),
            traversal,
        )
        runBlocking {
            repeat(100) { index ->
                val bytes = hostile[index % hostile.size]
                val outcome = runCatching { env.installer.import(bytes) }
                assertTrue("attempt $index must not explode", outcome.isSuccess)
                assertTrue(outcome.getOrNull() is StoryPackInstaller.ImportResult.Rejected)
            }
            assertEquals(emptyList<Any>(), env.registry.list())
        }
        env.cleanup()
    }

    @Test
    fun `a hundred validation cases produce a hundred deterministic verdicts`() {
        val pack = PackFixtures.pack()
        val cases = buildList<(StoryPack) -> StoryPack> {
            add { it }
            add { it.copy(characters = it.characters + it.characters.first()) }
            add { it.copy(locations = emptyList()) }
            add { it.copy(characters = emptyList()) }
            add { it.copy(scenarios = emptyList()) }
            add { it.copy(initialStoryThreads = emptyList()) }
            add { it.copy(objectives = emptyList()) }
            add { it.copy(scenes = emptyList()) }
            add { it.copy(initialKnowledge = dev.charaly.runtime.domain.InitialKnowledge()) }
            add { it.copy(events = emptyList()) }
        }
        fun score(mutated: StoryPack): Int {
            val bundle = StoryPackBundle.of(mutated)
            return StoryPackValidator.validateBundle(bundle).size +
                StoryPackValidator.validateContent(mutated, bundle).size +
                StoryPackValidator.validateReferences(mutated).size +
                StoryPackValidator.validateReachability(mutated).size +
                StoryPackValidator.validateBranching(mutated).size
        }

        val verdicts = (0 until 100).map { index ->
            // A mutation that cannot even construct is itself a verdict (the schema refused
            // it), and it is recorded the same way every time.
            val mutated = runCatching { cases[index % cases.size](pack) }.getOrElse { return@map Int.MIN_VALUE }
            score(mutated)
        }
        // Deterministic: the same case scored twice gives the same count.
        val repeated = (0 until 100).map { index ->
            val mutated = runCatching { cases[index % cases.size](pack) }.getOrElse { return@map Int.MIN_VALUE }
            score(mutated)
        }
        assertEquals(verdicts, repeated)
    }

    @Test
    fun `a restart during install is survivable`() {
        val env = PackTestEnv()
        runBlocking {
            val staging = env.store.stage("power-cut")
            staging.resolve(StoryPackFormat.MANIFEST_PATH).writeText("{ partial")

            // "Restart": a brand new installer over the same tree, then housekeeping.
            val second = PackTestEnv()
            val sameTree = StoryPackInstaller(env.repository, env.registry, env.store)
            sameTree.sweep()

            assertEquals(emptyList<Any>(), env.registry.list())
            assertEquals(emptyList<Any>(), env.store.listEditions("pack.paris.afterdark"))
            assertTrue("staging is gone after the restart", !staging.exists())
            second.cleanup()
        }
        env.cleanup()
    }

    @Test
    fun `a restart after install finds the pack and its assets`() {
        val env = PackTestEnv()
        runBlocking { env.install(PackFixtures.pack()) }

        val registry = JsonStoryPackRegistry(env.storage)
        val store = PackInstallStore(env.root.resolve("installed-packs"))
        runBlocking {
            registry.reload()
            val record = registry.get("pack.paris.afterdark")!!
            val loaded = store.loadEdition(record.packId, record.packVersion)!!
            assertEquals(PackFixtures.pack(), loaded.pack)
            assertEquals("1.0.0", loaded.manifest.packVersion)
        }
        env.cleanup()
    }

    @Test
    fun `a large pack with a large asset set installs and exports`() {
        val env = PackTestEnv()
        runBlocking {
            val pack = PackFixtures.pack()
            val content = StoryPackCodec.encode(pack)
            val files = LinkedHashMap<String, ByteArray>()
            content.forEach { (path, text) -> files[path] = text.toByteArray(Charsets.UTF_8) }
            // 60 small assets and one 8 MB payload: the shape of a pack with real art.
            // Pseudo-random payloads on purpose: what ships in `assets/` is compressed
            // imagery, and a pile of zeros would trip the zip-bomb ratio guard - correctly.
            val random = java.util.Random(7)
            repeat(60) { index ->
                files["assets/gallery/frame-$index.webp"] = ByteArray(2_048).also(random::nextBytes)
            }
            files["assets/backgrounds/paris_rooftop.webp"] = ByteArray(8 * 1024 * 1024).also(random::nextBytes)
            val manifest = PackManifestBuilder.build(pack, files)
            val all = LinkedHashMap<String, ByteArray>()
            all[StoryPackFormat.MANIFEST_PATH] = StoryPackFormat.manifestJson
                .encodeToString(PackManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            all.putAll(files)

            val result = env.installer.import(StoryPackArchive.write(all))
            assertTrue(result is StoryPackInstaller.ImportResult.Installed)
            val assets = env.store.assetRoot("pack.paris.afterdark", "1.0.0")
            assertEquals(61, assets.listAssets().size)

            val exported = env.installer.export("pack.paris.afterdark")!!
            assertEquals(61, StoryPackArchive.read(exported).assets.size)
        }
        env.cleanup()
    }

    @Test
    fun `churning packs never disturbs existing saves`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack())
            val pack = env.installer.load("pack.paris.afterdark")!!.pack
            val story = env.runtime.startStory(pack, StoryInstanceId("story-untouchable"))

            repeat(10) {
                env.install(PackFixtures.pack(version = "1.${it + 2}.0"))
                env.installer.delete("pack.paris.afterdark", force = true)
                env.install(PackFixtures.pack(version = "2.$it.0"))
            }

            val reloaded = env.runtime.loadStory(story.id)!!
            assertEquals(story.conversation.size, reloaded.conversation.size)
            assertEquals(story.worldState.worldClock.now, reloaded.worldState.worldClock.now)
        }
        env.cleanup()
    }

    @Test
    fun `an old story is resumed long after its pack moved on`() {
        val env = PackTestEnv()
        runBlocking {
            env.install(PackFixtures.pack(version = "1.0.0"))
            val story = env.runtime.startStory(
                env.installer.editionFor("pack.paris.afterdark", "1.0.0")!!,
                StoryInstanceId("story-long"),
            )
            (2..10).forEach { env.install(PackFixtures.pack(version = "1.$it.0")) }

            val compatibility = env.installer.compatibilityFor(story)
            assertTrue(compatibility.isPlayable)
            val resumed = env.runtime.advanceLatest(story.id, dev.charaly.runtime.domain.StoryDuration(minutes = 45))!!
            assertEquals("1.0.0", env.runtime.packFor(resumed)!!.semanticVersion)
        }
        env.cleanup()
    }
}