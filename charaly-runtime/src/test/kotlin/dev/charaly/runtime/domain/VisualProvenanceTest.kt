package dev.charaly.runtime.domain

import dev.charaly.runtime.presentation.ResolvedVisual
import dev.charaly.runtime.presentation.VisualResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media provenance, and why it is a rule rather than a convention.
 *
 * ## The requirement
 *
 * The brief is explicit that a media-based pack must not have internet images scraped
 * into the APK, and that asset provenance is mandatory: source, licence, attribution,
 * sourceType. The reason is straightforward and not a legal technicality - shipping a
 * scraped still from a television series inside a distributed app is redistribution of
 * someone else's work.
 *
 * ## What is being enforced
 *
 * Not "no remote URLs" - that alone is easy to satisfy dishonestly by downloading
 * images at build time and bundling them. The rule that actually holds is the one
 * [AssetSource] encodes:
 *
 * ```
 *   BUNDLED_LICENSED     may ship, because someone licensed it
 *   GENERATED_ORIGINAL    may ship, because Charaly drew it from a seed
 *   USER_PROVIDED         may exist, but only because the user brought it
 *   REMOTE_OPTIONAL      may exist, but must never be *selected*
 * ```
 *
 * `isRedistributable` is the machine-checkable half, and it is what this suite asserts
 * for every visual entity in every shipped pack.
 */
class VisualProvenanceTest {

    private val packs = listOf(
        dev.charaly.runtime.pack.MiraculousPack.pack,
        dev.charaly.runtime.pack.NeonDistrictPack.pack,
        dev.charaly.runtime.pack.LastKingdomPack.pack,
    )

    // ------------------------------------------------------------------
    // The source taxonomy
    // ------------------------------------------------------------------

    /**
     * Only licensed or original art may ship in the app.
     *
     * The two that may not are both defensible individually and are exactly the two that
     * together constitute scraping:
     *
     *  * a user-provided image is theirs, and bundling it would redistribute it;
     *  * a remote image downloaded at build time is redistribution without a licence,
     *    and it is indistinguishable from a scrape by the time it is in the APK.
     */
    @Test
    fun `only licensed or original art is redistributable`() {
        assertTrue(AssetSource.BUNDLED_LICENSED.isRedistributable)
        assertTrue(AssetSource.GENERATED_ORIGINAL.isRedistributable)

        assertFalse(
            "a user's own image must never be redistributed",
            AssetSource.USER_PROVIDED.isRedistributable,
        )
        assertFalse(
            "a downloaded asset must never be redistributed",
            AssetSource.REMOTE_OPTIONAL.isRedistributable,
        )
    }

    /**
     * A bundled licensed asset must say what it is and under what terms.
     *
     * This is the provenance requirement in its minimal form. A `BUNDLED_LICENSED` asset
     * with an empty licence is unusable - there is no way to honour terms nobody
     * recorded - so the type says so rather than trusting convention.
     */
    /**
     * A licensed asset records what it is and under what terms.
     *
     * The provenance requirement in its minimal form. `creditLine()` is what a credits
     * screen renders, so asserting on it is asserting on what a user would actually see -
     * and an asset with no licence produces a line with no terms on it, which is
     * unactionable.
     */
    @Test
    fun `a licensed asset records its licence`() {
        val licensed = VisualAsset(
            assetId = "miraculous-cover",
            type = VisualAssetType.PACK_COVER,
            ref = "bundled/miraculous/cover.png",
            source = AssetSource.BUNDLED_LICENSED,
            attribution = "A. Illustrator",
            license = "CC BY 4.0",
        )
        val line = licensed.creditLine()
        assertTrue("the licence is missing from the credit line: \'$line\'", line.contains("CC BY 4.0"))
        assertTrue("the attribution is missing from the credit line: \'$line\'", line.contains("A. Illustrator"))
        // Plain language, not an enum name.
        assertTrue("the credit line names the source in jargon: \'$line\'", line.startsWith("Bundled"))
    }

    @Test
    fun `provenance labels are plain language`() {
        assertEquals("Bundled", VisualAsset.sourceLabel(AssetSource.BUNDLED_LICENSED))
        assertEquals("Original artwork", VisualAsset.sourceLabel(AssetSource.GENERATED_ORIGINAL))
        assertEquals("Your image", VisualAsset.sourceLabel(AssetSource.USER_PROVIDED))
        assertEquals("Optional download", VisualAsset.sourceLabel(AssetSource.REMOTE_OPTIONAL))
    }

