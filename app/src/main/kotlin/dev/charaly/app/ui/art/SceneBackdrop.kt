package dev.charaly.app.ui.art

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import dev.charaly.app.ui.design.CharalyAtmosphere
import dev.charaly.runtime.presentation.AssetResolver
import dev.charaly.runtime.domain.CharalySurface
import dev.charaly.runtime.presentation.ResolvedVisual
import dev.charaly.runtime.presentation.TimeOfDay
import dev.charaly.runtime.presentation.VisualSceneProjection
import dev.charaly.runtime.presentation.stableSeed

/**
 * THE SCENE BACKDROP.
 *
 * ## What this replaces
 *
 * A single static wallpaper per pack. Every scene in every world looked like the same
 * gradient, so a rooftop and a bakery and a museum were the same picture with different
 * text over it.
 *
 * ## What it does instead
 *
 * ```
 *   VisualSceneProjection  (world state + scene + location + time of day + weather)
 *        -> motif          (a school draws as a school)
 *        -> atmosphere     (night draws darker, rain draws streaks)
 *        -> the fallback   (declared asset, or this deterministic composition)
 * ```
 *
 * Every input comes from [VisualSceneProjection], which reads only authoritative engine
 * state. This file draws. It never decides *what* the weather is.
 *
 * ## Why everything is drawn rather than loaded
 *
 * No network, no disk read, no decode, no cache eviction, and identical on every device
 * forever. A backdrop that costs one `Canvas` is affordable behind a scrolling transcript
 * on a phone, which a bitmap of the same size is not. When a pack *does* declare a real
 * image, [dev.charaly.runtime.presentation.VisualSceneProjection] hands it over and the
 * caller draws that instead; this is the fallback, and it is deliberately not a grey box.
 */
object CharalyScene {

    /**
     * The structural motif a location draws as.
     *
     * Chosen from a pack-declared tag, never from a hash alone: a hash gives variety
     * between worlds, but it cannot make a school *look* like a school, which is the thing
     * that makes a world feel like a place rather than a colour scheme.
     */
    enum class Motif {
        /** A classroom: a grid of desks and a row of tall windows. */
        CLASSROOM,

        /** Paris rooftops with the tower on the horizon. */
        ROOFTOP,

        /** A boulevard: awnings, a lamp post, a wet road. */
        STREET,

        /** Trees and a path. */
        PARK,

        /** An interior room: a window, a counter, a lamp. */
        ROOM,

        /** The generic city silhouette, for anywhere unclassified. */
        SKYLINE,

        /** An abstract composition, for anything with no place identity at all. */
        ABSTRACT,
    }

    /**
     * Tag -> motif.
     *
     * Deliberately small and explicit. A tag the pack author writes has to mean something
     * here, and a tag that means nothing falls through to the hash rather than being
     * guessed at - so the table cannot rot into "every location is a skyline".
     */
    private val TAG_MOTIFS: List<Pair<String, Motif>> = listOf(
        "classroom" to Motif.CLASSROOM,
        "school" to Motif.CLASSROOM,
        "rooftop" to Motif.ROOFTOP,
        "roof" to Motif.ROOFTOP,
        "street" to Motif.STREET,
        "city" to Motif.STREET,
        "bakery" to Motif.STREET,
        "cafe" to Motif.STREET,
        "park" to Motif.PARK,
        "garden" to Motif.PARK,
        "interior" to Motif.ROOM,
        "home" to Motif.ROOM,
        "flat" to Motif.ROOM,
        "room" to Motif.ROOM,
        "museum" to Motif.ROOM,
        "office" to Motif.ROOM,
        "metro" to Motif.ROOM,
        "tower" to Motif.SKYLINE,
    )

