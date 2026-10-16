package dev.charaly.runtime.presentation

import dev.charaly.runtime.model.CatalogSource
import dev.charaly.runtime.model.InstalledModel
import dev.charaly.runtime.model.ModelCatalog
import dev.charaly.runtime.model.ModelCatalogItem

/**
 * THE MODEL CATALOG VIEW: what is here, what else there is, and what is in use.
 *
 * ## Why these are three separate lists
 *
 * One flat list was the thing that made a model library feel like a rummage sale. The three
 * questions a player asks are genuinely different:
 *
 * ```
 *   INSTALLED  what can I use right now, offline, tonight?
 *   AVAILABLE  what could I get if I wanted more?
 *   ACTIVE     which one is this story speaking with?
 *   ```
 *
 * Collapsing them hides the answer to whichever two the player did not ask. So the split is
 * data here and the UI only lays it out.
 *
 * ## What this never does
 *
 * It does not download, does not load, and does not decide. Everything is a projection of
 * [ModelCatalog] and `ModelRegistry`, so it cannot disagree with either of them and cannot
 * put an internal id in front of a player - the internal `ModelId` is deliberately absent
 * from every row type here.
 */
object ModelCatalogPresenter {

    /** One row in one of the three lists. Carries no internal identifier. */
    data class CatalogRow(
        /** The name the publisher gave it. Never a registry id and never a file path. */
        val displayName: String,
        val publisher: String,
        val subtitle: String,
        /** e.g. "Q4_K_M · Qwen3 · 1.7B" */
        val specLine: String,
        val sizeLabel: String,
        /** Blank when the entry is engine-loadable; otherwise the honest reason. */
        val engineNote: String = "",
        val isSelected: Boolean = false,
    )

    data class Sections(
        val installed: List<CatalogRow>,
        val available: List<CatalogRow>,
        val active: CatalogRow?,
        /**
         * Where the available list came from.
         *
         * Rendered as a footnote ("showing the built-in list") rather than an error: a
         * catalog that cannot be refreshed is a catalogue that is slightly out of date, not
         * a broken app.
         */
        val sourceNote: String = "",
        val isEmpty: Boolean = true,
    )

    fun sections(
        installed: List<InstalledModel>,
        catalog: ModelCatalog,
        activeModelId: String,
        availableRamBytes: Long = 0L,
        source: CatalogSource = CatalogSource.NotLoaded,
    ): Sections {
        val installedIds = installed.map { it.id }.toSet()
        val installedRows = installed
            .sortedBy { it.displayName.lowercase() }
            .map { it.row(selected = it.id == activeModelId) }

        val availableRows = catalog.items
            .filterNot { it.id in installedIds }
            .filter { availableRamBytes <= 0L || it.isSuitableForDevice(availableRamBytes) }
            .sortedWith(compareBy({ it.weight() }, { it.name.lowercase() }))
            .map { it.row() }

        val active = installed.firstOrNull { it.id == activeModelId }?.row(selected = true)

        return Sections(
            installed = installedRows,
            available = availableRows,
            active = active,
            sourceNote = sourceNoteOf(source),
            isEmpty = installedRows.isEmpty() && availableRows.isEmpty(),
        )
    }

    private fun sourceNoteOf(source: CatalogSource): String = when (source) {
        is CatalogSource.NotLoaded -> ""
        is CatalogSource.Remote -> ""
        // Not an error, and not dressed as one. The list is real; it is just the one the app
        // shipped with rather than the one it was hoping to fetch.
        is CatalogSource.Fallback -> "Offline catalog · ${source.reason}"
    }

    private fun InstalledModel.row(selected: Boolean = false) = CatalogRow(
        displayName = this.displayName,
        publisher = "",
        subtitle = when (origin) {
            dev.charaly.runtime.model.ModelOrigin.DOWNLOADED -> "Downloaded"
            else -> "Imported"
        },
        specLine = listOf(quantization, architecture)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        sizeLabel = this.sizeLabel,
        engineNote = if (compatibility.loadFailed) "This model could not be loaded" else "",
        isSelected = selected,
    )

    private fun ModelCatalogItem.row() = CatalogRow(
        displayName = this.name,
        publisher = publisher,
        subtitle = description.oneLine(),
        specLine = listOf(quantization, architecture, parameterLabel)
            .filter { it.isNotBlank() }
            .joinToString(" · "),
        sizeLabel = sizeDisplay,
        engineNote = when {
            needsEngineUpdate -> "Needs an engine update"
            !isEngineLoadable -> engineVerdict.reason
            else -> ""
        },
    )

    /**
     * The Model Detail rows a publisher's metadata earns.
     *
     * Ordered for a reader, not for a spec sheet: what it is, whether it fits, where it came
     * from. Every value is published metadata - nothing here is inferred from a file name -
     * and a field the publisher did not state is simply not shown rather than filled in with
     * a plausible guess.
     */
    fun detailRows(
        item: ModelCatalogItem,
        measuredSpeedLabel: String? = null,
    ): List<StatChip> = buildList {
        item.description.takeIf { it.isNotBlank() }?.let { add(StatChip("Description", it.oneLine(200))) }
        item.architecture.takeIf { it.isNotBlank() }?.let { add(StatChip("Architecture", it)) }
        item.quantization.takeIf { it.isNotBlank() }?.let { add(StatChip("Quantization", it)) }
        item.parameterLabel.takeIf { it.isNotBlank() }?.let { add(StatChip("Parameters", it)) }
        add(StatChip("Context", "${item.contextLength} tokens"))
        add(StatChip("Download size", item.sizeDisplay))
        item.ramLabel.takeIf { it.isNotBlank() }?.let { add(StatChip("Estimated RAM", it)) }
        item.language.takeIf { it.isNotBlank() }?.let { add(StatChip("Languages", it)) }
        item.license.takeIf { it.isNotBlank() }?.let { add(StatChip("License", it)) }
        item.ggufSource.takeIf { it.isNotBlank() }?.let { add(StatChip("Source", it)) }
        item.version.takeIf { it.isNotBlank() }?.let { add(StatChip("Version", it)) }
        item.recommendedDevice.takeIf { it.isNotBlank() }?.let { add(StatChip("Runs on", it)) }
        if (item.requiresArm64) add(StatChip("Requires", "64-bit ARM"))
        if (item.minAndroidVersion > 0) add(StatChip("Minimum Android", "API ${item.minAndroidVersion}"))
        // A speed that was never measured says so. "0 tok/s" would be a claim that the model
        // is broken; "Not measured" is the truth about the measurement.
        add(StatChip("Speed", measuredSpeedLabel?.takeIf { it.isNotBlank() } ?: "Not measured"))
    }

    private fun String.oneLine(limit: Int = 120): String {
        val flat = lineSequence().joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= limit) flat else flat.take(limit - 1) + "…"
    }
}