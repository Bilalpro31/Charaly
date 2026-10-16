package dev.charaly.app.ui.packstore

import dev.charaly.runtime.domain.ObjectiveState
import dev.charaly.runtime.domain.ObjectiveStatus
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.storypack.InstalledPackRecord
import dev.charaly.runtime.storypack.PackCompatibility
import dev.charaly.runtime.storypack.PackDownloadProgress
import dev.charaly.runtime.storypack.PackDownloadState
import dev.charaly.runtime.storypack.PackOpenDecision
import dev.charaly.runtime.storypack.PackRecoveryAction
import dev.charaly.runtime.storypack.SemVer
import dev.charaly.runtime.storypack.Severity
import dev.charaly.runtime.storypack.StoryCatalogItem
import dev.charaly.runtime.storypack.ValidationReport

/**
 * THE STORY PACK STORE'S PROJECTIONS.
 *
 * ## What lives here, and what must never
 *
 * The pack pipeline answers hard questions - is this archive valid, what is wrong with it,
 * how much has arrived - in the vocabulary of an engineer: issue codes, digests, staging
 * paths, edition trees. Every one of those is useless or alarming in a player's hands.
 * So nothing in the pipeline is rendered directly; it is *mapped* here first, and this file
 * is where the audience changes.
 *
 * The mapping obeys one rule, asserted by tests rather than by review: **a user-facing
 * field may only ever contain prose a person can act on.** `ValidationIssue.detail` and
 * `.subject` are for the developer panel and never reach these types; record ids, checksums
 * and file paths travel only in the explicitly-marked wiring keys, which no screen renders.
 *
 * Pure Kotlin on purpose - the same reason [dev.charaly.app.ui.ModelDownloads] holds its
 * projections separately from the ViewModel: mapping that cannot be tested without a device
 * is mapping that will leak an id at a user sooner or later.
 */

/**
 * One installed pack, as the store's "Yüklü Paketler" row draws it.
 *
 * [packId] is the wiring key: the buttons need it to name *which* pack to export or delete.
 * It is never rendered - the row's identity on screen is the title, exactly as a person
 * thinks of it.
 */
data class PackStoreItem(
    val packId: String,
    val title: String,
    val author: String,
    /** "Sürüm 1.2.0", or blank when the record has no edition to name. */
    val versionLabel: String,
    val premise: String,
    val tags: List<String>,
    /** "3 karakter · 5 mekân · 2 açılış" - what this pack actually holds. */
    val countsLine: String,
    val enabled: Boolean,
)

/** Whether a catalog listing is already on this device, and whether it is superseded. */
enum class PackInstallState {
    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
}

/**
 * One catalog listing, as "Mevcut Paketler" draws it.
 *
 * A listing is a shop-window entry, not a pack: it may say a pack exists and this is all it
 * is allowed to claim. [itemId] is the wiring key for the download pipeline and is never
 * rendered.
 */
data class StoryCatalogCard(
    val itemId: String,
    val packId: String,
    val title: String,
    val author: String,
    val premise: String,
    /** "12.4 MB" or "boyut belirtilmemiş", straight from the catalog's own estimate. */
    val sizeLabel: String,
    val tags: List<String>,
    val installState: PackInstallState,
    /** The live download frame, or null when nothing is in flight for this listing. */
    val download: PackDownloadUi?,
)

/**
 * One download's state, in the shape a card draws it.
 *
 * ## The one rule: [fraction] is null when there is no honest number
 *
 * Null unless the transfer is *actually running* and the total is *actually known*. Every
 * other state renders as a word plus activity, because a bar that says 12% during an
 * install is a lie about a phase no byte is moving in - and a lying progress bar is worse
 * than none. This mirrors
 * [dev.charaly.runtime.storypack.PackDownloadProgress.fraction] and is asserted to.
 */
data class PackDownloadUi(
    /** "İndiriliyor", "Denetleniyor", "Kuruldu" - what is happening, in Turkish. */
    val stateLabel: String,
    val fraction: Float?,
    /** "3.1 MB / 12.4 MB" or blank when no byte count is real yet. */
    val transferredLabel: String,
    val message: String,
    val canCancel: Boolean,
    val canPause: Boolean,
    val canRetry: Boolean,
    /** The failure's own sentence, or blank for a healthy download. */
    val failureMessage: String,
    /** What the user can do instead - the failure type's own alternative. */
    val failureAlternative: String,
    /** Whether this download reached READY - the only state where "Yüklü" is true. */
    val isReady: Boolean,
    /**
     * Paused versus failed, so the retry affordance says "Devam" where it resumes and
     * "Yeniden Dene" where it genuinely restarts. Both are `canRetry`; they are not the
     * same promise to the user.
     */
    val isPaused: Boolean = false,
    val isFailed: Boolean = false,
) {
    companion object {
        val IDLE = PackDownloadUi(
            stateLabel = "",
            fraction = null,
            transferredLabel = "",
            message = "",
            canCancel = false,
            canPause = false,
            canRetry = false,
            failureMessage = "",
            failureAlternative = "",
            isReady = false,
        )
    }
}

