package dev.charaly.app.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.app.ui.design.toCompose
import dev.charaly.runtime.domain.ArtworkKind
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.presentation.ResolvedTheme
import dev.charaly.runtime.presentation.AssetResolver
import dev.charaly.runtime.presentation.stableSeed

/**
 * CHARALY'S ARTWORK.
 *
 * ## Where the pictures come from
 *
 * Every visual in the app is drawn locally from a seed: a hashed number picks a
 * composition, a palette blend, a horizon and a light source. There is no remote image, no
 * bundled third-party art, and no grey placeholder box anywhere in the product.
 *
 * Two consequences, both deliberate:
 *
 *  * **A pack with no artwork still looks designed.** "No artwork" is itself a
 *    deterministic composition rather than a hole, so the fallback chain is a drawing and
 *    not a grey square.
 *  * **Colour comes from the world's [CharalyAtmosphere].** A red-accented pack gets a red
 *    glow and a red skyline; a cyan one gets a cyan horizon. The *structure* of the
 *    composition also varies by seed, so two worlds never differ only in colour.
 *
 * ## Deterministic, on purpose
 *
 * The same seed draws the same picture on every device, forever, with no cache
 * invalidation and no network. That is what makes it safe to draw behind a scrolling list.
 */
object CharalyArt {

    /** A reproducible pseudo-random sequence. Xorshift: fast, stable, seed-addressable. */
    class Rng(seed: Int) {
        private var state: Int = if (seed == 0) 0x2545F491 else seed

        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            return ((state ushr 8) and 0xFFFFFF) / 16_777_216f
        }

        fun range(from: Float, until: Float): Float = from + next() * (until - from)

        fun int(bound: Int): Int = (next() * bound).toInt().coerceIn(0, (bound - 1).coerceAtLeast(0))
    }

    /**
     * The visual motif a seed picks.
     *
     * Different worlds should look *structurally* different, not merely differently
     * coloured: a city gets a skyline, a coast gets a horizon, a wood gets canopies, an
     * interior gets a window, and anything else gets an abstraction.
     */
    enum class Motif { SKYLINE, HORIZON, CANOPY, INTERIOR, ABSTRACT }

    fun motifFor(seed: String): Motif = Motif.entries[stableSeed(seed).mod(Motif.entries.size)]

    fun motifFor(artwork: PackArtwork): Motif =
        motifFor(artwork.seed.ifBlank { artwork.glyph.ifBlank { "charaly" } })

    /** Two colours, a seed-chosen ratio. */
    fun blend(a: Color, b: Color, ratio: Float): Color = Color(
        red = a.red + (b.red - a.red) * ratio,
        green = a.green + (b.green - a.green) * ratio,
        blue = a.blue + (b.blue - a.blue) * ratio,
        alpha = 1f,
    )

    /**
     * The base gradient for an artwork surface.
     *
     * Deep at the bottom, glowing near the top: that is what lets a title sit on artwork
     * without a heavy scrim over the whole thing.
     */
    fun base(atmosphere: CharalyAtmosphere, seed: String): Brush {
        val rng = Rng(stableSeed(seed) xor 0x51ED2701)
        val top = blend(
            atmosphere.accent,
            atmosphere.secondary,
            rng.range(0.18f, 0.55f),
        )
        val mid = blend(atmosphere.secondary, atmosphere.highlight, rng.range(0.05f, 0.28f))
        return Brush.linearGradient(
            colors = listOf(Color(0xFF000000), top, mid),
            start = Offset.Zero,
            end = Offset(0f, 1000f),
        )
    }

    /** The light source's colour. Always derived from the world's own accent. */
    fun glow(atmosphere: CharalyAtmosphere): Color = atmosphere.highlight.copy(alpha = 0.5f)

    /**
     * A silhouette colour: near-black with a trace of the accent.
     *
     * Never pure black, because pure black is the page and a shape drawn in the page's own
     * colour disappears.
     */
    fun silhouette(atmosphere: CharalyAtmosphere): Color =
        blend(Color(0xFF000000), atmosphere.secondary, 0.14f)
}

