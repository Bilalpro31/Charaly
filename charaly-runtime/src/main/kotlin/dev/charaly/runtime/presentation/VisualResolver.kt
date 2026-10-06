package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.VisualAsset
import dev.charaly.runtime.domain.VisualAssetChain
import dev.charaly.runtime.domain.VisualAssetType

/**
 * What a screen is asked to draw for one visual entity.
 *
 * The important property: [artwork] is **never null and never broken**. Every field
 * here is derived through [VisualAssetChain], whose last step always produces something
 * drawable. A screen can therefore render without a single null check or fallback path
 * of its own - which is the only way to make "no broken image" a structural guarantee
 * rather than a promise.
 */
data class ResolvedVisual(
    val key: String,
    val type: VisualAssetType,
    /** Opaque reference the app layer resolves. Empty means "draw the fallback". */
    val ref: String,
    /** True when there is a real image to load. */
    val hasImage: Boolean,
    /** Seed for the locally drawn composition. Always present. */
    val seed: String,
    val caption: String,
    /** How this was resolved, so an "About this artwork" panel can say so. */
    val tier: VisualAssetChain.Tier,
    val credit: String,
    /** Whether anything here needs the network, which the core build cannot provide. */
    val needsNetwork: Boolean,
)

/**
 * Resolves a pack's visuals into something a screen can draw unconditionally.
 *
 * ## Why the fallback lives here rather than in a composable
 *
 * The brief's rule is absolute: never a broken image, never an empty remote URL, never a
 * "failed to load" box. That is only enforceable if resolution happens *before* the view
 * gets involved, because a composable that has to decide what to do when art is missing
 * will eventually get it wrong on some path nobody tested.
 *
 * So every entry point here returns a fully populated [ResolvedVisual]. The app layer
 * draws [ref] when [hasImage] is true and draws [seed] when it is not. There is no third
 * possibility.
 */
object VisualResolver {

    /** The pack's cover, as used by a library card. */
    fun packCover(pack: StoryPack, userProvided: VisualAsset? = null): ResolvedVisual =
        resolve(
            key = "pack:${pack.id.value}",
            type = VisualAssetType.PACK_COVER,
            declared = pack.visualAssets().firstOrNull { it.type == VisualAssetType.PACK_COVER }
                ?: fromArtwork("pack-cover", VisualAssetType.PACK_COVER, pack.identity.cover, pack.id.value),
            userProvided = userProvided,
            seed = "pack-${pack.id.value}",
        )

    /** The pack's wide hero for its detail screen. */
    fun packBanner(pack: StoryPack, userProvided: VisualAsset? = null): ResolvedVisual =
        resolve(
            key = "pack-banner:${pack.id.value}",
            type = VisualAssetType.PACK_BANNER,
            declared = pack.visualAssets().firstOrNull { it.type == VisualAssetType.PACK_BANNER }
                ?: fromArtwork("pack-banner", VisualAssetType.PACK_BANNER, pack.identity.cover, "banner-${pack.id.value}"),
            userProvided = userProvided,
            seed = "banner-${pack.id.value}",
        )

    /**
     * A character.
     *
     * [portrait] chooses the size. A carousel wants a thumbnail; a character page wants
     * the portrait, and both fall back independently so a pack that only has a small
     * image still renders a large one - as a generated composition.
     */
    fun character(
        character: CharacterDefinition,
        portrait: Boolean = true,
        userProvided: VisualAsset? = null,
    ): ResolvedVisual {
        val type = if (portrait) VisualAssetType.CHARACTER_PORTRAIT else VisualAssetType.CHARACTER_THUMBNAIL
        return resolve(
            key = "character:${character.id.value}",
            type = type,
            declared = character.visualAssets.firstOrNull { it.type == type }
                ?: character.visualAssets.firstOrNull { it.type == VisualAssetType.CHARACTER_PORTRAIT }
                ?: fromArtwork("character", type, character.artwork, character.id.value),
            userProvided = userProvided,
            seed = character.id.value,
            caption = character.summaryLine(),
        )
    }

    /** A location. */
    fun location(
        location: Location,
        image: Boolean = true,
        userProvided: VisualAsset? = null,
    ): ResolvedVisual {
        val type = if (image) VisualAssetType.LOCATION_IMAGE else VisualAssetType.LOCATION_THUMBNAIL
        return resolve(
            key = "location:${location.id.value}",
            type = type,
            declared = location.visualAssets.firstOrNull { it.type == type }
                // A location that only declares a wide image can still fill a small tile:
                // the chain crops it, which beats showing nothing.
                ?: location.visualAssets.firstOrNull { it.type == VisualAssetType.LOCATION_IMAGE }
                ?: fromArtwork("location", type, location.artwork, location.id.value),
            userProvided = userProvided,
            seed = location.id.value,
            caption = location.blurb(),
        )
    }

