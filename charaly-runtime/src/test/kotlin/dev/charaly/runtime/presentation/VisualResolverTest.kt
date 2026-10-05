package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.AssetSource
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.VisualAsset
import dev.charaly.runtime.domain.VisualAssetChain
import dev.charaly.runtime.domain.VisualAssetType
import dev.charaly.runtime.pack.DemoStoryPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Never show a broken image" is only enforceable if resolution happens before the view
 * gets involved.
 *
 * These tests assert the *structural* guarantee: every visual entity in every demo pack
 * resolves to something drawable, with no null, no empty remote reference and no gap.
 * A screen that consumes [ResolvedVisual] therefore needs no fallback branch of its own,
 * which is the only way this property survives contact with new screens.
 */
class VisualResolverTest {

    private val packs = DemoStoryPacks.all

    // ------------------------------------------------------------------
    // The guarantee
    // ------------------------------------------------------------------

    @Test
    fun `no visual entity in any demo pack fails to resolve`() {
        packs.forEach { pack ->
            assertFalse("${pack.id.value} cover", VisualResolver.packCover(pack).ref.isBlank() && VisualResolver.packCover(pack).seed.isBlank())

            pack.characters.forEach { character ->
                listOf(true, false).forEach { portrait ->
                    val visual = VisualResolver.character(character, portrait = portrait)
                    assertTrue("${character.name} has no seed to draw", visual.seed.isNotBlank())
                    assertNotNull(visual.tier)
                }
            }

            pack.locations.forEach { location ->
                listOf(true, false).forEach { image ->
                    val visual = VisualResolver.location(location, image = image)
                    assertTrue("${location.name} has no seed to draw", visual.seed.isNotBlank())
                }
            }

            pack.scenarios.forEach { scenario ->
                assertTrue(
                    "hook ${scenario.id} has no seed",
                    VisualResolver.storyHook(scenario.id, scenario.artwork).seed.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun `an entity with no declared artwork still gets a drawable fallback`() {
        val pack = packs.first()
        pack.characters.forEach { character ->
            val visual = VisualResolver.character(character)
            if (!visual.hasImage) {
                assertEquals(
                    "${character.name} should fall back to a generated composition",
                    VisualAssetChain.Tier.GENERATED,
                    visual.tier,
                )
            }
        }
    }

    @Test
    fun `the fallback seed is derived from the entity, so it is stable`() {
        val pack = packs.first()
        val character = pack.characters.first()
        assertEquals(
            "the same entity must always draw the same picture",
            VisualResolver.character(character).seed,
            VisualResolver.character(character).seed,
        )
        // The seed must be the one the pack declared for this entity when it declared
        // one, because that is the drawing the pack author chose. An entity with no
        // declared art falls back to its own id, which is still stable and per-entity.
        val declared = character.visualAssets.firstOrNull { it.type == VisualAssetType.CHARACTER_PORTRAIT }
        assertEquals(
            "the seed must be the entity's own declared art, or its id when it has none",
            declared?.generatedSeed ?: character.id.value,
            VisualResolver.character(character).seed,
        )
    }

    @Test
    fun `an entity with no declared art still draws from its own id`() {
        val bare = CharacterDefinition(
            id = CharacterId("nobody-in-particular"),
            name = "Nobody In Particular",
            tagline = "A test fixture with no artwork of its own",
            shortDescription = "Exercises the last step of the fallback chain.",
            description = "Exercises the last step of the fallback chain.",
            personality = "Flat",
            artwork = PackArtwork.generated(seed = "", glyph = ""),
        )
        assertEquals("nobody-in-particular", VisualResolver.character(bare).seed)
    }

    @Test
    fun `two different entities never share a fallback seed`() {
        val pack = packs.first()
        val seeds = pack.characters.map { VisualResolver.character(it).seed }
        assertEquals("every character must be visually distinct", seeds.size, seeds.distinct().size)
    }

    // ------------------------------------------------------------------
    // Precedence
    // ------------------------------------------------------------------

    @Test
    fun `a user supplied image outranks the pack default`() {
        val pack = packs.first()
        val userImage = VisualAsset(
            assetId = "user-cover",
            type = VisualAssetType.PACK_COVER,
            ref = "content://media/1",
            source = AssetSource.USER_PROVIDED,
        )
        val resolved = VisualAssetChain.resolve(
            type = VisualAssetType.PACK_COVER,
            declared = VisualAsset(
                assetId = "pack-default",
                type = VisualAssetType.PACK_COVER,
                ref = "android.resource://app/default",
                source = AssetSource.BUNDLED_LICENSED,
            ),
            userProvided = userImage,
            seed = "cover",
        )
        assertEquals(VisualAssetChain.Tier.USER_PROVIDED, resolved.tier)
        assertEquals("content://media/1", resolved.asset.ref)
    }

    @Test
    fun `an optional remote asset is never selected in the offline core`() {
        // A download the app cannot perform must not be chosen over something drawable.
        val resolved = VisualAssetChain.resolve(
            type = VisualAssetType.EVENT_IMAGE,
            declared = VisualAsset(
                assetId = "remote",
                type = VisualAssetType.EVENT_IMAGE,
                ref = "https://example.invalid/art.png",
                source = AssetSource.REMOTE_OPTIONAL,
            ),
            seed = "event-1",
        )
        assertFalse("a remote url must never be chosen offline", resolved.hasImage)
        assertEquals(VisualAssetChain.Tier.GENERATED, resolved.tier)
        assertTrue(resolved.asset.generatedSeed.isNotBlank())
    }

    @Test
    fun `a local user image is preferred even when it is not redistributable`() {
        val resolved = VisualAssetChain.resolve(
            type = VisualAssetType.CHARACTER_PORTRAIT,
            declared = VisualAsset(
                assetId = "generated",
                type = VisualAssetType.CHARACTER_PORTRAIT,
                source = AssetSource.GENERATED_ORIGINAL,
            ),
            userProvided = VisualAsset(
                assetId = "user",
                type = VisualAssetType.CHARACTER_PORTRAIT,
                ref = "content://media/2",
                source = AssetSource.USER_PROVIDED,
            ),
            seed = "marinette",
        )
        assertTrue(resolved.hasImage)
        assertFalse(resolved.asset.mayBeShipped)
    }

    // ------------------------------------------------------------------
    // Honesty about rights
    // ------------------------------------------------------------------

    @Test
    fun `only licensed or generated assets may ship with the app`() {
        assertTrue(AssetSource.BUNDLED_LICENSED.isRedistributable)
        assertTrue(AssetSource.GENERATED_ORIGINAL.isRedistributable)
        assertFalse("a user's own image must never be redistributed", AssetSource.USER_PROVIDED.isRedistributable)
        assertFalse("a downloaded asset must never be redistributed", AssetSource.REMOTE_OPTIONAL.isRedistributable)
    }

    @Test
    fun `a credit line names the source and any attribution`() {
        val asset = VisualAsset(
            assetId = "a",
            type = VisualAssetType.PACK_COVER,
            source = AssetSource.BUNDLED_LICENSED,
            attribution = "Some Artist",
            license = "CC BY 4.0",
        )
        val credit = asset.creditLine()
        assertTrue(credit.contains("Bundled"))
        assertTrue(credit.contains("Some Artist"))
        assertTrue(credit.contains("CC BY 4.0"))
    }

    @Test
    fun `a generated asset makes no licensing claim it cannot back up`() {
        val generated = VisualAsset.generated("a", VisualAssetType.LOCATION_IMAGE, seed = "school")
        assertTrue(generated.mayBeShipped)
        assertFalse(generated.needsNetwork)
        assertEquals(AssetSource.GENERATED_ORIGINAL, generated.source)
    }

    // ------------------------------------------------------------------
    // Sizes and types
    // ------------------------------------------------------------------

    @Test
    fun `a portrait entity prefers its thumbnail at small sizes`() {
        val asset = VisualAsset(
            assetId = "a",
            type = VisualAssetType.CHARACTER_THUMBNAIL,
            ref = "content://media/full",
            thumbnailRef = "content://media/small",
        )
        assertEquals("content://media/small", asset.preferredRef())
    }

    @Test
    fun `asset types declare whether they want width or height`() {
        assertTrue(VisualAssetType.PACK_BANNER.isWide)
        assertTrue(VisualAssetType.LOCATION_IMAGE.isWide)
        assertTrue(VisualAssetType.CHARACTER_PORTRAIT.isPortrait)
        assertFalse(VisualAssetType.EVENT_IMAGE.isPortrait)
    }

    @Test
    fun `an asset needs an id`() {
        assertTrue(
            "a nameless asset cannot be referenced or overridden",
            runCatching { VisualAsset(assetId = "", type = VisualAssetType.PACK_COVER) }.isFailure,
        )
    }

    @Test
    fun `every demo pack renders a distinct cover seed`() {
        val seeds = packs.map { VisualResolver.packCover(it).seed }
        assertEquals("each world must be visually distinguishable", seeds.size, seeds.distinct().size)
    }
}