    /**
     * The motif for a location.
     *
     * Tag first, then the hash. The hash is what stops every unclassified place in a pack
     * from being drawn identically, and it is deterministic, so the same place is the same
     * picture on every device and after every restart.
     */
    fun motifFor(tags: List<String>, seed: String): Motif {
        tags.forEach { tag ->
            TAG_MOTIFS.firstOrNull { it.first == tag.trim().lowercase() }?.let { return it.second }
        }
        // No tag matched, so the hash decides - but only among the motifs that make sense
        // for an unclassified place. Letting the hash pick "classroom" for a piece of
        // coastline is the kind of arbitrary-but-plausible that this whole pass is trying to
        // stop doing, so it is restricted to the two that are safe as a default.
        return when (Motif.entries[stableSeed(seed).mod(2)]) {
            Motif.ABSTRACT -> Motif.ABSTRACT
            else -> Motif.SKYLINE
        }
    }

    /**
     * The colour of the sky for a time of day.
     *
     * Derived from the world's own accent rather than a fixed palette, so a pack that
     * declares red and black gets a red Paris at night and a red one at noon too - the
     * identity survives the time change instead of being replaced by it.
     */
    fun skyWash(atmosphere: CharalyAtmosphere, timeOfDay: TimeOfDay): List<Color> {
        val accent = atmosphere.accent
        val secondary = atmosphere.secondary
        val deep = CharalyArt.blend(Color(0xFF000000), secondary, 0.10f)
        return when (timeOfDay) {
            TimeOfDay.DAWN -> listOf(
                CharalyArt.blend(deep, accent, 0.45f),
                CharalyArt.blend(Color(0xFF000000), accent, 0.22f),
                Color(0xFF000000),
            )

            TimeOfDay.DAY -> listOf(
                CharalyArt.blend(deep, accent, 0.62f),
                CharalyArt.blend(deep, secondary, 0.35f),
                Color(0xFF000000),
            )

            TimeOfDay.SUNSET -> listOf(
                CharalyArt.blend(deep, atmosphere.highlight, 0.55f),
                CharalyArt.blend(deep, accent, 0.30f),
                Color(0xFF000000),
            )

            TimeOfDay.NIGHT -> listOf(
                CharalyArt.blend(deep, nightSky(), 0.55f),
                CharalyArt.blend(Color(0xFF000000), secondary, 0.12f),
                Color(0xFF000000),
            )
        }
    }

    /**
     * A visible light source, positioned by seed.
     *
     * The moon at night and the sun by day: the same bloom, a different colour, because
     * what makes a night scene read as night is the light *in* it, not the darkness.
     */
    fun lightSource(atmosphere: CharalyAtmosphere, timeOfDay: TimeOfDay): Color = when (timeOfDay) {
        TimeOfDay.DAWN, TimeOfDay.SUNSET -> atmosphere.highlight
        TimeOfDay.DAY -> Color.White.copy(alpha = 0.55f)
        TimeOfDay.NIGHT -> nightLight()
    }

    /** How dark silhouettes are against this sky. Night is deeper; noon is flatter. */
    fun silhouetteDepth(timeOfDay: TimeOfDay): Float = when (timeOfDay) {
        TimeOfDay.NIGHT -> 0.06f
        TimeOfDay.SUNSET -> 0.10f
        TimeOfDay.DAWN -> 0.13f
        TimeOfDay.DAY -> 0.18f
    }
}

/**
 * The backdrop for the scene being played.
 *
 * ## Two layers, and why the picture is only one of them
 *
 * ```
 *   layer 1   a real image from the pack's assets, when there is one
 *   layer 2   the deterministic composition - always drawn
 *   layer 3   the scrim, so prose stays legible over either
 * ```
 *
 * The composition is drawn *underneath* the image rather than instead of it. That means the
 * image can be absent, truncated, or simply not yet decoded - all three of which happen on
 * a real phone - and the frame is never empty. The composition is also the fallback for a
 * location the pack shipped no artwork for, which is the common case rather than the
 * exceptional one: a pack author writes places, and this build's artwork set covers the
 * ones the shipped packs use.
 *
 * ## What the image is allowed to be chosen by
 *
 * [projection] only - location, time of day, and the world's own declared condition.
 * Never the transcript. A character saying "it is raining" does not make it rain, and a
 * backdrop that changed because the model said so would be the UI asserting a world fact
 * the engine never agreed to.
 *
 * @param packId the pack's id, which selects the asset directory. Character and location
 *   ids are resolved *inside* the projection; the pack id only names the folder.
 * @param loader the asset loader. Null in previews, where no assets are available - and a
 *   null loader is a supported state, not a crash.
 */