/** True when a pack asks for a real local image rather than generated art. */
fun PackArtwork.usesLocalImage(): Boolean = kind == ArtworkKind.LOCAL_URI && !uri.isNullOrBlank()

/**
 * The atmosphere a pack's theme supplies.
 *
 * One function so a hero, a card and an avatar can never disagree about a world's colour.
 */
@Composable
fun atmosphereOf(theme: ResolvedTheme): CharalyAtmosphere = CharalyAtmosphere.of(theme)

/**
 * Generated cover artwork behind a slot's content.
 *
 * No bitmap, no IO and no per-frame allocation beyond the brush, so it is cheap enough to
 * sit behind a scrolling feed without a frame-budget conversation.
 */
@Composable
fun PackArt(
    artwork: PackArtwork,
    atmosphere: CharalyAtmosphere,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val seed = artwork.seed.ifBlank { artwork.glyph.ifBlank { "charaly" } }
    val motif = remember(seed) { CharalyArt.motifFor(seed) }
    val brush = remember(seed, atmosphere) { CharalyArt.base(atmosphere, seed) }
    val glow = remember(atmosphere) { CharalyArt.glow(atmosphere) }

    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush = brush)
            val rng = CharalyArt.Rng(stableSeed(seed) xor motif.ordinal)
            when (motif) {
                CharalyArt.Motif.SKYLINE -> drawSkyline(rng, atmosphere, glow)
                CharalyArt.Motif.HORIZON -> drawHorizon(rng, atmosphere, glow)
                CharalyArt.Motif.CANOPY -> drawCanopy(rng, atmosphere, glow)
                CharalyArt.Motif.INTERIOR -> drawInterior(rng, atmosphere, glow)
                CharalyArt.Motif.ABSTRACT -> drawAbstract(rng, atmosphere, glow)
            }
        }
        content()
    }
}

/**
 * Artwork with a scrim and a bloom, for a hero.
 *
 * ## Why the scrim is bottom-weighted
 *
 * Type sits on artwork at the *bottom* of a hero, not the middle, so the scrim is heaviest
 * there and near-transparent at the top. A uniform scrim dims the picture to fix a problem
 * it does not have; a bottom-weighted one keeps the artwork's own light source visible and
 * still makes the title legible.
 *
 * [strength] is 1f for a hero with text on it and lower for a purely decorative band.
 */
@Composable
fun PackArtworkHero(
    artwork: PackArtwork,
    atmosphere: CharalyAtmosphere,
    modifier: Modifier = Modifier,
    strength: Float = 1f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier) {
        PackArt(artwork = artwork, atmosphere = atmosphere, modifier = Modifier.fillMaxSize())

        // The declared gradient when the pack authored one, a bloom when it did not. Both
        // are optional, and the absence of a declared gradient is the honest answer rather
        // than an accent-derived wash that would look identical on every pack.
        val declared = atmosphere.gradient
        Canvas(Modifier.fillMaxSize()) {
            if (declared != null) {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to declared.first.copy(alpha = 0.35f * strength),
                        1f to declared.second.copy(alpha = 0.85f * strength),
                    ),
                )
            } else {
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color(0xFF000000).copy(alpha = 0.25f * strength),
                        1f to Color(0xFF000000).copy(alpha = 0.94f * strength),
                    ),
                )
            }
        }
        content()
    }
}

/**
 * A character's mark.
 *
 * Deterministic per character: the hue is the character's own accent, the composition from
 * their id. An avatar is 40dp of screen, so this is a *mark* rather than a portrait - an
 * initial over a lit arc - and it is legible at that size in light and dark.
 *
 * ## The layer order, and why it is not optional
 *
 * ```
 *   1  the generated mark   always drawn, from the character's own id
 *   2  the initial          when no image loaded
 *   3  the pack's portrait a real image, when one loaded
 * ```
 *
 * Layer 1 is what makes a portrait *optional*. An NPC with no artwork, an id the pack never
 * declared, a decode that failed because the process was killed - all three still produce
 * a complete, character-specific mark. Layers 2 and 3 refine it.
 *
 * @param characterId the `CharacterId`, which is what selects the asset. Never the display
 *   name - see [dev.charaly.runtime.presentation.AssetResolver.portrait].
 * @param packId the pack whose `assets/stories/<pack>/` tree holds this portrait.
 * @param loader the cache. Null in previews, which is a supported state.
 */
