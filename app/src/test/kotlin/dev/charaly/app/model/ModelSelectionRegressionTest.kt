package dev.charaly.app.model

import dev.charaly.runtime.model.EngineCapabilities
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelBinding
import dev.charaly.runtime.model.ModelBindingResolver
import dev.charaly.runtime.model.ModelBlockReason
import dev.charaly.runtime.model.ModelSelection
import dev.charaly.runtime.model.ModelSelectionResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MODEL SELECTION REGRESSION.
 *
 * ## What this guards
 *
 * `ModelSelectionResolver` is the single authority for "which model will speak".
 * Three properties are load-bearing and were each broken at some point:
 *
 * 1. A story bound to an installed model uses THAT model, even when another is active.
 * 2. A story bound to a model that is no longer installed does NOT silently adopt the
 *    active model - that would swap the file underneath a transcript that was resolved
 *    against a different one.
 * 3. A story with no binding adopts the active model, because it was never bound to
 *    anything else.
 *
 * These are pure functions, so they are tested directly rather than through the UI.
 */
class ModelSelectionRegressionTest {

    private val installed = InstalledModel(
        id = "local-test-1",
        displayName = "Test Model",
        absolutePath = "/models/test.gguf",
        sizeBytes = 4096L,
    )

    private val other = InstalledModel(
        id = "local-test-2",
        displayName = "Other Model",
        absolutePath = "/models/other.gguf",
        sizeBytes = 8192L,
    )

    // ==================================================================
    // 1. Bound installed model wins
    // ==================================================================

    @Test
    fun `a story bound to an installed model uses that model, not the active one`() {
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = installed,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(installed, other),
            activeModelId = other.id,
            fileExists = { true },
        )