/**
 * One validation finding, as the import review shows it.
 *
 * [severityLabel] rides with the message rather than being rendered as a colour, because
 * "critical" and "warning" have to survive being read by someone who cannot separate the
 * two tints. Only `message` travels: the issue's detail and subject are developer data and
 * have no field here to leak through.
 */
data class IssueRow(
    val message: String,
    val severityLabel: String,
)

/**
 * The import review's whole content: what the pack holds, what is wrong, and whether it may
 * be installed.
 *
 * The headline is the report's own verdict sentence ("2 uyarı ile kurulabilir"), so the
 * review and a later failure cannot word the same state differently.
 */
data class PackImportReportUi(
    val headline: String,
    val censusLine: String,
    val errors: List<IssueRow>,
    val warnings: List<IssueRow>,
    /** False the moment anything CRITICAL was found. The Yükle button is drawn from this. */
    val canInstall: Boolean,
) {
    val hasErrors: Boolean get() = errors.isNotEmpty()
    val hasWarnings: Boolean get() = warnings.isNotEmpty()
}

/**
 * An archive that was picked but not yet installed.
 *
 * The bytes are held from preview to decision so confirming cannot re-read the file and
 * install something other than what was reviewed - the same guarantee the character import
 * flow makes with its pending card. [bytes] is the archive, and is deliberately *not* a
 * user-facing field: nothing renders it.
 */
data class PackImportSession(
    /** The picked file's display name, for the review's provenance line only. */
    val sourceName: String,
    val bytes: ByteArray,
    val report: PackImportReportUi,
    val title: String,
    val versionLabel: String,
    /** Whether this supersedes an edition already installed. */
    val isUpgrade: Boolean,
    /** The installed edition being superseded ("1.0.0"), or blank. */
    val previousVersion: String,
)

/**
 * A finished export, waiting for the system's "save as" sheet.
 *
 * The SAF `CreateDocument` launcher lives in the shell and needs a suggested name before a
 * uri exists, so the name is decided here - by the format itself, which is the only place
 * that knows what a `.charaly-pack` should be called.
 */
data class PackExportRequest(
    val fileName: String,
    val bytes: ByteArray,
)

/**
 * The delete confirmation's content.
 *
 * [activeStories] is zero for the plain confirmation and non-zero for the second, "still
 * delete?" dialog the installer's `DeleteOutcome.Blocked` sends the user into. Saves are
 * never touched by a deletion, and both dialogs say so.
 */
data class PackDeletePrompt(
    val packId: String,
    val title: String,
    val activeStories: Int,
    /** True once the installer has refused a plain delete and asked "are you sure". */
    val blocked: Boolean,
)

/**
 * THE STORY-OPEN GATE: a story that cannot be opened the way it was written.
 *
 * Shown instead of the stage, with the reason and the way back. Same shape as
 * [PackDeletePrompt]: the view model owns one, the shell renders it above every route, and
 * the decision it describes is never guessed at twice. [storyId] is the wiring key the
 * "Kapat" action clears; the player reads the title and the sentence.
 */
data class PackOpenGate(
    val storyId: String,
    val storyTitle: String,
    /**
     * What happened, what it means for the save, and what restores play. Turkish, built
     * from the verdict's own sentence so the compatibility contract speaks once and the
     * dialog only adds the next step.
     */
    val explanation: String,
    /** Only the recoveries the app really has, in the order to offer them. */
    val actions: List<PackOpenAction>,
)

/**
 * One recovery button. Labels live with the action so a screen cannot word them wrongly.
 * Dismissal ("Vazgeç") is implicit in every dialog and is deliberately not listed here.
 */
enum class PackOpenAction(val label: String) {
    /** Restore the right edition through the `.charaly-pack` import flow. */
    IMPORT_ARCHIVE("Arşivden İçe Aktar"),

    /** Browse the story catalog - useful when the whole pack is gone. */
    OPEN_CATALOG("Paket Mağazası"),
}

// ---------------------------------------------------------------------------
// Mapping
// ---------------------------------------------------------------------------

/** "Sürüm 1.2.0". Blank editions produce a blank label rather than "Sürüm ". */
fun packVersionLabel(version: String): String =
    if (version.isBlank()) "" else "Sürüm $version"