@Composable
fun CharacterMark(
    seed: String,
    accent: Color,
    name: String,
    modifier: Modifier = Modifier,
    glyph: Char? = null,
    ring: Color? = null,
    characterId: String = "",
    packId: String = "",
    loader: StoryAssetLoader? = null,
) {
    val initial = glyph?.toString() ?: name.trim().take(1).uppercase().ifBlank { "?" }
    val motif = remember(seed) { CharalyArt.motifFor(seed) }

    // A blank id must not produce a path to `characters/.png`, so the resolution is
    // skipped entirely rather than attempted and failed.
    val assetPath = remember(packId, characterId) {
        if (characterId.isBlank()) "" else AssetResolver.portrait(packId, characterId)
    }
    // Portraits are drawn inside a circle a few dozen dp across, so the decode cap is
    // small. Decoding a 512px source at 512 is right; the backgrounds' 1440 would be four
    // times the pixels for no visible gain at this size.
    val portrait = rememberAssetBitmap(loader, assetPath, targetWidthPx = 256)

    Box(modifier = modifier.then(if (ring != null) Modifier.border(1.5.dp, ring, CircleShape) else Modifier)) {
        Canvas(Modifier.fillMaxSize()) {
            val rng = CharalyArt.Rng(stableSeed(seed) xor 0x2F1B3C7D)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.9f),
                        accent.copy(alpha = 0.32f),
                        Color(0xFF000000).copy(alpha = 0.9f),
                    ),
                    center = Offset(size.width * rng.range(0.3f, 0.7f), size.height * rng.range(0.25f, 0.6f)),
                    radius = size.minDimension * 0.85f,
                ),
            )
            when (motif) {
                CharalyArt.Motif.SKYLINE, CharalyArt.Motif.HORIZON -> drawArc(
                    color = Color.White.copy(alpha = 0.16f),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(size.width * 0.12f, size.height * 0.46f),
                    size = Size(size.width * 0.76f, size.height * 0.76f),
                )

                else -> drawCircle(
                    color = Color.White.copy(alpha = 0.12f),
                    radius = size.minDimension * rng.range(0.28f, 0.44f),
                    center = Offset(size.width * rng.range(0.35f, 0.65f), size.height * rng.range(0.3f, 0.6f)),
                )
            }
        }
        // The initial, drawn under the portrait rather than over it: a loaded image does not need a
        // letter on top of a face, and an unloaded one has nothing else to show.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.95f),
            )
        }

        // The pack's own portrait, clipped to the circle the mark already defines.
        //
        // Clipping rather than letting the square image show: the mark is a circle
        // everywhere in the UI - the presence list, the cast picker, the transcript - and an
        // unclipped square would change the shape of the component rather than its content.
        if (portrait != null) {
            Image(
                bitmap = portrait,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
            )
        }
    }
}

/**
 * A square artwork tile for carousels and lists.
 *
 * An explicit layout rather than `aspectRatio`, because the parent often constrains only
 * the width - and a tile whose height collapses to zero is invisible in a way that is
 * obvious in review but maddening on a device.
 */
@Composable
fun PackArtworkTile(
    artwork: PackArtwork,
    atmosphere: CharalyAtmosphere,
    modifier: Modifier = Modifier,
    aspect: Float = 0.68f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Layout(
        modifier = modifier,
        content = { PackArt(artwork, atmosphere, Modifier.fillMaxSize(), content = content) },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = (width * aspect).toInt().coerceAtMost(constraints.maxHeight)
        val placeable = measurables.firstOrNull()
            ?.measure(constraints.copy(minHeight = height, maxHeight = height))
        if (placeable != null) {
            layout(width, height) { placeable.place(0, 0) }
        } else {
            layout(width, height) {}
        }
    }
}