@Composable
fun SceneBackdrop(
    visual: ResolvedVisual,
    atmosphere: CharalyAtmosphere,
    projection: VisualSceneProjection,
    locationTags: List<String>,
    modifier: Modifier = Modifier,
    /** 0..1 scrim strength. The transcript sits on top, so this is rarely full strength. */
    strength: Float = 1f,
    /** The pack whose `assets/stories/<pack>/` tree holds this backdrop. */
    packId: String = "",
    loader: StoryAssetLoader? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val seed = visual.seed.ifBlank { visual.key }
    val motif = remember(seed, locationTags) {
        CharalyScene.motifFor(locationTags, seed)
    }

    // The file path is pure: location + time + condition, no Android, no I/O. Only the
    // decode is expensive, and that happens in the loader behind a cache.
    val assetPath = remember(packId, projection.locationId, projection.timeOfDay, projection.weather) {
        AssetResolver.resolveBackground(
            packId = packId,
            locationId = projection.locationId,
            timeOfDay = projection.timeOfDay,
            weather = projection.weather,
            exists = loader?.let { l -> { path -> l.hasAsset(path) } } ?: { false },
        )
    }
    val image = rememberAssetBitmap(loader, assetPath, targetWidthPx = 1080)
    val sky = remember(seed, atmosphere, projection.timeOfDay) {
        CharalyScene.skyWash(atmosphere, projection.timeOfDay)
    }
    val light = remember(atmosphere, projection.timeOfDay) {
        CharalyScene.lightSource(atmosphere, projection.timeOfDay)
    }
    val depth = remember(projection.timeOfDay) {
        CharalyScene.silhouetteDepth(projection.timeOfDay)
    }

    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = sky,
                    startY = 0f,
                    endY = size.height,
                ),
            )
            val rng = CharalyArt.Rng(stableSeed(seed) xor 0x2C1B3A55)
            when (motif) {
                CharalyScene.Motif.CLASSROOM -> drawClassroom(rng, light, depth)
                CharalyScene.Motif.ROOFTOP -> drawRooftop(rng, light, depth, atmosphere)
                CharalyScene.Motif.STREET -> drawStreet(rng, light, depth, atmosphere)
                CharalyScene.Motif.PARK -> drawPark(rng, light, depth)
                CharalyScene.Motif.ROOM -> drawRoom(rng, light, depth)
                CharalyScene.Motif.SKYLINE -> drawRooftop(rng, light, depth, atmosphere)
                CharalyScene.Motif.ABSTRACT -> drawPark(rng, light, depth)
            }
            // Weather, and only when the world declared it. The streaks are driven by the
            // engine's own `weather` variable; nothing here reads the transcript.
            //
            // Skipped when a real image is showing, because the image already has the rain
            // painted into it - drawing streaks on top would double it, and doubling rain
            // is the visual equivalent of saying it twice.
            if (image == null && (projection.weather == "rain" || projection.weather == "raining")) {
                drawRain(rng, light)
            }
        }

        // ---- layer 1: the pack's own artwork ------------------------------
        //
        // Drawn above the composition rather than instead of it, so an image that has not
        // finished decoding - or that is not there at all - leaves a complete picture
        // underneath. `contentScale = Crop` because a backdrop is a picture of a place, and
        // cropping its edges is right; letterboxing it would show the sky gradient again.
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Bottom-weighted scrim so prose set over the backdrop stays legible without
        // greying out the picture the pack asked for.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color(0xFF000000).copy(alpha = 0.20f * strength),
                    1f to Color(0xFF000000).copy(alpha = 0.86f * strength),
                ),
            )
        }
        content()
    }
}

