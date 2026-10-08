package dev.charaly.runtime.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Model recommendations.
 *
 * ## The rule under test
 *
 * The brief asks for "Fast", "Balanced" and "Quality" labels and explicitly forbids
 * inventing them. So this suite exists to prove the product never uses a performance
 * word it has not measured.
 *
 * That is a claim about strings, so it is tested by searching strings - and the first
 * test below is the one that would catch a regression.
 */
class DeviceRecommendationTest {

    private fun model(
        id: String = "m1",
        sizeBytes: Long = 2_000_000_000L,
        contextLength: Int = 4096,
    ) = InstalledModel(
        id = id,
        displayName = "Test Model",
        absolutePath = "/models/$id.gguf",
        sizeBytes = sizeBytes,
        origin = ModelOrigin.DOWNLOADED,
        architecture = "qwen2",
        quantization = "Q4_K_M",
        contextLength = contextLength,
    )

    private val roomy = DeviceCapability(
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        availableRamBytes = 8L * 1024 * 1024 * 1024,
    )

    private val tight = DeviceCapability(
        totalRamBytes = 3L * 1024 * 1024 * 1024,
        availableRamBytes = 1_500_000_000L,
    )

    private val silent = DeviceCapability(0L, 0L, null)

    private fun benchmark(modelId: String = "m1", tokensPerSecond: Double) = ModelBenchmark(
        modelId = modelId,
        loadMillis = 1200L,
        tokensPerSecond = tokensPerSecond,
        tokensMeasured = 64,
        measuredAtEpochMs = 1_700_000_000_000L,
    )

    // ------------------------------------------------------------------
    // The headline rule
    // ------------------------------------------------------------------

    /**
     * No performance word without a measurement.
     *
     * "Fast", "Balanced" and "Quality" are speed claims. Without a benchmark on this
     * device the presenter has no basis for one, and the only way to guarantee that is
     * to check the string.
     */
    @Test
    fun `no speed claim without a benchmark`() {
        for (device in listOf(roomy, tight, silent)) {
            val recommendation = ModelRecommender.recommend(model(), device)
            val text = listOf(recommendation.label, recommendation.evidence).joinToString(" ")

            for (speedWord in listOf("Fast", "Balanced", "Quality", "fast", "tok/s")) {
                assertFalse(
                    "device reported speed without a benchmark: '$text'",
                    text.contains(speedWord),
                )
            }
            assertFalse(
                "a speed claim was made with basis ${recommendation.basis}",
                recommendation.claimsSpeed,
            )
        }
    }

    @Test
    fun `a benchmark produces a speed claim`() {
        val device = roomy.copy(benchmark = benchmark(tokensPerSecond = 24.0))
        val recommendation = ModelRecommender.recommend(model(), device)

        assertEquals(RecommendationBasis.MEASURED_ON_THIS_DEVICE, recommendation.basis)
        assertTrue(recommendation.claimsSpeed)
        assertEquals("Bu cihazda Hızlı", recommendation.label)
        assertTrue("the evidence should quote the measurement: ${recommendation.evidence}",
            recommendation.evidence.contains("tok/s"))
    }

    /**
     * A benchmark for a *different* model is not evidence about this one.
     *
     * The obvious bug in a benchmark cache: the first model's numbers get attached to
     * every card, which would be both wrong and flattering.
     */
    @Test
    fun `a benchmark is only applied to the model it measured`() {
        val device = roomy.copy(benchmark = benchmark(modelId = "other-model", tokensPerSecond = 24.0))
        val recommendation = ModelRecommender.recommend(model(id = "m1"), device)

        assertFalse(
            "another model's benchmark was used for this one",
            recommendation.claimsSpeed,
        )
        assertEquals(RecommendationBasis.DEVICE_CLASS, recommendation.basis)
    }

    // ------------------------------------------------------------------
    // Tiers
    // ------------------------------------------------------------------

    @Test
    fun `performance tiers follow the measured speed`() {
        assertEquals(PerformanceTier.FAST, PerformanceTier.of(30.0))
        assertEquals(PerformanceTier.BALANCED, PerformanceTier.of(12.0))
        assertEquals(PerformanceTier.TOO_SLOW, PerformanceTier.of(3.0))
        // Exactly on the boundary counts as meeting it, so a user is never told a model
        // is unusable when it is precisely at the threshold.
        assertEquals(PerformanceTier.FAST, PerformanceTier.of(PerformanceTier.FAST_TOKENS_PER_SECOND))
        assertEquals(
            PerformanceTier.BALANCED,
            PerformanceTier.of(PerformanceTier.USABLE_TOKENS_PER_SECOND),
        )
    }