// ---------------------------------------------------------------------------
// Motifs. Top-level extensions so they resolve from inside a Canvas lambda.
// ---------------------------------------------------------------------------

private fun DrawScope.drawSkyline(
    rng: CharalyArt.Rng,
    atmosphere: CharalyAtmosphere,
    glow: Color,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(glow.copy(alpha = 0.5f), Color.Transparent),
            center = Offset(size.width * rng.range(0.25f, 0.75f), size.height * rng.range(0.25f, 0.45f)),
            radius = size.minDimension * rng.range(0.35f, 0.6f),
        ),
    )

    val building = CharalyArt.silhouette(atmosphere)
    val baseline = size.height * rng.range(0.72f, 0.86f)
    var x = -size.width * 0.05f
    while (x < size.width * 1.05f) {
        val width = size.width * rng.range(0.05f, 0.14f)
        val height = size.height * rng.range(0.10f, 0.36f)
        drawRect(
            color = building,
            topLeft = Offset(x, baseline - height),
            size = Size(width, height + size.height),
        )
        // A few lit windows: sparse, never a grid.
        val rows = (height / (size.height * 0.045f)).toInt().coerceIn(0, 8)
        val columns = (width / (size.width * 0.035f)).toInt().coerceIn(0, 4)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                if (rng.next() > 0.82f) {
                    drawRect(
                        color = glow.copy(alpha = rng.range(0.25f, 0.7f)),
                        topLeft = Offset(
                            x + width * (column + 0.3f) / (columns + 0.6f),
                            baseline - height + height * (row + 0.4f) / (rows + 0.4f),
                        ),
                        size = Size(size.width * 0.012f, size.height * 0.012f),
                    )
                }
            }
        }
        x += width + size.width * rng.range(0.005f, 0.03f)
    }
}

private fun DrawScope.drawHorizon(rng: CharalyArt.Rng, atmosphere: CharalyAtmosphere, glow: Color) {
    val horizon = size.height * rng.range(0.55f, 0.72f)
    drawRect(
        color = glow.blendWith(Color.White, 0.3f).copy(alpha = 0.3f),
        topLeft = Offset(0f, horizon),
        size = Size(size.width, size.height - horizon),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(glow.copy(alpha = 0.85f), glow.copy(alpha = 0f)),
            center = Offset(
                size.width * rng.range(0.2f, 0.8f),
                horizon - size.height * rng.range(0.05f, 0.18f),
            ),
            radius = size.minDimension * rng.range(0.12f, 0.26f),
        ),
    )
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(0f, size.height)
        lineTo(0f, horizon + size.height * 0.08f)
        var px = 0f
        while (px <= size.width) {
            val step = size.width * rng.range(0.12f, 0.25f)
            quadraticTo(
                px + step / 2f,
                horizon + size.height * rng.range(-0.06f, 0.10f),
                px + step,
                horizon + size.height * rng.range(0.02f, 0.12f),
            )
            px += step
        }
        lineTo(size.width, size.height)
        close()
    }
    drawPath(path = path, color = CharalyArt.silhouette(atmosphere))
}

private fun DrawScope.drawCanopy(rng: CharalyArt.Rng, atmosphere: CharalyAtmosphere, glow: Color) {
    val layers = 4
    for (layer in 0 until layers) {
        val depth = layer / (layers - 1f)
        val color = CharalyArt.silhouette(atmosphere).copy(alpha = 0.35f + 0.65f * (1f - depth))
        val baseY = size.height * (0.45f + 0.16f * layer)
        val trunks = 3 + layer
        for (trunk in 0 until trunks) {
            val cx = size.width * ((trunk + 0.5f) / trunks + rng.range(-0.06f, 0.06f))
            val trunkWidth = size.width * (0.012f + 0.02f * depth)
            val trunkHeight = size.height * rng.range(0.18f, 0.34f)
            drawRect(
                color = color,
                topLeft = Offset(cx - trunkWidth / 2f, baseY - trunkHeight),
                size = Size(trunkWidth, trunkHeight + size.height),
            )
            repeat(4) {
                val bx = cx + size.width * rng.range(-0.10f, 0.10f)
                val by = baseY - trunkHeight - size.height * rng.range(0f, 0.10f)
                drawCircle(
                    color = color,
                    radius = size.minDimension * rng.range(0.08f, 0.16f),
                    center = Offset(bx, by),
                )
            }
        }
    }
    rotate(degrees = rng.range(-14f, 14f), pivot = Offset(size.width * 0.5f, size.height)) {
        repeat(3) {
            val x = size.width * rng.range(0.2f, 0.8f)
            val width = size.width * rng.range(0.04f, 0.10f)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(glow.copy(alpha = 0.28f), Color.Transparent),
                    startY = 0f,
                    endY = size.height,
                ),
                topLeft = Offset(x, 0f),
                size = Size(width, size.height),
            )
        }
    }
}

