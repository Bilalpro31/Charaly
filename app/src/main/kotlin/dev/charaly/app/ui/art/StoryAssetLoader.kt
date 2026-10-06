package dev.charaly.app.ui.art

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.charaly.runtime.presentation.AssetResolver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * THE ASSET LOADER.
 *
 * ## What it does
 *
 * Turns an asset *path* - the string [AssetResolver] produced - into a decoded
 * [ImageBitmap], or into null when the file is not there. Three jobs, in this order:
 *
 * ```
 *   character id -> path      (AssetResolver, pure, tested on a JVM)
 *   path        -> bitmap     (this class: decode, cache, downsample)
 *   bitmap      -> drawn      (SceneBackdrop / CharacterMark)
 * ```
 *
 * The split matters. Everything that can be wrong with a *name* is decided in step one,
 * with no Android involved, so the class of bug where "the school has no picture" is a
 * pure-function test rather than a device reproduction.
 *
 * ## Why nothing here ever throws
 *
 * Every failure - a missing file, a truncated PNG, an out-of-memory decode - returns null.
 * A composable that could throw while resolving artwork would take the whole stage with it,
 * and the artwork is decoration: a story must render identically whether or not a picture
 * loads. That is a stronger property than "shows a placeholder", because it means the
 * fallback and the loaded state are the *same code path* with one value differing.
 *
 * ## Memory, on a phone
 *
 * Three measures, because a 1080x1920 background behind a scrolling transcript is 8 MB
 * decoded and a device that decodes a dozen of them has no app left:
 *
 *  * **Downsample on decode.** [targetWidthPx] caps the decoded width, so a full-screen
 *    background is decoded at screen resolution rather than at source resolution. The
 *    source file stays at full size on disk - it is one of ours and the disk is cheap.
 *  * **An LRU keyed by path, sized in bytes.** Not by count: a count budget cannot tell a
 *    512x512 portrait from a 1080x1920 background, and getting that wrong is how a phone
 *    runs out of memory rather than merely out of cache.
 *  * **A negative cache.** Paths that failed are remembered, so a background that is
 *    genuinely absent is not re-decoded on every recomposition. Without it, a missing
 *    file costs a `BitmapFactory.decodeStream` call per frame.
 *
 * Nothing is fetched over the network. Not because the manifest forbids it, but because a
 * backdrop that changes when connectivity does is a backdrop the user cannot rely on.
 */
