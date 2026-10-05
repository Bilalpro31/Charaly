package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.AssetSource
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.VisualAssetType
import dev.charaly.runtime.pack.DemoStoryPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every entity in every shipped pack must have a real visual identity.
 *
 * The brief is specific: the core seven *and* the NPCs - including Andre and the
 * principal - get their own portraits, and the places get their own images. A generic
 * circle for a named recurring character is the failure mode this file exists to catch.
 */
class DemoPackVisualIdentityTest {

    private val packs = DemoStoryPacks.all
    private val miraculous = packs.first { it.id.value == "pack-miraculous-shadows-of-paris" }

    // ------------------------------------------------------------------
    // Named entities the brief calls out
    // ------------------------------------------------------------------

    @Test
    fun `the entities the brief names all have declared assets`() {
        val namedCharacters = listOf(
            "marinette", "adrien", "ladybug", "catnoir", "alya", "nino", "andre", "principal-damore",
        )
        namedCharacters.forEach { id ->
            val character = miraculous.character(CharacterId(id))
            assertNotNull("$id must exist", character)
            assertTrue(
                "$id must declare its own portrait, not fall back to a generic shape",
                character!!.visualAssets.isNotEmpty(),
            )
            assertNotNull(
                "$id must have a portrait",
                character.visualAssets.firstOrNull { it.type == VisualAssetType.CHARACTER_PORTRAIT },
            )
        }

        val namedPlaces = listOf(
            "city-streets", "school", "andre-ice-cream", "rooftop",
        )
        namedPlaces.forEach { id ->
            val location = miraculous.location(LocationId(id))
            assertNotNull("$id must exist", location)
            assertTrue("$id must declare its own image", location!!.visualAssets.isNotEmpty())
            assertNotNull(
                "$id must have a place image",
                location.visualAssets.firstOrNull { it.type == VisualAssetType.LOCATION_IMAGE },
            )
        }
    }

    @Test
    fun `every character in every demo pack declares a portrait and a thumbnail`() {
        packs.forEach { pack ->
            pack.characters.forEach { character ->
                assertTrue(
                    "${pack.id.value}/${character.name} has no portrait",
                    character.visualAssets.any { it.type == VisualAssetType.CHARACTER_PORTRAIT },
                )
                assertTrue(
                    "${pack.id.value}/${character.name} has no thumbnail; avatars and carousel " +
                        "tiles must not reuse a full portrait",
                    character.visualAssets.any { it.type == VisualAssetType.CHARACTER_THUMBNAIL },
                )
            }
        }
    }

    @Test
    fun `every location in every demo pack declares an image and a thumbnail`() {
        packs.forEach { pack ->
            pack.locations.forEach { location ->
                assertTrue(
                    "${pack.id.value}/${location.name} has no image",
                    location.visualAssets.any { it.type == VisualAssetType.LOCATION_IMAGE },
                )
                assertTrue(
                    "${pack.id.value}/${location.name} has no thumbnail",
                    location.visualAssets.any { it.type == VisualAssetType.LOCATION_THUMBNAIL },
                )
            }
        }
    }

    @Test
    fun `every demo pack declares a cover and a banner`() {
        packs.forEach { pack ->
            assertTrue(
                "${pack.id.value} has no cover",
                pack.visualAssets.any { it.type == VisualAssetType.PACK_COVER },
            )
            assertTrue(
                "${pack.id.value} has no banner",
                pack.visualAssets.any { it.type == VisualAssetType.PACK_BANNER },
            )
        }
    }

    // ------------------------------------------------------------------
    // Copyright safety
    // ------------------------------------------------------------------

    @Test
    fun `no demo pack ships art it has no right to ship`() {
        packs.forEach { pack ->
            val everything = pack.visualAssets +
                pack.characters.flatMap { it.visualAssets } +
                pack.locations.flatMap { it.visualAssets } +
                pack.events.mapNotNull { it.visualAsset } +
                pack.scenarios.mapNotNull { it.artworkAsset() }
            everything.forEach { asset ->
                assertTrue(
                    "${pack.id.value}/${asset.assetId} is ${asset.source} and must not ship",
                    asset.mayBeShipped,
                )
            }
        }
    }

    @Test
    fun `no demo pack uses a remote asset as required art`() {
        // A remote url in a shipped pack would be a broken image in the offline core, and
        // the brief forbids it outright for required visuals.
        packs.forEach { pack ->
            val everything = pack.visualAssets +
                pack.characters.flatMap { it.visualAssets } +
                pack.locations.flatMap { it.visualAssets }
            everything.forEach { asset ->
                assertFalse(
                    "${pack.id.value}/${asset.assetId} must not require a download",
                    asset.source == AssetSource.REMOTE_OPTIONAL,
                )
                assertTrue(
                    "${pack.id.value}/${asset.assetId} must not carry a remote url",
                    !asset.ref.startsWith("http"),
                )
            }
        }
    }

    @Test
    fun `generated demo art makes no licensing claim it cannot back up`() {
        packs.forEach { pack ->
            (pack.characters.flatMap { it.visualAssets } + pack.locations.flatMap { it.visualAssets })
                .forEach { asset ->
                    assertEquals(
                        "${pack.id.value}/${asset.assetId}",
                        AssetSource.GENERATED_ORIGINAL,
                        asset.source,
                    )
                    assertTrue(
                        "${pack.id.value}/${asset.assetId} needs a seed to draw",
                        asset.generatedSeed.isNotBlank(),
                    )
                }
        }
    }

    // ------------------------------------------------------------------
    // Distinctness - the actual point of a per-entity portrait
    // ------------------------------------------------------------------

    @Test
    fun `no two characters in a pack share a drawing seed`() {
        packs.forEach { pack ->
            val seeds = pack.characters.map { VisualResolver.character(it, portrait = false).seed }
            assertEquals(
                "${pack.id.value}: every character must be visually distinct",
                seeds.size,
                seeds.distinct().size,
            )
        }
    }

    @Test
    fun `no two locations in a pack share a drawing seed`() {
        packs.forEach { pack ->
            val seeds = pack.locations.map { VisualResolver.location(it, image = false).seed }
            assertEquals(
                "${pack.id.value}: every place must be visually distinct",
                seeds.size,
                seeds.distinct().size,
            )
        }
    }

    @Test
    fun `an npc has a portrait just like a protagonist does`() {
        // The brief is explicit that Andre and the principal must not be generic.
        val npcs = miraculous.characters.filter { it.storyRole == CharacterRole.NPC }
        assertTrue("the pack must have npcs to check", npcs.isNotEmpty())
        npcs.forEach { npc ->
            val visual = VisualResolver.character(npc)
            assertTrue("${npc.name} must have its own seed", visual.seed.isNotBlank())
            assertTrue(
                "${npc.name} must not be a generic placeholder",
                visual.hasImage || visual.tier == dev.charaly.runtime.domain.VisualAssetChain.Tier.GENERATED,
            )
        }
    }

    @Test
    fun `the declared portrait is what actually gets resolved`() {
        val andre = miraculous.character(CharacterId("andre"))!!
        val visual = VisualResolver.character(andre, portrait = true)
        assertEquals(VisualAssetType.CHARACTER_PORTRAIT, visual.type)
        assertEquals("miraculous-andre", visual.seed)
    }
}