    @Test
    fun `a too-slow model is labelled honestly`() {
        val device = roomy.copy(benchmark = benchmark(tokensPerSecond = 2.5))
        val recommendation = ModelRecommender.recommend(model(), device)

        assertEquals(PerformanceTier.TOO_SLOW, recommendation.tier)
        assertFalse("a too-slow model was recommended", recommendation.isRecommended)
        assertTrue(
            "the label should not read as a positive: ${recommendation.label}",
            recommendation.label.contains("çok yavaş"),
        )
    }

    // ------------------------------------------------------------------
    // Fit, not speed
    // ------------------------------------------------------------------

    @Test
    fun `with ram figures but no benchmark the label is about fit`() {
        val recommendation = ModelRecommender.recommend(model(), roomy)
        assertEquals(RecommendationBasis.DEVICE_CLASS, recommendation.basis)
        assertEquals("Cihazınıza uygun", recommendation.label)
        assertEquals("the evidence field must be empty without a measurement", "", recommendation.evidence)
    }

    /**
     * No RAM figures means no claims at all.
     *
     * Some devices decline to report memory. Warning about a size we cannot check would
     * be inventing a problem, so the label is empty and the basis says UNKNOWN.
     */
    @Test
    fun `a silent device produces no claim`() {
        val recommendation = ModelRecommender.recommend(model(), silent)
        assertEquals(RecommendationBasis.UNKNOWN, recommendation.basis)
        assertEquals("", recommendation.label)
        assertEquals("", recommendation.memoryWarning)
    }

    /**
     * A model that will not fit is warned about.
     *
     * The arithmetic is the same one the pre-download check uses, so the library's
     * warning and the downloader's refusal cannot disagree about what fits.
     */
    @Test
    fun `an oversized model is warned about`() {
        val huge = model(sizeBytes = 7_000_000_000L, contextLength = 8192)
        val recommendation = ModelRecommender.recommend(huge, tight)
        assertTrue("expected a memory warning, got none", recommendation.memoryWarning.isNotBlank())
        assertFalse("an oversized model was recommended", recommendation.isRecommended)
        assertEquals("Bu cihaz için çok büyük", recommendation.label)
    }

    @Test
    fun `a model that fits produces no warning`() {
        val small = model(sizeBytes = 400_000_000L, contextLength = 2048)
        assertEquals("", ModelRecommender.memoryWarning(small, tight))
    }

    /**
     * A model with no known size is never warned about.
     *
     * Warning about a size we do not know would produce a permanent, wrong warning on
     * every card whose metadata was incomplete.
     */
    @Test
    fun `an unknown size produces no warning`() {
        assertEquals("", ModelRecommender.memoryWarning(model(sizeBytes = 0L), tight))
    }

    // ------------------------------------------------------------------
    // Device classes
    // ------------------------------------------------------------------

    @Test
    fun `device class is derived from reported memory`() {
        assertEquals(DeviceClass.UNKNOWN, silent.deviceClass)
        assertEquals(
            DeviceClass.TIGHT,
            DeviceCapability(3L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024).deviceClass,
        )
        assertEquals(
            DeviceClass.NORMAL,
            DeviceCapability(6L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024).deviceClass,
        )
        assertEquals(DeviceClass.ROOMY, roomy.deviceClass)
    }

    /**
     * A benchmark must have measured something.
     *
     * `require` rather than a default, because a zero-token benchmark stored on a device
     * would be indistinguishable from a real one and would then produce a confident
     * "0.0 tok/s" label.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `a benchmark with no tokens is rejected`() {
        ModelBenchmark(
            modelId = "m1",
            loadMillis = 10L,
            tokensPerSecond = 0.0,
            tokensMeasured = 0,
        )
    }

    @Test
    fun `memory release is measured, not assumed`() {
        val released = ModelBenchmark(
            modelId = "m1",
            loadMillis = 10L,
            tokensPerSecond = 20.0,
            tokensMeasured = 32,
            freeMemoryBeforeBytes = 4_000_000_000L,
            freeMemoryAfterBytes = 3_900_000_000L,
        )
        assertTrue("100 MB of churn is within tolerance", released.releasedMemory())

        val leaked = released.copy(freeMemoryAfterBytes = 1_000_000_000L)
        assertFalse("3 GB of retained memory is a leak", leaked.releasedMemory())
    }
}