private fun DrawScope.drawInterior(rng: CharalyArt.Rng, atmosphere: CharalyAtmosphere, glow: Color) {
    val windowX = size.width * rng.range(0.45f, 0.7f)
    val windowY = size.height * rng.range(0.12f, 0.28f)
    val windowW = size.width * rng.range(0.24f, 0.4f)
    val windowH = size.height * rng.range(0.28f, 0.42f)
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(glow.copy(alpha = 0.8f), glow.copy(alpha = 0.12f)),
            start = Offset(windowX, windowY),
            end = Offset(windowX + windowW, windowY + windowH),
        ),
        topLeft = Offset(windowX, windowY),
        size = Size(windowW, windowH),
    )
    drawRect(
        color = CharalyArt.silhouette(atmosphere),
        topLeft = Offset(windowX, windowY),
        size = Size(windowW, windowH),
        style = Stroke(width = size.minDimension * 0.012f),
    )
    drawOval(
        brush = Brush.radialGradient(
            colors = listOf(glow.copy(alpha = 0.32f), Color.Transparent),
            center = Offset(windowX + windowW / 2f, size.height * 0.88f),
            radius = size.minDimension * 0.5f,
        ),
        topLeft = Offset(windowX - windowW / 2f, size.height * 0.7f),
        size = Size(windowW * 2f, size.height * 0.3f),
    )
    val furniture = CharalyArt.silhouette(atmosphere)
    val boxW = size.width * rng.range(0.12f, 0.22f)
    drawRect(
        color = furniture,
        topLeft = Offset(size.width * rng.range(0.05f, 0.3f), size.height * rng.range(0.62f, 0.78f)),
        size = Size(boxW, size.height * 0.3f),
    )
}

private fun DrawScope.drawAbstract(rng: CharalyArt.Rng, atmosphere: CharalyAtmosphere, glow: Color) {
    val center = Offset(size.width * rng.range(0.3f, 0.7f), size.height * rng.range(0.35f, 0.65f))
    for (ring in 0 until 6) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, glow.copy(alpha = 0.20f * (1f - ring / 7f))),
                center = center,
                radius = size.minDimension * 0.5f,
            ),
            radius = size.minDimension * (0.12f + 0.09f * ring),
            center = center,
            style = Stroke(width = size.minDimension * 0.012f),
        )
    }
    repeat(5) {
        drawCircle(
            color = glow.copy(alpha = rng.range(0.2f, 0.6f)),
            radius = size.minDimension * rng.range(0.01f, 0.03f),
            center = Offset(size.width * rng.range(0.05f, 0.95f), size.height * rng.range(0.05f, 0.95f)),
        )
    }
}

/** Blends toward [other]; used for a lit edge rather than a flat fill. */
private fun Color.blendWith(other: Color, ratio: Float): Color = Color(
    red = red + (other.red - red) * ratio,
    green = green + (other.green - green) * ratio,
    blue = blue + (other.blue - blue) * ratio,
    alpha = 1f,
)

/** True black, for artwork surfaces. Not navy: a cast would tint every picture. */
val ArtworkVoid: Color = Color(0xFF000000)

/** A hero's atmosphere, remembered across recompositions. */
@Composable
fun rememberAtmosphere(theme: ResolvedTheme): CharalyAtmosphere =
    remember(theme) { CharalyAtmosphere.of(theme) }