/**
 * A blocked verdict becomes the dialog's content.
 *
 * The verdict's own Turkish sentence leads - it is the compatibility contract speaking -
 * and the mapping adds only the next step. There is deliberately no "geçiş yap" (migrate)
 * button: no migration operation exists, and a button that pretends otherwise is worse
 * than an honest explanation that the right edition must come back.
 */
fun PackOpenDecision.Blocked.toGateUi(storyId: String, storyTitle: String): PackOpenGate {
    val nextStep = when (report.status) {
        PackCompatibility.PACK_MISSING ->
            "Kaydınız bu süre boyunca korunur. Paketi mağazadan kurabilir ya da hikâyenin " +
                "çalıştığı sürümün .charaly-pack arşivini içe aktarabilirsiniz; doğru sürüm " +
                "geldiğinde hikâye kaldığı yerden devam eder."
        else ->
            "Bu hikâye ${packVersionLabel(report.pinnedVersion)} ile başladı ve sessizce " +
                "yeni sürüme taşınmaz - geçiş uygulanmış değildir. Doğru sürümün " +
                ".charaly-pack arşivini içe aktardığınızda hikâye eksiksiz devam eder. " +
                "Bu cihazda otomatik geçiş yapılmaz."
    }
    return PackOpenGate(
        storyId = storyId,
        storyTitle = storyTitle,
        explanation = report.message + "\n\n" + nextStep,
        actions = recovery.map { action ->
            when (action) {
                PackRecoveryAction.IMPORT_ARCHIVE -> PackOpenAction.IMPORT_ARCHIVE
                PackRecoveryAction.OPEN_CATALOG -> PackOpenAction.OPEN_CATALOG
            }
        },
    )
}

/**
 * "3 karakter · 5 mekân · 2 açılış" from the record's content counts.
 *
 * Zero-count kinds are omitted rather than shown as "0 karakter": a count of nothing is
 * noise, and a pack with no locations should simply not mention locations.
 */
fun packCountsLine(characters: Int, locations: Int, scenarios: Int): String = listOfNotNull(
    packCount("karakter", characters),
    packCount("mekân", locations),
    packCount("açılış", scenarios),
).joinToString(" · ")

private fun packCount(kind: String, count: Int): String? =
    if (count > 0) "$count $kind" else null

/**
 * An installed record, as the store row draws it.
 *
 * Everything rendered here is prose the installer already prepared: a title a person chose,
 * a premise, counts of content. The checksums, the source, the retained edition tree and
 * the install timestamps stay behind, because none of them help anyone decide whether to
 * keep a pack.
 */
fun InstalledPackRecord.toPackStoreItem(): PackStoreItem = PackStoreItem(
    packId = packId,
    title = title,
    author = author,
    versionLabel = packVersionLabel(packVersion),
    premise = premise.ifBlank { description },
    tags = tags,
    countsLine = packCountsLine(
        characters = characterCount,
        locations = locationCount,
        scenarios = scenarioCount,
    ),
    enabled = enabled,
)

/**
 * Whether a catalog listing offers something this device does not have.
 *
 * "Update available" needs a *newer* edition, judged by the version triple rather than by
 * string inequality: a catalog that republishes 1.0.0 after 1.2.0 is not an update, and
 * offering a downgrade as one would be a destructive suggestion.
 */
fun installStateOf(item: StoryCatalogItem, installed: List<InstalledPackRecord>): PackInstallState {
    val record = installed.firstOrNull { it.packId == item.packId } ?: return PackInstallState.NOT_INSTALLED
    val newer = SemVer.compare(item.packVersion, record.packVersion) > 0
    return if (newer) PackInstallState.UPDATE_AVAILABLE else PackInstallState.INSTALLED
}

/**
 * A catalog listing, as the store card draws it.
 *
 * The listing's own words and its own size estimate - a listing has no premises about
 * characters or locations and must not borrow any from the copy installed here, or an
 * outdated listing would describe a pack it is not.
 */
fun StoryCatalogItem.toStoryCatalogCard(
    installState: PackInstallState,
    download: PackDownloadUi?,
): StoryCatalogCard = StoryCatalogCard(
    itemId = id,
    packId = packId,
    title = title,
    author = author,
    premise = premise.ifBlank { description },
    sizeLabel = sizeLabel,
    tags = tags,
    installState = installState,
    download = download,
)

/**
 * A validation finding, as one review row.
 *
 * Exactly two fields, and the first is the report's own Turkish sentence. The
 * [Severity]-to-label step is here so "Kritik Hata" has one spelling in the whole app.
 */