    /** A story hook or opening. */
    fun storyHook(
        scenarioId: String,
        artwork: PackArtwork,
        hookAsset: VisualAsset? = null,
    ): ResolvedVisual = resolve(
        key = "hook:$scenarioId",
        type = VisualAssetType.STORY_HOOK_IMAGE,
        declared = hookAsset ?: fromArtwork("hook", VisualAssetType.STORY_HOOK_IMAGE, artwork, "hook-$scenarioId"),
        seed = "hook-$scenarioId",
        caption = artwork.caption,
    )

    /** An authored event's optional artwork. Always resolves, even for events with none. */
    fun event(eventId: String, artwork: PackArtwork, declared: VisualAsset? = null): ResolvedVisual =
        resolve(
            key = "event:$eventId",
            type = VisualAssetType.EVENT_IMAGE,
            declared = declared ?: fromArtwork("event", VisualAssetType.EVENT_IMAGE, artwork, "event-$eventId"),
            seed = "event-$eventId",
        )

    /**
     * The backdrop for the scene being played.
     *
     * [seed] is the scene's own identity including its time of day, so the generated
     * fallback differs between a rooftop at night and the same rooftop at midday - without
     * the UI having to decide anything, and without a pack having to declare an image for
     * every hour.
     */
    fun sceneBackdrop(
        locationId: String,
        declared: VisualAsset? = null,
        seed: String = "scene-$locationId",
    ): ResolvedVisual = resolve(
        key = "scene:$locationId",
        type = VisualAssetType.BACKGROUND_IMAGE,
        declared = declared,
        seed = seed,
    )

    /**
     * The common tail of every resolution: the one place a [ResolvedVisual] is built.
     *
     * Exposed so a caller that has a seed but no entity (an unresolvable character id, for
     * instance) still goes through the fallback chain rather than inventing a partial value
     * of its own.
     */
    fun resolveFor(
        key: String,
        type: VisualAssetType,
        declared: VisualAsset?,
        seed: String,
        userProvided: VisualAsset? = null,
    ): ResolvedVisual = resolve(
        key = key,
        type = type,
        declared = declared,
        userProvided = userProvided,
        seed = seed,
    )

    private fun resolve(
        key: String,
        type: VisualAssetType,
        declared: VisualAsset?,
        userProvided: VisualAsset? = null,
        seed: String,
        caption: String = "",
    ): ResolvedVisual {
        val resolved = VisualAssetChain.resolve(
            type = type,
            declared = declared,
            userProvided = userProvided,
            seed = seed,
        )
        return ResolvedVisual(
            key = key,
            type = type,
            ref = resolved.asset.preferredRef(),
            hasImage = resolved.hasImage,
            seed = resolved.asset.generatedSeed.ifBlank { seed },
            caption = caption.ifBlank { resolved.asset.caption },
            tier = resolved.tier,
            credit = resolved.asset.creditLine(),
            needsNetwork = resolved.needsNetwork,
        )
    }

    /**
     * Bridges the older [PackArtwork] shape onto the asset model.
     *
     * Packs predate the asset model, and their artwork already carries a stable seed. So
     * a local file URI becomes a real asset and anything else becomes a generated one -
     * which means an old pack gains the full fallback chain without being rewritten.
     */
    private fun fromArtwork(
        prefix: String,
        type: VisualAssetType,
        artwork: PackArtwork,
        seed: String,
    ): VisualAsset? {
        val stableSeed = artwork.seed.ifBlank { artwork.glyph }.ifBlank { seed }
        return VisualAsset(
            assetId = "$prefix-$seed",
            type = type,
            ref = artwork.uri.orEmpty(),
            source = artwork.source(),
            generatedSeed = stableSeed,
            caption = artwork.caption,
        )
    }
}

/** Every visual asset a pack declares, across every entity. */
private fun StoryPack.visualAssets(): List<VisualAsset> =
    visualAssets +
        characters.flatMap { it.visualAssets } +
        locations.flatMap { it.visualAssets } +
        events.mapNotNull { it.visualAsset } +
        scenarios.mapNotNull { it.artworkAsset() }

private fun PackArtwork.source(): dev.charaly.runtime.domain.AssetSource = when (kind) {
    dev.charaly.runtime.domain.ArtworkKind.LOCAL_URI -> dev.charaly.runtime.domain.AssetSource.USER_PROVIDED
    dev.charaly.runtime.domain.ArtworkKind.GENERATED -> dev.charaly.runtime.domain.AssetSource.GENERATED_ORIGINAL
}