class StoryAssetLoader(
    private val assets: AssetManager,
    /**
     * The decode width cap.
     *
     * A background is drawn edge to edge, so its useful resolution is the screen's. A
     * portrait is drawn in a circle a few dozen dp across. Passing one number for both
     * would either waste memory on portraits or make backgrounds soft, so callers pass
     * what they actually need.
     */
    private val targetWidthPx: Int,
    /**
     * The LRU budget, in bytes.
     *
     * An eighth of the heap, which is the conventional share for a cache in a single-tenant
     * app and leaves the rest for the transcript, the world state and llama.cpp's own
     * mmap - which is the largest allocation in the process by a wide margin.
     */
    cacheBytes: Int = defaultCacheBytes(),
) {

    private val cache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Paths that have been tried and are not there. Never looked up twice. */
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /**
     * In-flight decodes, so two recompositions do not decode the same file twice.
     *
     * A [CompletableDeferred] per path rather than a list of continuations: the first
     * caller creates it, everyone awaits it, and the owner completes it exactly once.
     */
    private val inFlight = mutableMapOf<String, CompletableDeferred<Bitmap?>>()
    private val lock = Any()

    /**
     * The decoded bitmap for [path], or null.
     *
     * Null is a real answer: it means "draw the fallback", and every caller has one.
     */
    suspend fun load(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        if (path in missing) return null

        // Coalesce concurrent requests for the same path. Compose will happily ask for the
        // same background from several recompositions in a frame, and decoding it four
        // times to keep one would be the single most expensive thing on the screen.
        //
        // `CompletableDeferred` is kotlinx's own, which gives every caller the same value
        // and resumes them on their own dispatcher. An earlier hand-rolled version kept a
        // list of continuations and could complete them before any of them had suspended -
        // which loses a wakeup and hangs a recomposition forever.
        val (deferred, isOwner) = synchronized(lock) {
            val existing = inFlight[path]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<Bitmap?>()
                inFlight[path] = fresh
                fresh to true
            }
        }
        if (!isOwner) return deferred.await()

        val decoded = withContext(Dispatchers.IO) { decode(path) }
        if (decoded == null) missing += path
        synchronized(lock) { inFlight.remove(path) }
        // Completed after the map entry is removed, so a caller arriving between the two
        // starts its own decode rather than awaiting a value nobody will ever produce.
        deferred.complete(decoded)
        return decoded
    }

    /**
     * Decodes one file, downsampled.
     *
     * `inSampleSize` is what makes the downsample free: `BitmapFactory` reads only a
     * subsample of the pixel data and never materialises the full bitmap, so a 1080x1920
     * PNG decoded to 720px wide costs a quarter of the memory and never costs the full
     * amount at any point.
     */
    private fun decode(path: String): Bitmap? = runCatching {
        // Two passes: the first reads only the header to learn the real dimensions, the
        // second decodes at the reduced size. boundsInJustDecodeBounds is what makes the
        // first pass cheap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open(path, AssetManager.ACCESS_STREAMING).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, targetWidthPx)
            // RGB_565: half the memory of ARGB_8888 and visually indistinguishable for a
            // background that is about to be dimmed behind a scrim anyway. Nothing here is
            // a screenshot or a chart, so an alpha channel would be 4 MB per background
            // spent on a fact every one of these files is false about.
            inPreferredConfig = Bitmap.Config.RGB_565
            inScaled = true
            // No density: these are our own files, laid out in dp by the caller. Letting
            // the framework rescale them by a density bucket it invented would make the
            // decoded size unpredictable, which defeats the sampling above.
            inDensity = 0
        }
        val bitmap = assets.open(path, AssetManager.ACCESS_STREAMING).use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        cache.put(path, bitmap)
        bitmap
    }.getOrNull()

    /**
     * The power-of-two reduction factor.
     *
     * Only powers of two, because `inSampleSize` is a power of two; rounding to an
     * arbitrary factor silently does nothing. And it never goes below 1, which would mean
     * *upscaling* - decoding a 96px icon to fill a screen.
     */
    private fun sampleSizeFor(sourceWidth: Int, targetWidth: Int): Int {
        if (targetWidth <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth) sample *= 2
        return sample
    }

    /**
     * Wraps a decoded bitmap for Compose, or null.
     *
     * A function rather than an extension so a caller that has a `Bitmap?` does not have to
     * import a receiver and remember which overload it is.
     */
    fun asCompose(bitmap: Bitmap?): ImageBitmap? = bitmap?.asImageBitmap()

    /**
     * Whether [path] names a real file, without decoding it.
     *
     * The resolver uses this to pick between candidates: a rainy night prefers
     * `rooftop_rain.png` if that exists and `rooftop_night.png` if it does not. Only an
     * `open` that throws counts as absent, so this costs a single failed stream rather
     * than a header parse.
     */
    fun hasAsset(path: String): Boolean {
        if (path.isBlank()) return false
        if (path in missing) return false
        val present = runCatching {
            assets.open(path, AssetManager.ACCESS_STREAMING).close()
            true
        }.getOrDefault(false)
        if (!present) missing += path
        return present
    }

    /** Drops every cached bitmap. For a low-memory callback. */
    fun clear() {
        cache.evictAll()
        missing.clear()
    }

    /** How many bytes of decoded artwork are held right now. */
    fun cachedBytes(): Int = cache.size()

    companion object {
        /** A path no pack declares, used to prove the null path is reachable. */
        const val MISSING = "stories/miraculous/backgrounds/does-not-exist.png"

        private fun defaultCacheBytes(): Int {
            val heap = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            return (heap / 8).coerceAtLeast(2 * 1024) * 1024
        }
    }
}

/**
 * Loads an asset bitmap across recompositions, or null.
 *
 * ## Why this is a composable and not a `remember`
 *
 * Decoding is I/O, so it cannot happen inside `remember { }` - that block runs on the main
 * thread during composition. `produceState` is the primitive that starts the work and
 * cancels it when the composable leaves, which is exactly the lifecycle a decode needs:
 * a user who backs out of a story mid-decode should not be left paying for it.
 *
 * ## Why the key is the path
 *
 * Keying on the path means a new scene starts a new load and the same scene does not -
 * which is what makes the loader's own LRU worth having. Two scenes can be on screen
 * during a transition and both decode, briefly, and the LRU absorbs it.
 *
 * Null loader is supported and returns null immediately: previews have no `AssetManager`,
 * and a preview that crashed on missing assets would be a preview nobody could write.
 */
@Composable
fun rememberAssetBitmap(
    loader: StoryAssetLoader?,
    path: String,
    targetWidthPx: Int,
): ImageBitmap? {
    if (loader == null || path.isBlank()) return null
    val image by produceState<ImageBitmap?>(initialValue = null, loader, path, targetWidthPx) {
        value = runCatching { loader.load(path)?.asImageBitmap() }.getOrNull()
    }
    return image
}

/**
 * Placeholder removed.
 *
 * This used to hold a hand-rolled continuation list, which had a lost-wakeup race: an
 * owner could complete the value before a second caller had suspended on it. kotlinx's
 * `CompletableDeferred` is already a dependency and does the same job correctly, so the
 * class is gone rather than patched.
 */