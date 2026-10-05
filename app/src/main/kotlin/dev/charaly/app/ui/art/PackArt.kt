package dev.charaly.app.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import dev.charaly.runtime.domain.ArtworkKind
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.presentation.ResolvedTheme
import dev.charaly.runtime.presentation.stableSeed

/**
 * Charaly's artwork system.
 *
 * Every visual in the app is generated locally from a seed: a hashed number
 * decides a palette blend, a horizon, a skyline and a light source. There is no
 * remote image, no bundled copyrighted art and no placeholder grey box. A pack
 * without artwork still looks designed, because "no artwork" is itself a
 * deterministic composition rather than a hole.
 *
 * Deterministic is the important word: the same seed always draws the same
 * picture, on every device, forever, with no cache invalidation.
 */
object Artwork {

    /** A stable pseudo-random sequence from a seed, for reproducible drawing. */
    class Rng(seed: Int) {
        private var state: Int = (if (seed == 0) 0x2545F491 else seed).also {
            // Avoid the degenerate zero state.
        }

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
     * Different worlds should look structurally different, not just differently
     * coloured: a city gets a skyline, a forest gets layered canopies, an interior
     * gets light through a window.
     */
    enum class Motif { SKYLINE, HORIZON, CANOPY, INTERIOR, ABSTRACT }

    fun motifFor(seed: String): Motif = Motif.entries[stableSeed(seed).mod(Motif.entries.size)]

    fun motifFor(artwork: PackArtwork): Motif = motifFor(artwork.seed.ifBlank { artwork.glyph })

    /** Blend two accent colours with a seed-chosen ratio. */
    fun blend(primary: Color, secondary: Color, ratio: Float): Color {
        val a = primary
        val b = secondary
        return Color(
            red = a.red + (b.red - a.red) * ratio,
            green = a.green + (b.green - a.green) * ratio,
            blue = a.blue + (b.blue - a.blue) * ratio,
            alpha = 1f,
        )
    }

    /**
     * The base gradient for a cover.
     *
     * Deep at the bottom, glowing near the horizon: this is what makes a title
     * readable on top of it without adding a scrim everywhere.
     */
    fun coverBrush(theme: ResolvedTheme, seed: String, deep: Color): Brush {
        val rng = Rng(stableSeed(seed) xor 0x51ED2701)
        val top = blend(theme.primary.asColor(), theme.secondary.asColor(), rng.range(0.15f, 0.55f))
        val middle = blend(theme.secondary.asColor(), theme.accent.asColor(), rng.range(0.05f, 0.30f))
        return Brush.linearGradient(
            colors = listOf(deep, top, middle),
            start = Offset.Zero,
            end = Offset(0f, 1000f),
        )
    }

    /** Glow colour for the light source, always derived from the palette. */
    fun glowColor(theme: ResolvedTheme): Color = theme.accent.asColor().copy(alpha = 0.55f)

    /** Silhouette colour: near-black with a hint of the accent, never pure black. */
    fun silhouette(theme: ResolvedTheme, deep: Color): Color = blend(theme.surface.asColor(), deep, 0.35f)

    // ---- motifs ----------------------------------------------------------

    

    

    

    

    
}


/** Long (ARGB) -> Compose Color, using the brand violet when the value is unset. */
private fun Long.asColor(): Color = Color(if (this == 0L) 0xFF8B7BF0 else this)

/** Blend this colour toward [other] by [ratio]. */
private fun Color.blendWith(other: Color, ratio: Float): Color = Color(
    red = red + (other.red - red) * ratio,
    green = green + (other.green - green) * ratio,
    blue = blue + (other.blue - blue) * ratio,
    alpha = 1f,
)

/**
 * Draws generated cover artwork behind a slot's content.
 *
 * No allocations per frame beyond the brush, no bitmap, no IO: this is cheap
 * enough to sit behind a scrolling list without a frame budget conversation.
 */
@Composable
fun PackArt(
    artwork: PackArtwork,
    theme: ResolvedTheme,
    modifier: Modifier = Modifier,
    deep: Color = Color(0xFF07060B),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val seed = artwork.seed.ifBlank { artwork.glyph.ifBlank { "charaly" } }
    val motif = remember(seed) { Artwork.motifFor(seed) }
    val brush = remember(seed, theme) { Artwork.coverBrush(theme, seed, deep) }
    val glow = remember(theme) { Artwork.glowColor(theme) }

    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush = brush)
            val rng = Artwork.Rng(stableSeed(seed) xor motif.ordinal)
            when (motif) {
                Artwork.Motif.SKYLINE -> drawSkyline(rng, theme, deep, glow)
                Artwork.Motif.HORIZON -> drawHorizon(rng, theme, deep, glow)
                Artwork.Motif.CANOPY -> drawCanopy(rng, theme, deep, glow)
                Artwork.Motif.INTERIOR -> drawInterior(rng, theme, deep, glow)
                Artwork.Motif.ABSTRACT -> drawAbstract(rng, theme, deep, glow)
            }
        }
        content()
    }
}

