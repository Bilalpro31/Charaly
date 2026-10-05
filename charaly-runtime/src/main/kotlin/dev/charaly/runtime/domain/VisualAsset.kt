package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * What a piece of artwork is *for*.
 *
 * The distinction matters for two reasons. Practically, the same image is rarely right
 * at every size: a pack cover needs a wide hero, a character in a carousel needs a
 * square, a location card needs a wide crop. And legally, an asset's licence travels
 * with its *purpose*: official promotional art is fine on a pack cover in some contexts
 * and not fine as a chat background.
 *
 * Keeping the type explicit means the UI can pick an appropriate size without guessing,
 * and means a licence check has something to check against.
 */
@Serializable
enum class VisualAssetType {
    /** The card image in the library. Wide. */
    PACK_COVER,

    /** The wide hero at the top of a pack's detail screen. */
    PACK_BANNER,

    /** A character's main portrait, e.g. on a character page. */
    CHARACTER_PORTRAIT,

    /** A character's small image, e.g. a carousel tile or a chat avatar. */
    CHARACTER_THUMBNAIL,

    /** A location's main image. */
    LOCATION_IMAGE,

    /** A location's small image, e.g. a place tile. */
    LOCATION_THUMBNAIL,

    /** Artwork for an authored event. */
    EVENT_IMAGE,

    /** Artwork for the scene currently being played. */
    SCENE_IMAGE,

    /** Artwork for a story hook or opening. */
    STORY_HOOK_IMAGE,

    /** A full-bleed backdrop behind a scene. */
    BACKGROUND_IMAGE,
    ;

    /** Roughly square, so it wants a small square asset rather than a wide crop. */
    val isPortrait: Boolean get() = this == CHARACTER_PORTRAIT || this == CHARACTER_THUMBNAIL

    /** Needs more width than height. */
    val isWide: Boolean get() = this == PACK_BANNER || this == LOCATION_IMAGE || this == BACKGROUND_IMAGE
}

/**
 * Where an asset came from, and therefore what may be done with it.
 *
 * This is the honest part of the visual system. Charaly ships fan-made demonstration
 * packs, and the difference between "official promotional art", "an open-licence photo",
 * "something this project generated", and "an image the user brought from their own
 * files" is not a detail - it determines whether the pack can be distributed at all.
 *
 * Nothing here is inferred at runtime. A pack declares the provenance of its art, and the
 * UI shows it.
 */
@Serializable
enum class AssetSource {
    /**
     * Bundled with Charaly under a licence that permits redistribution.
     *
     * The only category safe to ship in the repo.
     */
    BUNDLED_LICENSED,

    /**
     * Original artwork generated for this pack - procedural, or produced by this project.
     *
     * The default for demonstration packs, and the reason the shipped packs can be
     * distributed without a licensing question.
     */
    GENERATED_ORIGINAL,

    /**
     * Brought in by the user with the system photo picker.
     *
     * Kept on the user's device, never redistributed, and never fetched by the app.
     */
    USER_PROVIDED,

    /**
     * Optionally downloaded, only where the user has the rights and the build permits it.
     *
     * Always optional: a pack must be fully functional with this absent, because the app
     * declares no INTERNET permission in its core build.
     */
    REMOTE_OPTIONAL,
    ;

    /**
     * Whether this asset may be shipped inside the Charaly repository.
     *
     * Only licensed and generated art qualifies. User-provided and remote assets are
     * personal to the install that owns them.
     */
    val isRedistributable: Boolean
        get() = this == BUNDLED_LICENSED || this == GENERATED_ORIGINAL

    /** Whether the app may fetch it over a network. Never true in the offline core. */
    val requiresNetwork: Boolean get() = this == REMOTE_OPTIONAL
}

/**
 * One piece of artwork, with everything needed to display it *and* to be honest about it.
 *
 * ## Resolution
 *
 * [ref] is deliberately an opaque string rather than a URI or a resource id: the
 * runtime module is pure Kotlin/JVM and must never know about Android resources. The app
 * layer resolves it - a `file://` path, an `android.resource://` URI, or a bundled key.
 *
 * A blank [ref] means "no image", which is a normal state, not an error. The fallback
 * chain in [VisualAssetChain] then produces a generated composition instead of a broken
 * frame, so a pack with no artwork at all still looks designed.
 */