// ---------------------------------------------------------------------------
// Motifs
// ---------------------------------------------------------------------------

private fun DrawScope.drawClassroom(
    rng: CharalyArt.Rng,
    light: Color,
    depth: Float,
) {
    // Tall windows first: the light in a classroom comes from outside it.
    val windows = 4
    val w = size.width / (windows + 1f)
    for (i in 0 until windows) {
        val x = w * (i + 0.7f)
        val top = size.height * 0.10f
        val h = size.height * rng.range(0.34f, 0.46f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(light.copy(alpha = 0.55f), light.copy(alpha = 0.08f)),
                start = Offset(x, top),
                end = Offset(x + w * 0.62f, top + h),
            ),
            topLeft = Offset(x, top),
            size = Size(w * 0.62f, h),
        )
        drawRect(
            color = CharalyArt.blend(Color(0xFF000000), light, depth).copy(alpha = 0.7f),
            topLeft = Offset(x, top),
            size = Size(w * 0.62f, h),
            style = Stroke(width = size.minDimension * 0.008f),
        )
    }
    // Desks, in perspective, sparse.
    val desk = CharalyArt.blend(Color(0xFF000000), light, depth * 0.6f)
    var row = 0
    while (row < 3) {
        val y = size.height * (0.58f + 0.13f * row)
        val scale = 0.6f + 0.22f * row
        var x = size.width * (0.06f + 0.05f * row)
        while (x < size.width * 0.95f) {
            val dw = size.width * 0.14f * scale
            val dh = size.height * 0.05f * scale
            drawRect(
                color = desk.copy(alpha = 0.85f - 0.2f * row),
                topLeft = Offset(x, y),
                size = Size(dw, dh),
            )
            drawRect(
                color = desk.copy(alpha = 0.6f - 0.15f * row),
                topLeft = Offset(x + dw * 0.45f, y + dh),
                size = Size(dw * 0.1f, size.height * 0.09f * scale),
            )
            x += dw * rng.range(1.5f, 1.9f)
        }
        row++
    }
}

private fun DrawScope.drawRooftop(
    rng: CharalyArt.Rng,
    light: Color,
    depth: Float,
    atmosphere: CharalyAtmosphere,
) {
    // The tower, off-centre and unmistakable: it is what makes this Paris and not a city.
    val towerX = size.width * rng.range(0.62f, 0.80f)
    val towerTop = size.height * rng.range(0.10f, 0.20f)
    val towerW = size.width * rng.range(0.045f, 0.07f)
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(atmosphere.accent.copy(alpha = 0.45f), Color.Transparent),
            startY = towerTop,
            endY = size.height * 0.7f,
        ),
        topLeft = Offset(towerX, towerTop),
        size = Size(towerW, size.height * 0.7f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(light.copy(alpha = 0.85f), light.copy(alpha = 0f)),
            center = Offset(towerX + towerW / 2f, towerTop + towerW * 0.4f),
            radius = size.minDimension * 0.12f,
        ),
    )

    // Mansard roofs: Paris is a forest of them, and they read as sloped, not flat.
    val roof = CharalyArt.blend(Color(0xFF000000), light, depth)
    var x = -size.width * 0.05f
    while (x < size.width) {
        val w = size.width * rng.range(0.08f, 0.16f)
        val top = size.height * rng.range(0.42f, 0.62f)
        drawRect(
            color = roof,
            topLeft = Offset(x, top),
            size = Size(w, size.height - top),
        )
        // The slope: a lit triangle, which is what makes a roof look like a roof.
        drawPath(
            path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x, top)
                lineTo(x + w, top)
                lineTo(x + w * rng.range(0.30f, 0.55f), top - size.height * rng.range(0.06f, 0.12f))
                close()
            },
            color = CharalyArt.blend(roof, light, 0.22f),
        )
        // A couple of lit dormer windows.
        val rows = (2 + rng.int(3)).coerceIn(2, 4)
        for (row in 0 until rows) {
            if (rng.next() < 0.45f) {
                drawRect(
                    color = light.copy(alpha = rng.range(0.25f, 0.7f)),
                    topLeft = Offset(
                        x + w * rng.range(0.15f, 0.7f),
                        top + size.height * rng.range(0.04f, 0.22f),
                    ),
                    size = Size(size.width * 0.016f, size.height * 0.022f),
                )
            }
        }
        x += w + size.width * rng.range(0.004f, 0.02f)
    }
}