/**
 * A generated avatar.
 *
 * Deterministic per character: the hue comes from the character's own accent, the
 * composition from their id. A character with no artwork still gets a distinct,
 * stable face-like mark.
 */
@Composable
fun CharacterAvatar(
    seed: String,
    accent: Color,
    name: String,
    modifier: Modifier = Modifier,
    ring: Color? = null,
    glyph: Char? = null,
) {
    val initials = glyph?.toString() ?: name.trim().take(1).uppercase().ifBlank { "?" }
    val motif = remember(seed) { Artwork.motifFor(seed) }
    Box(
        modifier = modifier.then(
            if (ring != null) {
                Modifier.avatarRing(ring)
            } else {
                Modifier
            },
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val rng = Artwork.Rng(stableSeed(seed) xor 0x2F1B3C7D)
            drawCircle(
                brush = Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.95f),
                        accent.copy(alpha = 0.55f).compositeOver(deep = Color(0xFF14131A)),
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                ),
            )
            when (motif) {
                Artwork.Motif.CANOPY, Artwork.Motif.ABSTRACT -> {
                    drawCircle(
                        color = Color.White.copy(alpha = rng.range(0.05f, 0.16f)),
                        radius = size.minDimension * rng.range(0.25f, 0.42f),
                        center = Offset(size.width * rng.range(0.3f, 0.7f), size.height * rng.range(0.3f, 0.7f)),
                    )
                }
                else -> {
                    drawArc(
                        color = Color.White.copy(alpha = 0.14f),
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = Offset(size.width * 0.1f, size.height * 0.45f),
                        size = Size(size.width * 0.8f, size.height * 0.8f),
                    )
                }
            }
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

private fun Modifier.avatarRing(ring: Color): Modifier =
    this.border(width = 1.5.dp, color = ring, shape = CircleShape)

private fun Color.compositeOver(deep: Color): Color = Color(
    red = red * 0.85f + deep.red * 0.15f,
    green = green * 0.85f + deep.green * 0.15f,
    blue = blue * 0.85f + deep.blue * 0.15f,
    alpha = 1f,
)

/** True when a pack asks for a real local image instead of generated art. */
fun PackArtwork.usesLocalImage(): Boolean = kind == ArtworkKind.LOCAL_URI && !uri.isNullOrBlank()

/**
 * A horizontally cropped artwork band, used behind hero headers.
 *
 * Kept separate from [PackArt] so a hero can be taller and add a bottom scrim for
 * text legibility without changing the card behaviour.
 */
@Composable
fun ArtworkHero(
    artwork: PackArtwork,
    theme: ResolvedTheme,
    modifier: Modifier = Modifier,
    scrimStrength: Float = 0.85f,
    deep: Color = Color(0xFF07060B),
    content: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier) {
        PackArt(artwork = artwork, theme = theme, deep = deep, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Transparent,
                        deep.copy(alpha = scrimStrength),
                    ),
                    startY = 0f,
                    endY = size.height,
                ),
            )
        }
        content()
    }
}

/**
 * A "square" artwork tile for carousels.
 *
 * Using an explicit layout instead of aspectRatio keeps the aspect correct even
 * when the parent constrains only the width.
 */
@Composable
fun ArtworkTile(
    artwork: PackArtwork,
    theme: ResolvedTheme,
    modifier: Modifier = Modifier,
    aspect: Float = 0.72f,
    content: @Composable BoxScope.() -> Unit = {},
) {
    Layout(content = { PackArt(artwork, theme, Modifier.fillMaxSize(), content = content) }, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = (width * aspect).toInt().coerceAtMost(constraints.maxHeight)
        val placeable = measurables.firstOrNull()?.measure(constraints.copy(minHeight = height, maxHeight = height))
        if (placeable != null) {
            layout(width, height) { placeable.place(0, 0) }
        } else {
            layout(width, height) {}
        }
    }
}

/** Unused maths kept honest: the arcs use degrees, this keeps the import meaningful. */
// ---------------------------------------------------------------------------
// Motifs.
//
// These are file-private DrawScope extensions rather than members of [Artwork] on
// purpose: an extension declared inside an object also needs that object as a
// dispatch receiver, which makes a call site read as `Artwork.drawSkyline(...)` and
// does not resolve from inside a Canvas lambda. Top-level extensions simply work
// where the drawing happens.

private fun DrawScope.drawSkyline(rng: Artwork.Rng, theme: ResolvedTheme, deep: Color, glow: Color) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.55f), Color.Transparent),
                center = Offset(size.width * rng.range(0.25f, 0.75f), size.height * rng.range(0.25f, 0.45f)),
                radius = size.minDimension * rng.range(0.35f, 0.6f),
            ),
        )
        val building = Artwork.silhouette(theme, deep)
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
            // A few lit windows: small, sparse, never a grid.
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