fun dev.charaly.runtime.storypack.ValidationIssue.toIssueRow(): IssueRow = IssueRow(
    message = message,
    severityLabel = when (severity) {
        Severity.CRITICAL -> "Kritik Hata"
        Severity.WARNING -> "Uyarı"
        // OK never reaches the review: the report's warnings/errors lists are filtered by
        // severity before they get here. The label exists so the mapping stays total.
        Severity.OK -> "Uyarı"
    },
)

/**
 * A report, as the import review draws it.
 *
 * The headline and the census are the report's own sentences - one verdict and one honest
 * inventory - so this step cannot disagree with the failure screen about the same file.
 * [ValidationReport.isInstallable] is what the Yükle button obeys: a CRITICAL finding is a
 * refusal, not a suggestion.
 */
fun ValidationReport.toImportReportUi(): PackImportReportUi = PackImportReportUi(
    headline = headline(),
    censusLine = censusLine(),
    errors = errors.map { it.toIssueRow() },
    warnings = warnings.map { it.toIssueRow() },
    canInstall = isInstallable,
)

/**
 * One download frame, as a card draws it.
 *
 * State words are Turkish and are the pipeline's own messages where it has one - the
 * failure's sentence and its alternative come straight from `PackDownloadFailure`, which
 * writes both for a person rather than for a log. [PackDownloadUi.fraction] is null unless
 * bytes are genuinely moving toward a known total; see its own doc.
 */
fun PackDownloadProgress.toPackDownloadUi(): PackDownloadUi = PackDownloadUi(
    stateLabel = when (state) {
        PackDownloadState.QUEUED -> "Sırada"
        PackDownloadState.DOWNLOADING -> "İndiriliyor"
        PackDownloadState.PAUSED -> "Duraklatıldı"
        PackDownloadState.DOWNLOADED -> "İndirildi"
        PackDownloadState.VERIFYING -> "Denetleniyor"
        PackDownloadState.INSTALLING -> "Kuruluyor"
        PackDownloadState.REGISTERING -> "Kaydediliyor"
        PackDownloadState.READY -> "Kuruldu"
        PackDownloadState.FAILED -> "Tamamlanamadı"
        PackDownloadState.CANCELLED -> "İptal edildi"
    },
    // Null unless the transfer is running and the total is real. Never a fabricated
    // percentage for a phase no byte is moving in.
    fraction = fraction,
    transferredLabel = transferredLabel,
    message = message,
    canCancel = state.isActive,
    canPause = state == PackDownloadState.DOWNLOADING || state == PackDownloadState.QUEUED,
    canRetry = state.isRetryable,
    failureMessage = failure?.message.orEmpty(),
    failureAlternative = failure?.alternative.orEmpty(),
    isReady = state == PackDownloadState.READY,
    isPaused = state == PackDownloadState.PAUSED,
    isFailed = state == PackDownloadState.FAILED || state == PackDownloadState.CANCELLED,
)

/**
 * One objective, as the UI shows it: a title and a sentence.
 *
 * Shared by the enter-world review ("Bu hikâyenin görevi") and the stage's Görevler panel,
 * and deliberately limited to these two fields: the conditions, effects and ids behind an
 * objective are authoring data and would only clutter a player's read.
 */
data class PackObjectiveRow(
    val title: String,
    val description: String,
)

/**
 * The objectives a scenario puts on the board, for the wizard's review step.
 *
 * An objective is shown when the opening names it, or when it is an objective that simply
 * starts active in this opening - and never when it is secret, because a secret objective's
 * existence is itself a spoiler and the review is read *before* the first turn. Only the
 * title and the description survive the mapping.
 */
fun scenarioObjectives(pack: StoryPack, scenarioId: String): List<PackObjectiveRow> {
    val scenario = pack.scenario(scenarioId)
    val named = scenario?.objectiveIds.orEmpty()
    return pack.objectives
        .filterNot { it.secret }
        .filter { objective ->
            objective.id in named ||
                (objective.autoActivate && (objective.scenarioIds.isEmpty() || scenarioId in objective.scenarioIds))
        }
        .sortedByDescending { it.priority }
        .map { PackObjectiveRow(title = it.title, description = it.description) }
}

/**
 * The objectives a *running* story is asking for right now.
 *
 * `ACTIVE` only, and never a secret one: a secret objective that has not surfaced in play
 * is a spoiler sitting in the corner of the screen. Terminal states are history, not work.
 */
fun Iterable<ObjectiveState>.activeRows(): List<PackObjectiveRow> =
    filter { it.status == ObjectiveStatus.ACTIVE && !it.secret }
        .sortedByDescending { it.priority }
        .map { PackObjectiveRow(title = it.title, description = it.description) }