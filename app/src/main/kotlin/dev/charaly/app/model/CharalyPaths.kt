package dev.charaly.app.model

import android.content.Context
import java.io.File

/**
 * THE ONE SET OF LOCATIONS CHARaly WRITES TO.
 *
 * ## Why this file exists
 *
 * Charaly has two writers of model files, and they are independent by design: one is fed
 * by the Storage Access Framework, the other by the Hub download pipeline. That
 * independence is good - neither needs to know the other exists. What they *must* share is
 * where the bytes land, because a third party reconciles them.
 *
 * That third party is the registry. `ModelManager.syncRegistry` runs on every launch and
 * does exactly this:
 *
 * ```
 *   for each file in the models directory   -> register it (it exists, so it is installed)
 *   for each registry entry with no file    -> remove it (it does not exist, so it is not)
 * ```
 *
 * That rule is correct and necessary: a model whose bytes were deleted by the user, or by
 * Android reclaiming cache, must not keep claiming it can generate. But it is only correct
 * when *every* writer uses the same directory. When they did not - import wrote to
 * `filesDir/models`, download wrote to `filesDir/charaly/models` - a freshly downloaded
 * model was registered with a path outside the directory being scanned, and the next
 * launch deleted its registry entry while leaving its 4 GB file sitting on disk. The
 * library then showed it as not installed, and re-downloading was the only apparent fix.
 *
 * So: one directory, named once, here.
 */
object CharalyPaths {

    /**
     * Every GGUF Charaly manages.
     *
     * App-private storage (`filesDir`), not external storage, so no runtime permission is
     * ever needed and the file cannot be evicted by an SD-card unmount. `charaly/models`
     * keeps it next to the rest of the app's own data rather than at the root of
     * `filesDir`.
     */
    fun models(context: Context): File = File(context.filesDir, "charaly/models")

    /**
     * The JSON documents: packs, stories, world state, registry, memories, measurements.
     *
     * Shared with the models directory's parent, which is deliberate rather than tidy -
     * the repository and the registry are constructed by the composition root and have
     * always been siblings.
     */
    fun documents(context: Context): File = File(context.filesDir, "charaly")
}