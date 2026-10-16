package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.inference.MockInferenceEngine
import dev.charaly.runtime.persistence.FileCharalyStorage
import dev.charaly.runtime.persistence.InMemoryCharalyStorage
import dev.charaly.runtime.persistence.JsonCharalyRepository
import dev.charaly.runtime.session.CharalyRuntime
import java.io.File
import java.nio.file.Files

/**
 * One install's worth of real components on a real temp tree.
 *
 * Real storage, real JSON, real registry, real installer - the only fakes in sight are the
 * inference engine (not the subject) and the clock. Everything about packaging,
 * installation, registry and saving runs exactly as it does on a device.
 */
class PackTestEnv(
    val engineVersion: String = StoryPackFormat.ENGINE_VERSION,
    val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val root: File = Files.createTempDirectory("charaly-pack-test").toFile()

    val storage = FileCharalyStorage(root.resolve("docs"))

    val repository = JsonCharalyRepository(storage)

    val registry = JsonStoryPackRegistry(storage)

    val store = PackInstallStore(root.resolve("installed-packs"))

    var activeStories: Map<String, Int> = emptyMap()

    val installer = StoryPackInstaller(
        packs = repository,
        registry = registry,
        store = store,
        engineVersion = engineVersion,
        nowMs = nowMs,
        storyCountFor = { packId -> activeStories[packId] ?: 0 },
    )

    /**
     * The inference engine stands in for llama.cpp (it is not the subject here), but it is
     * *resident* the way a device's would be after a model load - otherwise every chat turn
     * would honestly report "no model" and the pipeline beyond it would go untested.
     */
    val engine = MockInferenceEngine(
        initiallyLoaded = true,
        info = dev.charaly.runtime.inference.ModelInfo(
            id = "test-model",
            path = "models/test-model.gguf",
            displayName = "Test Model",
        ),
    )

    val runtime = CharalyRuntime(
        repository = repository,
        engine = engine,
        editions = installer,
    )

    /** A second, independent "device": the destination of an export/import hop. */
    fun peer(): PackTestEnv = PackTestEnv(engineVersion = engineVersion, nowMs = nowMs)

    suspend fun install(pack: StoryPack = PackFixtures.pack()): StoryPackInstaller.ImportResult =
        installer.import(PackFixtures.sampleArchive(pack), source = InstalledPackRecord.SOURCE_BUILTIN)

    suspend fun installArchive(bytes: ByteArray, source: String = InstalledPackRecord.SOURCE_IMPORT) =
        installer.import(bytes, source = source)

    fun tempFile(name: String): File = root.resolve(name)

    fun cleanup() {
        root.deleteRecursively()
    }
}

/** An in-memory env for tests that need speed rather than durability. */
fun memoryRepository(): JsonCharalyRepository = JsonCharalyRepository(InMemoryCharalyStorage())