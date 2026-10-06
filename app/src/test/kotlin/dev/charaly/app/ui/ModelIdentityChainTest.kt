package dev.charaly.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ONE MODEL ID, END TO END.
 *
 * ## What "the same id" has to mean in code
 *
 * The brief's rule is that a model is identified by one id, carried unchanged from the
 * registry through to the native engine. The failure this prevents is concrete: the
 * registry names an imported file `local-qwen3-1-7b-4242` while the engine used to name it
 * after the file, so "is the model I selected the one that is loaded?" compared two
 * unrelated namespaces, always failed, and made a correctly loaded model read as absent.
 *
 * A chain like that cannot be checked by unit-testing any one link - each link looks
 * correct in isolation and only the *seam* is wrong. So these assertions read the wiring
 * at each hand-off and name the failure each one prevents. They are source-level because
 * they are claims about wiring, and a wiring claim is only observable in the wiring.
 *
 * `ImportedModelStoryFlowTest` covers the behavioural half: that a story bound to a model
 * keeps it across a restart.
 */
class ModelIdentityChainTest {

    private fun appSource(relativePath: String): File =
        File("src/main/kotlin/dev/charaly/app/$relativePath")

    private val viewModel = appSource("ui/CharalyViewModel.kt").readText()
    private val engine = appSource("inference/LocalLlamaInferenceEngine.kt").readText()
    private val manager = appSource("model/ModelManager.kt").readText()
    private val services = appSource("model/HuggingFaceServices.kt").readText()

    /**
     * A source file in the sibling `:charaly-runtime` module.
     *
     * Reached relative to this module's project dir, which is what the other source-level
     * tests in this module already rely on.
     */
    private fun runtimeSource(relativePath: String): File =
        File("../charaly-runtime/src/main/kotlin/dev/charaly/runtime/$relativePath")

    // ==================================================================
    // 1. registry -> load request
    // ==================================================================

    @Test
    fun `a load request carries the registry id, not the file name`() {
        assertTrue(
            "loadModelLocked must pass installedModelId = model.id. Without it the engine " +
                "falls back to the file name and every residency comparison compares two " +
                "different namespaces.",
            viewModel.contains("installedModelId = model.id"),
        )
    }

    @Test
    fun `the engine records the id it was given as its own identity`() {
        assertTrue(
            "ModelInfo.id must be the registry id from the request",
            engine.contains("id = request.installedModelId.ifBlank { file.nameWithoutExtension }"),
        )
    }

    @Test
    fun `residency is compared by id, not by path or display name`() {
        assertTrue(
            "ModelSelectionResolver must compare residentModelId to target.id",
            runtimeSource("model/ModelSelection.kt")
                .readText()
                .contains("isResident = residentModelId != null && residentModelId == target.id"),
        )
    }

    // ==================================================================
    // 2. import and download produce ids in the same registry
    // ==================================================================

    @Test
    fun `an imported file gets a stable id derived from its content`() {
        assertTrue(
            "the import id must derive from the content hash, not the path - a rename " +
                "must not orphan a story binding",
            File("src/main/kotlin/dev/charaly/app/model/ModelIds.kt").readText()
                .contains("\"local-\$slug-\${sha256.take(12)}\""),
        )
        assertTrue(
            "ModelManager must mint ids through ModelIds",
            manager.contains("ModelIds.idFor("),
        )
    }

    @Test
    fun `a downloaded file registers into the same registry type`() {
        assertTrue(
            "the download pipeline must write an InstalledModel, the same type the import " +
                "writes, so there is one list rather than two",
            runtimeSource("model/HuggingFaceModelDownloads.kt")
                .readText()
                .contains("registry.register(installed)"),
        )
    }

    // ==================================================================
    // 3. ONE directory - the bug that erased downloaded models
    // ==================================================================

    @Test
    fun `import and download resolve the same models directory`() {
        assertTrue(
            "ModelManager must use the shared path, not its own literal",
            manager.contains("CharalyPaths.models(context)"),
        )
        assertTrue(
            "HuggingFaceServices must use the shared path, not its own literal",
            services.contains("CharalyPaths.models(context)"),
        )
        assertFalse(
            "a hardcoded second models directory is exactly what made downloaded models " +
                "vanish from the registry on the next launch",
            services.contains("File(context.filesDir, \"charaly/models\")"),
        )
        assertFalse(
            "ModelManager must not hardcode its own directory either",
            manager.contains("File(context.filesDir, \"models\")"),
        )
    }

    @Test
    fun `the shared path is declared once`() {
        val paths = appSource("model/CharalyPaths.kt")
        assertTrue("there must be a single place that names the models directory", paths.isFile)
        val text = paths.readText()
        assertTrue(
            "CharalyPaths.models must exist and be the one source",
            text.contains("fun models(context: Context): File"),
        )
    }

    // ==================================================================
    // 4. story -> selection
    // ==================================================================

    @Test
    fun `the story's own binding wins over the active model`() {
        val selection = runtimeSource("model/ModelSelection.kt").readText()
        assertTrue(
            "a story bound to a deleted model must NOT silently adopt the active one - its " +
                "context budget and transcript were resolved against that file",
            selection.contains("""if (binding.installedModelId.isBlank()) {
                installed.firstOrNull { it.id == activeModelId }"""),
        )
    }

    @Test
    fun `lazy load resolves the bound model before generating`() {
        assertTrue(
            "a turn must call ensureModelResident before runtime.respond, or the chat " +
                "screens ModelNotLoaded for a model that was merely not loaded yet",
            viewModel.contains("if (!ensureModelResident())"),
        )
        assertTrue(
            "ensureModelResident must look the model up by selection.modelId",
            viewModel.contains("it.id == selection.modelId"),
        )
    }

    // ==================================================================
    // 5. one load at a time
    // ==================================================================

    @Test
    fun `native loads are serialised behind a mutex`() {
        assertTrue(
            "llama.cpp holds one model; two overlapping loads free each other's handle",
            viewModel.contains("modelLoadLock.withLock"),
        )
        assertTrue(
            "the mutex must actually wrap the load",
            viewModel.contains("private suspend fun loadModel(model: InstalledModel): EngineStatus = modelLoadLock.withLock"),
        )
    }

    // ==================================================================
    // 6. deleting a model must delete its bytes
    // ==================================================================

    @Test
    fun `deleting a model compares paths, not file references`() {
        assertTrue(
            "deleteFileBehind must compare with absoluteFile; a bare == on File compares " +
                "references and is always false, so delete() freed nothing",
            manager.contains("file.absoluteFile.parentFile == managedDir.absoluteFile"),
        )
        // The buggy form is `file.parentFile == managedDir.absoluteFile`: the substring check
        // above also matches inside the fixed expression, so the negative assertion has to
        // target the exact buggy token sequence.
        assertFalse(
            "the reference-comparison bug must not still be present",
            manager.contains("""if (file.parentFile == managedDir.absoluteFile)"""),
        )
    }
}