private fun DrawScope.drawStreet(
    rng: CharalyArt.Rng,
    light: Color,
    depth: Float,
    atmosphere: CharalyAtmosphere,
) {
    val ground = size.height * rng.range(0.66f, 0.74f)
    // The road, wet: a vertical reflection of the sky, which is most of what sells "Paris
    // after dark" without a single texture.
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                CharalyArt.blend(Color(0xFF000000), atmosphere.accent, 0.30f),
                Color(0xFF000000),
            ),
            startY = ground,
            endY = size.height,
        ),
        topLeft = Offset(0f, ground),
        size = Size(size.width, size.height - ground),
    )

    val facade = CharalyArt.blend(Color(0xFF000000), light, depth)
    var x = -size.width * 0.05f
    while (x < size.width) {
        val w = size.width * rng.range(0.10f, 0.20f)
        val top = size.height * rng.range(0.06f, 0.22f)
        drawRect(color = facade, topLeft = Offset(x, top), size = Size(w, ground - top))
        // Awnings: the one detail that says "shops" rather than "blocks".
        if (rng.next() < 0.55f) {
            val awningTop = ground - size.height * rng.range(0.10f, 0.20f)
            drawRect(
                color = CharalyArt.blend(facade, atmosphere.accent, 0.45f),
                topLeft = Offset(x, awningTop),
                size = Size(w, size.height * 0.035f),
            )
        }
        // Lit windows in an irregular grid - never a spreadsheet.
        repeat(4) { row ->
            repeat(3) { column ->
                if (rng.next() > 0.55f) {
                    drawRect(
                        color = light.copy(alpha = rng.range(0.20f, 0.65f)),
                        topLeft = Offset(
                            x + w * (column + 0.2f) / 3f,
                            top + (ground - top) * (row + 0.3f) / 4f,
                        ),
                        size = Size(w * 0.16f, (ground - top) * 0.10f),
                    )
                }
            }
        }
        x += w + size.width * rng.range(0.005f, 0.02f)
    }

    // A lamp post, because the street needs one point of light at eye level.
    val lampX = size.width * rng.range(0.2f, 0.8f)
    drawRect(
        color = facade,
        topLeft = Offset(lampX, ground - size.height * 0.34f),
        size = Size(size.width * 0.008f, size.height * 0.34f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(light.copy(alpha = 0.9f), light.copy(alpha = 0f)),
            center = Offset(lampX + size.width * 0.004f, ground - size.height * 0.35f),
            radius = size.minDimension * 0.16f,
        ),
    )
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(light.copy(alpha = 0.22f), Color.Transparent),
            startY = ground,
            endY = size.height,
        ),
        topLeft = Offset(lampX - size.width * 0.03f, ground),
        size = Size(size.width * 0.08f, size.height - ground),
    )
}

private fun DrawScope.drawPark(
    rng: CharalyArt.Rng,
    light: Color,
    depth: Float,
) {
    val ground = size.height * rng.range(0.70f, 0.80f)
    val trunk = CharalyArt.blend(Color(0xFF000000), light, depth * 0.8f)
    val canopy = CharalyArt.blend(Color(0xFF000000), light, depth * 0.35f)

    // A path: two converging lines, which is the whole trick to reading depth.
    drawPath(
        path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.42f, ground)
            lineTo(size.width * 0.58f, ground)
            lineTo(size.width * 0.72f, size.height)
            lineTo(size.width * 0.28f, size.height)
            close()
        },
        color = CharalyArt.blend(trunk, light, 0.16f).copy(alpha = 0.75f),
    )

    repeat(6) { index ->
        val cx = size.width * ((index + 0.5f) / 6f + rng.range(-0.06f, 0.06f))
        val h = size.height * rng.range(0.28f, 0.52f)
        drawRect(
            color = trunk,
            topLeft = Offset(cx - size.width * 0.006f, ground - h),
            size = Size(size.width * 0.012f, h),
        )
        repeat(5) { blob ->
            drawCircle(
                color = canopy.copy(alpha = rng.range(0.5f, 0.9f)),
                radius = size.minDimension * rng.range(0.07f, 0.13f),
                center = Offset(
                    cx + size.width * rng.range(-0.09f, 0.09f),
                    ground - h + size.height * rng.range(-0.02f, 0.08f),
                ),
            )
        }
    }
}