@Serializable
data class VisualAsset(
    val assetId: String,
    val type: VisualAssetType,
    /** Opaque, resolved by the app layer. Empty means "not present". */
    val ref: String = "",
    /** A smaller version of [ref], when one exists. */
    val thumbnailRef: String = "",
    val source: AssetSource = AssetSource.GENERATED_ORIGINAL,
    /** Who to credit, e.g. "Generated for Charaly" or an artist name. */
    val attribution: String = "",
    /** e.g. "CC BY 4.0", "Apache 2.0", or "fan-made; not for redistribution". */
    val license: String = "",
    /** Any extra notice the pack author wants shown. */
    val copyrightNotice: String = "",
    /** Seed for the generated fallback, so it is stable per asset. */
    val generatedSeed: String = "",
    /** A short caption, e.g. the location's one-line mood. */
    val caption: String = "",
) {
    init {
        require(assetId.isNotBlank()) { "VisualAsset needs an assetId" }
        require(thumbnailRef.length <= 400) { "thumbnailRef is a reference, not content" }
    }

    /** True when there is actually something to load. */
    val isPresent: Boolean get() = ref.isNotBlank()

    /** True when this asset's rights allow it to ship inside the app. */
    val mayBeShipped: Boolean get() = source.isRedistributable

    /** True when displaying it would require network access the core build lacks. */
    val needsNetwork: Boolean get() = source.requiresNetwork

    /** The best reference to load at the size implied by [type]. */
    fun preferredRef(): String = when {
        type.isPortrait && thumbnailRef.isNotBlank() -> thumbnailRef
        isPresent -> ref
        else -> thumbnailRef
    }

    /** One line for a credits or details screen. */
    fun creditLine(): String = buildString {
        append(sourceLabel(source))
        if (attribution.isNotBlank()) append(" · ").append(attribution)
        if (license.isNotBlank()) append(" · ").append(license)
    }

    companion object {
        fun sourceLabel(source: AssetSource): String = when (source) {
            AssetSource.BUNDLED_LICENSED -> "Bundled"
            AssetSource.GENERATED_ORIGINAL -> "Original artwork"
            AssetSource.USER_PROVIDED -> "Your image"
            AssetSource.REMOTE_OPTIONAL -> "Optional download"
        }

        /** A generated placeholder: no file, a stable seed, no rights question. */
        fun generated(
            assetId: String,
            type: VisualAssetType,
            seed: String,
            caption: String = "",
        ): VisualAsset = VisualAsset(
            assetId = assetId,
            type = type,
            source = AssetSource.GENERATED_ORIGINAL,
            generatedSeed = seed,
            caption = caption,
        )
    }
}

/**
 * The fallback chain.
 *
 * ## The rule
 *
 * Every visual entity resolves through an ordered chain, and **the last step always
 * succeeds**:
 *
 * ```
 * 1. a bundled licensed asset for this entity
 * 2. an image the user provided
 * 3. another licensed or open-licence asset
 * 4. a generated composition from a seed
 * 5. an abstract fallback derived from the entity's own identity
 * ```
 *
 * The consequence that matters: there is no state in which Charaly can show a broken
 * image, an empty remote URL, or a "failed to load" box. A pack with no artwork renders
 * as designed artwork; a pack with a missing remote asset never shows a gap.
 *
 * Ordering is deliberate. A user's own image outranks a bundled default, because they
 * chose it. A generated composition outranks an optional download that cannot be
 * fetched in the offline core, because a real picture beats a promise.
 */
object VisualAssetChain {

    /** How the fallback was resolved, so the UI can explain itself if asked. */
    enum class Tier {
        /** The entity's own declared asset. */
        PRIMARY,
        /** A user-provided replacement. */
        USER_PROVIDED,
        /** A different licensed asset that suits the same slot. */
        LICENSED_ALTERNATE,
        /** Drawn locally from a seed. */
        GENERATED,
        /** An abstract composition from the entity id. */
        ABSTRACT,
    }

    data class Resolved(
        val asset: VisualAsset,
        val tier: Tier,
    ) {
        /** Whether anything real will be loaded, as opposed to drawn. */
        val hasImage: Boolean get() = asset.isPresent

        /** Whether this needs the network, which the core build cannot provide. */
        val needsNetwork: Boolean get() = asset.needsNetwork
    }

    /**
     * Resolves the artwork for one entity.
     *
     * @param declared the entity's own asset, if it declared one
     * @param userProvided an asset the user supplied, which outranks the default
     * @param seed the entity's stable id, used to draw the fallbacks
     */
    fun resolve(
        type: VisualAssetType,
        declared: VisualAsset?,
        userProvided: VisualAsset? = null,
        seed: String = "",
    ): Resolved {
        // 2. The user's own image beats anything shipped, because they chose it.
        if (userProvided != null && userProvided.isPresent && !userProvided.needsNetwork) {
            return Resolved(userProvided, Tier.USER_PROVIDED)
        }

        // 1. The declared asset, as long as it can actually be shown offline.
        if (declared != null && declared.isPresent && !declared.needsNetwork) {
            val tier = if (declared.mayBeShipped) Tier.PRIMARY else Tier.LICENSED_ALTERNATE
            return Resolved(declared, tier)
        }

        // 4. A generated composition from a stable seed: deterministic, local, and it is
        // a real picture rather than a placeholder box.
        //
        // The seed comes from the *declared* asset when the pack supplied one. A pack
        // author chooses that seed deliberately (so Andre draws as Andre, and not as
        // "the entity with id andre"), and honouring it here is what makes the declared
        // artwork the same drawing the resolver reports. Falling back to the entity id
        // instead would silently ignore the author's choice on every generated pack.
        val identity = (declared?.generatedSeed ?: seed).ifBlank { type.name.lowercase() }
        return Resolved(
            VisualAsset.generated(assetId = identity, type = type, seed = identity),
            Tier.GENERATED,
        )
    }
}