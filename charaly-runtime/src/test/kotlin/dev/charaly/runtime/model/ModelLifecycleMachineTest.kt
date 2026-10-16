package dev.charaly.runtime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model lifecycle table, asserted as data.
 *
 * The rules this file pins down are the ones that only ever showed up as device bugs:
 * a decode being unloaded out from under itself, two models believed resident at once, a
 * failure treated as permanent. Getting them wrong is silent in review and loud on a phone,
 * so they are stated once, here, and checked.
 */
class ModelLifecycleMachineTest {

    @Test
    fun `ready is not loaded`() {
        assertEquals(ModelLifecycle.READY, modelLifecycle(installed = true, verified = true))
        assertEquals(
            "READY must not be the same state as LOADED",
            ModelLifecycle.LOADED,
            modelLifecycle(installed = true, verified = true, resident = true),
        )
        assertTrue(ModelLifecycle.READY.isUsableOnDisk)
        assertTrue(ModelLifecycle.LOADED.isUsableOnDisk)
        assertFalse(ModelLifecycle.LOAD_FAILED.isUsableOnDisk)
    }

    @Test
    fun `a generation may not be unloaded out from under itself`() {
        val machine = ModelLifecycleMachine(ModelLifecycle.LOADED)
        assertTrue(machine.transition(ModelLifecycle.GENERATING) is ModelLifecycleMachine.TransitionResult.Entered)

        for (next in ModelLifecycle.entries) {
            if (next == ModelLifecycle.GENERATING) continue
            val result = machine.transition(next)
            if (next == ModelLifecycle.LOADED) {
                assertTrue(result is ModelLifecycleMachine.TransitionResult.Entered)
                assertTrue(machine.transition(ModelLifecycle.GENERATING) is ModelLifecycleMachine.TransitionResult.Entered)
            } else {
                assertTrue(
                    "GENERATING -> $next must be refused: stop the decode first",
                    result is ModelLifecycleMachine.TransitionResult.Rejected,
                )
                assertEquals(ModelLifecycle.GENERATING, machine.state)
            }
        }
    }

    @Test
    fun `the rejection explains that a decode must be stopped`() {
        val machine = ModelLifecycleMachine(ModelLifecycle.GENERATING)
        val result = machine.transition(ModelLifecycle.READY)
        assertTrue(result is ModelLifecycleMachine.TransitionResult.Rejected)
        assertTrue(
            (result as ModelLifecycleMachine.TransitionResult.Rejected).because.contains("stop"),
        )
    }

    @Test
    fun `a load must settle before a decode may start`() {
        val machine = ModelLifecycleMachine(ModelLifecycle.LOADING)
        assertTrue(machine.transition(ModelLifecycle.GENERATING) is ModelLifecycleMachine.TransitionResult.Rejected)
        assertTrue(machine.transition(ModelLifecycle.LOADED) is ModelLifecycleMachine.TransitionResult.Entered)
        assertTrue(machine.transition(ModelLifecycle.GENERATING) is ModelLifecycleMachine.TransitionResult.Entered)
    }

    @Test
    fun `unload goes back to ready, not to not-installed`() {
        val machine = ModelLifecycleMachine(ModelLifecycle.LOADED)
        assertTrue(machine.transition(ModelLifecycle.READY) is ModelLifecycleMachine.TransitionResult.Entered)
        assertEquals(ModelLifecycle.READY, machine.state)
    }

    @Test
    fun `a failed load is retryable, not terminal`() {
        val machine = ModelLifecycleMachine(ModelLifecycle.LOADING)
        assertTrue(machine.transition(ModelLifecycle.LOAD_FAILED) is ModelLifecycleMachine.TransitionResult.Entered)
        for (next in listOf(ModelLifecycle.READY, ModelLifecycle.LOAD_REQUESTED, ModelLifecycle.LOADING)) {
            assertTrue(
                "a model in LOAD_FAILED must be able to reach $next",
                machine.transition(next) is ModelLifecycleMachine.TransitionResult.Entered,
            )
        }
    }

    @Test
    fun `a load cannot begin from a state that has no model to load`() {
        for (from in listOf(ModelLifecycle.CORRUPT, ModelLifecycle.IMPORTING)) {
            val machine = ModelLifecycleMachine(from)
            assertTrue(
                "$from -> LOADING must be refused",
                machine.transition(ModelLifecycle.LOADING) is ModelLifecycleMachine.TransitionResult.Rejected,
            )
        }
    }

    @Test
    fun `the happy path walks the whole chain`() {
        val machine = ModelLifecycleMachine(ModelLifecycle.NOT_INSTALLED)
        val path = listOf(
            ModelLifecycle.IMPORTING,
            ModelLifecycle.IMPORTED,
            ModelLifecycle.VALIDATING,
            ModelLifecycle.READY,
            ModelLifecycle.LOADING,
            ModelLifecycle.LOADED,
            ModelLifecycle.GENERATING,
            ModelLifecycle.LOADED,
            ModelLifecycle.READY,
        )
        path.forEach { step ->
            assertTrue(
                "chain broke at ${machine.state} -> $step",
                machine.transition(step) is ModelLifecycleMachine.TransitionResult.Entered,
            )
        }
    }

    @Test
    fun `a file that vanished outranks a stale load failure`() {
        val staleFailure = ModelSelection(
            modelId = "m",
            displayName = "M",
            absolutePath = "/m.gguf",
            filePresent = false,
            loadFailure = "ran out of memory last month",
        )
        assertEquals(ModelBlockReason.FILE_MISSING, staleFailure.blocked)
    }

    @Test
    fun `GENERATING has exactly one way out`() {
        // The whole safety story is one fact: a running decode can only become a finished
        // decode. Everything else - unload, switch, shutdown - has to wait behind it, and
        // this is the assertion that keeps a "convenient" shortcut from being added later.
        val outs = ModelLifecycle.entries.filter {
            it != ModelLifecycle.GENERATING && ModelLifecycleMachine.isAllowed(ModelLifecycle.GENERATING, it)
        }
        assertEquals(listOf(ModelLifecycle.LOADED), outs)
    }

    @Test
    fun `LOADING can only settle into LOADED or LOAD_FAILED`() {
        val outs = ModelLifecycle.entries.filter {
            it != ModelLifecycle.LOADING && ModelLifecycleMachine.isAllowed(ModelLifecycle.LOADING, it)
        }
        assertEquals(
            "a load either lands or fails; it cannot wander",
            setOf(ModelLifecycle.LOADED, ModelLifecycle.LOAD_FAILED),
            outs.toSet(),
        )
    }
}