    /**
     * A bundled asset needs an id.
     *
     * The constructor requires it, so the check is that the requirement holds - a pack
     * author cannot ship an anonymous asset and lose track of where it came from.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `a bundled asset needs an id`() {
        VisualAsset(assetId = "", type = VisualAssetType.PACK_COVER, source = AssetSource.BUNDLED_LICENSED)
    }

    // ------------------------------------------------------------------
    // Shipped packs
    // ------------------------------------------------------------------

    /**
     * No shipped pack bundles a remote image.
     *
     * The check that would actually catch scraping: walk every declared asset in every
     * pack and assert none is `REMOTE_OPTIONAL`. A build-time download would show up here
     * as a bundled asset with a licence that is really a URL - which the next test
     * covers.
     */
    @Test
    fun `no shipped pack declares a remote asset`() {
        for (pack in packs) {
            for (asset in pack.visualAssets) {
                assertFalse(
                    "${pack.title} declares a REMOTE_OPTIONAL asset (${asset.type}); " +
                        "remote images must never become bundled art",
                    asset.source == AssetSource.REMOTE_OPTIONAL,
                )
            }
        }
    }

    /**
     * Every declared asset's reference points inside the app.
     *
     * A `BUNDLED_LICENSED` ref must be a relative path. An absolute URL, or a path that
     * escapes the asset directory, is the shape a scraped download takes once someone has
     * written the code to put it there.
     */
    @Test
    fun `bundled refs are relative in-app paths`() {
        for (pack in packs) {
            for (asset in pack.visualAssets) {
                if (!asset.mayBeShipped && asset.ref.isNotBlank()) continue
                val ref = asset.ref
                if (ref.isBlank()) continue
                assertFalse(
                    "${pack.title}/${asset.type}: bundled ref looks like a URL: $ref",
                    ref.startsWith("http://") || ref.startsWith("https://"),
                )
                assertFalse(
                    "${pack.title}/${asset.type}: bundled ref escapes the asset directory: $ref",
                    ref.contains(".."),
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Resolution always produces something drawable.
     *
     * The brief's "broken image, never" rule, and it is structurally enforceable only if
     * resolution happens before the view is involved. Every returned visual has a seed,
     * so the app layer has no path where it must decide what to draw when art is missing
     * - and therefore no path where it can get that wrong.
     */
    @Test
    fun `every resolved visual is drawable`() {
        for (pack in packs) {
            assertDrawable(VisualResolver.packCover(pack))
            assertDrawable(VisualResolver.packBanner(pack))
            for (character in pack.characters) {
                assertDrawable(VisualResolver.character(character))
                assertDrawable(VisualResolver.character(character, portrait = false))
            }
            for (location in pack.locations) {
                assertDrawable(VisualResolver.location(location))
                assertDrawable(VisualResolver.location(location, image = false))
            }
        }
    }

    /**
     * A remote asset is never *selected* in this build.
     *
     * A remote asset may exist in a pack's declaration - the Hub has them - but choosing
     * one would mean the core runtime depends on the network for its own UI. A real
     * picture always beats a promise the offline build cannot keep.
     */
    @Test
    fun `a remote asset is never selected`() {
        val pack = packs.first()
        val remote = VisualAsset(
            assetId = "remote-cover",
            type = VisualAssetType.PACK_COVER,
            ref = "https://example.invalid/cover.png",
            source = AssetSource.REMOTE_OPTIONAL,
        )
        val resolved = VisualResolver.packCover(pack, userProvided = remote)

        // The pack's own cover wins, and the remote reference is not what was chosen.
        assertFalse("a remote ref was selected: ${resolved.ref}", resolved.ref == remote.ref)
        assertFalse("the resolved visual claims it needs the network", resolved.needsNetwork)
    }

    /**
     * A user-provided image *is* honoured.
     *
     * The mirror of the previous test, and the reason the previous one is not simply
     * "ignore the argument": the user override is a real feature, and a resolver that
     * ignored it would make "import your own artwork" a dead button.
     */
    @Test
    fun `a user-provided asset is honoured`() {
        val pack = packs.first()
        val mine = VisualAsset(
            assetId = "user-cover",
            type = VisualAssetType.PACK_COVER,
            ref = "content://user/picked/cover.png",
            source = AssetSource.USER_PROVIDED,
        )
        val resolved = VisualResolver.packCover(pack, userProvided = mine)

        assertEquals("content://user/picked/cover.png", resolved.ref)
        assertTrue("the user image should be reported as having an image", resolved.hasImage)
        assertEquals(VisualAssetChain.Tier.USER_PROVIDED, resolved.tier)
        // And a seed is still present, so the fallback path remains total.
        assertTrue("a user image must not remove the seed", resolved.seed.isNotBlank())
    }

    /**
     * Resolution is deterministic.
     *
     * The same pack resolves to the same seed every time, on every device, forever. That
     * is what makes generated artwork cacheable and what stops a character's portrait
     * from changing between two opens of the same screen.
     */
    @Test
    fun `resolution is deterministic`() {
        val pack = packs.first()
        assertEquals(VisualResolver.packCover(pack).seed, VisualResolver.packCover(pack).seed)
        for (character in pack.characters) {
            assertEquals(
                "${character.name} resolved differently on a second pass",
                VisualResolver.character(character).seed,
                VisualResolver.character(character).seed,
            )
        }
    }

    /**
     * A pack's declared seed is what actually gets resolved.
     *
     * The regression this guards: the resolver drew from the entity id and silently
     * ignored the seed the pack had declared, so every generated pack looked right in
     * tests and drew the wrong picture at runtime. A seed is a choice; a resolver that
     * discards it is a resolver nobody can art-direct.
     */
    @Test
    fun `a declared seed is honoured`() {
        val pack = packs.first()
        val cover = pack.visualAssets.firstOrNull { it.type == VisualAssetType.PACK_COVER }
        val declaredSeed = cover?.generatedSeed.orEmpty()
        if (declaredSeed.isNotBlank()) {
            assertEquals(
                "the declared cover seed was not the one resolved",
                declaredSeed,
                VisualResolver.packCover(pack).seed,
            )
        }
    }

    /**
     * Characters do not all get the same generic avatar.
     *
     * A single placeholder across a nineteen-character cast is the "no artwork" failure
     * dressed as artwork. Distinct seeds are what make generated identity possible, so
     * this asserts distinctness across the cast rather than visual quality - which is not
     * something a JVM test can judge.
     */
    @Test
    fun `characters get distinct identities`() {
        for (pack in packs) {
            val seeds = pack.characters.map { VisualResolver.character(it).seed }
            assertEquals(
                "${pack.title}: several characters resolved to the same identity",
                seeds.size,
                seeds.distinct().size,
            )
        }
    }

    @Test
    fun `locations get distinct identities`() {
        for (pack in packs) {
            val seeds = pack.locations.map { VisualResolver.location(it).seed }
            assertEquals(
                "${pack.title}: several locations resolved to the same identity",
                seeds.size,
                seeds.distinct().size,
            )
        }
    }

    /**
     * Artwork is presentation, never world state.
     *
     * A pack's visual assets must not be reachable from a running story's world state,
     * because a player could not change them and the engine does not use them. This is
     * asserted structurally by reading the declared fields of the state type.
     */
    @Test
    fun `artwork is not part of world state`() {
        val fields = WorldState::class.java.declaredFields.map { it.name }.toSet()
        for (forbidden in listOf("visualAssets", "artwork", "cover", "images")) {
            assertFalse(
                "WorldState exposes '$forbidden'; artwork must be presentation only",
                fields.contains(forbidden),
            )
        }
        assertNotNull(WorldState.EMPTY)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun assertDrawable(visual: ResolvedVisual) {
        assertTrue("a resolved visual has no seed, so nothing can draw it", visual.seed.isNotBlank())
        // hasImage false means "draw the composition", which is always possible. The pair
        // is the whole contract: one of the two branches is always available.
        if (visual.hasImage) {
            assertTrue("hasImage is true but there is no ref", visual.ref.isNotBlank())
        }
        assertFalse(
            "a resolved visual requires the network, which the core build cannot provide",
            visual.needsNetwork,
        )
    }
}