private fun DrawScope.drawRoom(
    rng: CharalyArt.Rng,
    light: Color,
    depth: Float,
) {
    // A window, and the light it throws onto the floor. The floor light is what makes it a
    // room rather than a rectangle.
    val wx = size.width * rng.range(0.08f, 0.22f)
    val wy = size.height * rng.range(0.14f, 0.26f)
    val ww = size.width * rng.range(0.26f, 0.38f)
    val wh = size.height * rng.range(0.30f, 0.42f)
    drawRect(
        brush = Brush.linearGradient(
            colors = listOf(light.copy(alpha = 0.75f), light.copy(alpha = 0.10f)),
            start = Offset(wx, wy),
            end = Offset(wx + ww, wy + wh),
        ),
        topLeft = Offset(wx, wy),
        size = Size(ww, wh),
    )
    drawRect(
        color = CharalyArt.blend(Color(0xFF000000), light, depth),
        topLeft = Offset(wx, wy),
        size = Size(ww, wh),
        style = Stroke(width = size.minDimension * 0.012f),
    )
    drawPath(
        path = androidx.compose.ui.graphics.Path().apply {
            moveTo(wx, wy + wh)
            lineTo(wx + ww, wy + wh)
            lineTo(wx + ww * 1.7f, size.height)
            lineTo(wx - ww * 0.4f, size.height)
            close()
        },
        color = light.copy(alpha = 0.14f),
    )

    // A counter and a pendant lamp: the two objects that say "somebody works here".
    val furniture = CharalyArt.blend(Color(0xFF000000), light, depth * 0.7f)
    drawRect(
        color = furniture,
        topLeft = Offset(size.width * rng.range(0.55f, 0.68f), size.height * 0.66f),
        size = Size(size.width * rng.range(0.24f, 0.34f), size.height * 0.34f),
    )
    val lampX = size.width * rng.range(0.30f, 0.45f)
    drawRect(
        color = furniture,
        topLeft = Offset(lampX, size.height * 0.04f),
        size = Size(size.width * 0.004f, size.height * 0.16f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(light.copy(alpha = 0.9f), light.copy(alpha = 0f)),
            center = Offset(lampX, size.height * 0.21f),
            radius = size.minDimension * 0.22f,
        ),
    )
}

private fun DrawScope.drawRain(rng: CharalyArt.Rng, light: Color) {
    val drop = light.copy(alpha = 0.30f)
    repeat(48) {
        val x = size.width * rng.next()
        val y = size.height * rng.next()
        val length = size.height * rng.range(0.02f, 0.055f)
        drawLine(
            color = drop,
            start = Offset(x, y),
            end = Offset(x - length * 0.22f, y + length),
            strokeWidth = size.minDimension * 0.0025f,
        )
    }
}
/**
 * The two chromatic colours Charaly itself introduces, read from the token file.
 *
 * `android.graphics.Color.parseColor` rather than a literal, because the colour contract
 * test refuses chromatic literals anywhere but the token file - and rightly: a colour
 * chosen inside a screen is a decision nobody can audit.
 */
private fun nightSky(): Color = Color(android.graphics.Color.parseColor(CharalySurface.NIGHT_SKY))

private fun nightLight(): Color = Color(android.graphics.Color.parseColor(CharalySurface.NIGHT_LIGHT))