private fun DrawScope.drawHorizon(rng: Artwork.Rng, theme: ResolvedTheme, deep: Color, glow: Color) {
        val horizon = size.height * rng.range(0.55f, 0.72f)
        drawRect(
            color = glow.blendWith(Color.White, 0.35f).copy(alpha = 0.35f),
            topLeft = Offset(0f, horizon),
            size = Size(size.width, size.height - horizon),
        )
        // A low sun or a distant light.
        val sunX = size.width * rng.range(0.2f, 0.8f)
        val sunY = horizon - size.height * rng.range(0.05f, 0.18f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.9f), glow.copy(alpha = 0f)),
                center = Offset(sunX, sunY),
                radius = size.minDimension * rng.range(0.12f, 0.26f),
            ),
        )
        // Rolling ground silhouette.
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
        drawPath(path, color = Artwork.silhouette(theme, deep))
    }

private fun DrawScope.drawCanopy(rng: Artwork.Rng, theme: ResolvedTheme, deep: Color, glow: Color) {
        val layers = 4
        for (layer in 0 until layers) {
            val depth = layer / (layers - 1f)
            val color = Artwork.silhouette(theme, deep).copy(alpha = 0.35f + 0.65f * (1f - depth))
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
                // Canopy blobs.
                val blobs = 4
                for (blob in 0 until blobs) {
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
        // Light shafts through the canopy.
        rotate(degrees = rng.range(-14f, 14f), pivot = Offset(size.width * 0.5f, size.height)) {
            for (shaft in 0 until 3) {
                val x = size.width * rng.range(0.2f, 0.8f)
                val width = size.width * rng.range(0.04f, 0.10f)
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(glow.copy(alpha = 0.30f), Color.Transparent),
                        startY = 0f,
                        endY = size.height,
                    ),
                    topLeft = Offset(x, 0f),
                    size = Size(width, size.height),
                )
            }
        }
    }

private fun DrawScope.drawInterior(rng: Artwork.Rng, theme: ResolvedTheme, deep: Color, glow: Color) {
        // Window: the light source, off-centre.
        val windowX = size.width * rng.range(0.45f, 0.7f)
        val windowY = size.height * rng.range(0.12f, 0.28f)
        val windowW = size.width * rng.range(0.24f, 0.4f)
        val windowH = size.height * rng.range(0.28f, 0.42f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(glow.copy(alpha = 0.85f), glow.copy(alpha = 0.15f)),
                start = Offset(windowX, windowY),
                end = Offset(windowX + windowW, windowY + windowH),
            ),
            topLeft = Offset(windowX, windowY),
            size = Size(windowW, windowH),
        )
        drawRect(
            color = Artwork.silhouette(theme, deep),
            topLeft = Offset(windowX, windowY),
            size = Size(windowW, windowH),
            style = Stroke(width = size.minDimension * 0.012f),
        )
        // Light pooling on the floor.
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(glow.copy(alpha = 0.35f), Color.Transparent),
                center = Offset(windowX + windowW / 2f, size.height * 0.88f),
                radius = size.minDimension * 0.5f,
            ),
            topLeft = Offset(windowX - windowW / 2f, size.height * 0.7f),
            size = Size(windowW * 2f, size.height * 0.3f),
        )
        // Furniture: a couple of blocky silhouettes to imply a room.
        val furniture = Artwork.silhouette(theme, deep)
        val boxW = size.width * rng.range(0.12f, 0.22f)
        drawRect(
            color = furniture,
            topLeft = Offset(size.width * rng.range(0.05f, 0.3f), size.height * rng.range(0.62f, 0.78f)),
            size = Size(boxW, size.height * 0.3f),
        )
    }

private fun DrawScope.drawAbstract(rng: Artwork.Rng, theme: ResolvedTheme, deep: Color, glow: Color) {
        // Concentric arcs: a quiet, non-representational fallback.
        val center = Offset(size.width * rng.range(0.3f, 0.7f), size.height * rng.range(0.35f, 0.65f))
        for (ring in 0 until 6) {
            val radius = size.minDimension * (0.12f + 0.09f * ring)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, glow.copy(alpha = 0.22f * (1f - ring / 7f))),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
                style = Stroke(width = size.minDimension * 0.012f),
            )
        }
        for (dot in 0 until 5) {
            drawCircle(
                color = glow.copy(alpha = rng.range(0.2f, 0.6f)),
                radius = size.minDimension * rng.range(0.01f, 0.03f),
                center = Offset(
                    size.width * rng.range(0.05f, 0.95f),
                    size.height * rng.range(0.05f, 0.95f),
                ),
            )
        }
    }

