package dev.charaly.runtime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLifecycleTest {

    @Test
    fun `an imported file is READY only when verified and present`() {
        val state = modelLifecycle(installed = true, verified = true, filePresent = true)
        assertEquals(ModelLifecycle.READY, state)
    }

    @Test
    fun `READY does not imply LOADED`() {
        val ready = modelLifecycle(installed = true, verified = true, filePresent = true)
        assertFalse(ready == ModelLifecycle.LOADED)
    }

    @Test
    fun `a resident model is LOADED, not READY`() {
        assertEquals(
            ModelLifecycle.LOADED,
            modelLifecycle(installed = true, verified = true, filePresent = true, resident = true),
        )
    }

    @Test
    fun `generation wins while resident`() {
        assertEquals(
            ModelLifecycle.GENERATING,
            modelLifecycle(installed = true, verified = true, resident = true, generating = true),
        )
    }

    @Test
    fun `a deleted bound model is FILE_MISSING, never silently another model`() {
        assertEquals(
            ModelLifecycle.FILE_MISSING,
            modelLifecycle(installed = true, verified = true, filePresent = false),
        )
    }

    @Test
    fun `an explicitly failed load is LOAD_FAILED`() {
        assertEquals(
            ModelLifecycle.LOAD_FAILED,
            modelLifecycle(installed = true, verified = true, loadFailed = true),
        )
    }

    @Test
    fun `an unsupported architecture is UNSUPPORTED`() {
        assertEquals(
            ModelLifecycle.UNSUPPORTED,
            modelLifecycle(installed = true, verified = true, unsupported = true),
        )
    }

    @Test
    fun `a failed validation is CORRUPT`() {
        assertEquals(
            ModelLifecycle.CORRUPT,
            modelLifecycle(installed = true, verified = false, validationFailed = true),
        )
    }

    @Test
    fun `an imported but unverified file is still IMPORTED, not CORRUPT`() {
        assertEquals(
            ModelLifecycle.IMPORTED,
            modelLifecycle(installed = true, verified = false),
        )
    }

    @Test
    fun `loading and requested are transient and distinct`() {
        assertEquals(
            ModelLifecycle.LOAD_REQUESTED,
            modelLifecycle(installed = true, verified = true, loadRequested = true),
        )
        assertEquals(
            ModelLifecycle.LOADING,
            modelLifecycle(installed = true, verified = true, loading = true),
        )
    }
}