        assertEquals("the bound model must win over the active one", installed.id, selection.modelId)
        assertEquals(installed.displayName, selection.displayName)
        assertEquals(installed.absolutePath, selection.absolutePath)
        assertTrue("a bound, present, supported model can generate", selection.canGenerate)
    }

    @Test
    fun `a bound model that is resident is reported as ready`() {
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = installed,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(installed),
            activeModelId = installed.id,
            residentModelId = installed.id,
            fileExists = { true },
        )

        assertTrue("bound + resident means ready", selection.isReady)
        assertFalse("ready means no engine load is needed", selection.needsEngineLoad)
    }

    @Test
    fun `a bound model that is not resident still can generate`() {
        // Residency is a performance fact, not a permission. A model that is still
        // loading must not be reported as unbound.
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = installed,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(installed),
            activeModelId = installed.id,
            residentModelId = null,
            fileExists = { true },
        )

        assertTrue("a bound model can generate before it is resident", selection.canGenerate)
        assertTrue("and it needs an engine load", selection.needsEngineLoad)
    }

    // ==================================================================
    // 2. Missing bound model does not silently fallback
    // ==================================================================

    @Test
    fun `a story bound to a deleted model does not adopt the active model`() {
        // The invariant: a story whose bound model was deleted does NOT silently adopt
        // the active model. Its transcript, sampler settings and context budget were
        // resolved against that file, and swapping the file underneath them mid-story is
        // exactly the kind of quiet change this codebase refuses everywhere else.
        //
        // The binding is preserved (so the model can be re-imported and the story keeps
        // its identity), but the selection reports nothing bound and NOT_INSTALLED.
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = installed,
            userProfileId = null,
        )
        assertTrue("the binding itself is preserved", binding.installedModelId == installed.id)

        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(other),  // `installed` is gone
            activeModelId = other.id,
            fileExists = { true },
        )

        assertFalse(
            "a missing bound model must not adopt the active model",
            selection.isBound,
        )
        assertEquals(
            "a missing bound model must block generation",
            ModelBlockReason.NOT_INSTALLED,
            selection.blocked,
        )
        assertFalse("and must not be reported as generatable", selection.canGenerate)
    }

    @Test
    fun `a bound model whose file is deleted reports FILE_MISSING`() {
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = installed,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(installed),
            activeModelId = installed.id,
            fileExists = { false },
        )

        assertEquals(ModelBlockReason.FILE_MISSING, selection.blocked)
        assertFalse(selection.canGenerate)
    }

    @Test
    fun `a bound model that failed to load reports LOAD_FAILED`() {
        val failed = installed.copy(
            compatibility = installed.compatibility.copy(
                loadFailed = true,
                failureReason = "llama.cpp could not read this file",
            ),
        )
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = failed,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(failed),
            activeModelId = failed.id,
            fileExists = { true },
        )

        assertEquals(ModelBlockReason.LOAD_FAILED, selection.blocked)
        assertEquals("llama.cpp could not read this file", selection.loadFailure)
    }

    // ==================================================================
    // 3. Unbound story uses the active model
    // ==================================================================

    @Test
    fun `a story with no binding adopts the active model`() {
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = null,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(installed, other),
            activeModelId = other.id,
            fileExists = { true },
        )

        assertEquals("an unbound story uses the active model", other.id, selection.modelId)
        assertTrue(selection.canGenerate)
    }

    @Test
    fun `a story with no binding and no active model is not bound`() {
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = null,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(installed),
            activeModelId = "",
            fileExists = { true },
        )

        assertFalse("nothing is bound", selection.isBound)
        assertEquals(ModelBlockReason.NOT_INSTALLED, selection.blocked)
    }

    // ==================================================================
    // 4. Engine support
    // ==================================================================

    @Test
    fun `an unknown architecture is still allowed to load`() {
        // An unread header means "unknown", and refusing to load a real model because
        // its header has not been parsed yet would be the exact failure this type
        // exists to remove.
        val unknown = installed.copy(architecture = "")
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = unknown,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(unknown),
            activeModelId = unknown.id,
            fileExists = { true },
        )

        assertTrue("a blank architecture must not block", selection.engineSupports)
        assertTrue(selection.canGenerate)
    }

    @Test
    fun `a known unsupported architecture is reported as such`() {
        val unsupported = installed.copy(architecture = "unsupported-arch")
        val binding = ModelBindingResolver.resolve(
            packDefaultProfileId = "balanced",
            installedModel = unsupported,
            userProfileId = null,
        )
        val selection = ModelSelectionResolver.resolve(
            binding = binding,
            installed = listOf(unsupported),
            activeModelId = unsupported.id,
            fileExists = { true },
            engineSupports = { false },
        )

        assertEquals(ModelBlockReason.UNSUPPORTED, selection.blocked)
        assertFalse(selection.canGenerate)
    }

    // ==================================================================
    // 5. New-story resolution
    // ==================================================================

    @Test
    fun `a new story resolves to the requested model when it is installed`() {
        val selection = ModelSelectionResolver.resolveForNewStory(
            packDefaultProfileId = "balanced",
            requestedModelId = installed.id,
            installed = listOf(installed, other),
            activeModelId = other.id,
            fileExists = { true },
        )

        assertEquals(installed.id, selection.modelId)
    }

    @Test
    fun `a new story falls back to the active model when the requested one is not installed`() {
        val selection = ModelSelectionResolver.resolveForNewStory(
            packDefaultProfileId = "balanced",
            requestedModelId = "not-installed",
            installed = listOf(other),
            activeModelId = other.id,
            fileExists = { true },
        )

        assertEquals("an unresolvable request falls back to the active model", other.id, selection.modelId)
    }

    @Test
    fun `a new story with no request and no active model is not bound`() {
        val selection = ModelSelectionResolver.resolveForNewStory(
            packDefaultProfileId = "balanced",
            requestedModelId = "",
            installed = listOf(installed),
            activeModelId = "",
            fileExists = { true },
        )

        assertFalse(selection.isBound)
    }

    // ==================================================================
    // 6. The legacy flag shape
    // ==================================================================

    @Test
    fun `the legacy ready flag still means what it meant`() {
        val ready = ModelSelection.ofReadyFlag(true)
        assertTrue(ready.isResident)
        assertTrue(ready.canGenerate)

        val notReady = ModelSelection.ofReadyFlag(false)
        assertFalse(notReady.isResident)
        assertFalse(notReady.canGenerate)
    }

    @Test
    fun `a default selection is not bound and cannot generate`() {
        val selection = ModelSelection.NONE
        assertFalse(selection.isBound)
        assertFalse(selection.canGenerate)
        assertEquals(ModelBlockReason.NOT_INSTALLED, selection.blocked)
    }
}