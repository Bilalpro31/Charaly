package dev.charaly.runtime.presentation

import dev.charaly.runtime.compat.CharacterCardError
import dev.charaly.runtime.compat.CharacterCardPreview
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.persistence.ImportedCharacter

/**
 * The character import flow, as data.
 *
 * ```
 *   IDLE  -> READING -> PREVIEW -> IMPORTED
 *              |          |
 *              +----------+--> FAILED
 * ```
 *
 * ## Why the preview is a screen and not a dialog
 *
 * Because the decision it asks for is not small. A card can carry a description, a
 * personality, a scenario, a greeting, example dialogue and a dozen lore entries, and a user
 * deciding whether to keep a character is entitled to see the lot before anything is
 * written. A dialog that showed only a name would be asking for consent without disclosing
 * what is being consented to.
 *
 * ## The one rule
 *
 * Nothing is written before [CharacterImportStep.PREVIEW] is confirmed. [ImportedCharacter]
 * only reaches storage through the runtime's commit step, so a cancelled import leaves the
 * library byte-for-byte as it was - which is the only claim worth making about a cancel
 * button, and one worth testing.
 */
enum class CharacterImportStep {
    IDLE,
    READING,
    PREVIEW,
    IMPORTED,
    FAILED,
}

/**
 * The import screen's read model.
 *
 * Pure, built by [CharacterImportPresenter] from bytes or from an error, so the whole flow
 * is testable on the JVM without a file picker, a storage grant or a device.
 */
data class CharacterImportSnapshot(
    val step: CharacterImportStep,
    /** The source file's name, shown as provenance. Empty until a file is chosen. */
    val sourceName: String = "",
    /** The card, present only in [CharacterImportStep.PREVIEW]. */
    val preview: CharacterCardPreview? = null,
    /** A human failure, present only in [CharacterImportStep.FAILED]. */
    val error: CharacterCardError? = null,
    /** "Reading card…" while the file is being parsed. */
    val progressLabel: String = "",
) {
    val canConfirm: Boolean get() = step == CharacterImportStep.PREVIEW && preview != null

    val canCancel: Boolean
        get() = step == CharacterImportStep.PREVIEW || step == CharacterImportStep.FAILED

    /** The headline: the card's name, or the reason there isn't one. */
    val title: String
        get() = when (step) {
            CharacterImportStep.PREVIEW -> preview?.name.orEmpty()
            CharacterImportStep.FAILED -> error?.headline ?: "Could not read character card"
            CharacterImportStep.IMPORTED -> "Character imported"
            else -> "Import character"
        }
}

/** One row in the imported-characters list. */
data class ImportedCharacterCard(
    val id: String,
    val name: String,
    val summary: String,
    val tagline: String,
    val personality: String,
    val scenario: String,
    val greeting: String,
    val tags: List<String>,
    val loreCount: Int,
    val creator: String,
    /** "PNG card" / "JSON card", so the user knows what they imported. */
    val formatLabel: String,
    val accent: Long,
    val artwork: dev.charaly.runtime.domain.PackArtwork,
)

/**
 * The imported characters screen.
 *
 * Shown in the New Story cast step as a separate group from the pack's own cast, because
 * they *are* separate: a pack's cast is authored by the pack author, and an imported
 * character is the user's. Presenting them in one undifferentiated list would hide which
 * world a character actually came from.
 */
data class ImportedCharactersSnapshot(
    val cards: List<ImportedCharacterCard>,
    val isEmpty: Boolean,
    val emptyState: EmptyState?,
    /** How many are in use, for a line that says where they went. */
    val usageLabel: String = "",
)

object CharacterImportPresenter {

    /** Nothing chosen yet. */
    fun idle(): CharacterImportSnapshot =
        CharacterImportSnapshot(step = CharacterImportStep.IDLE)

    /** A file has been chosen and is being parsed. */
    fun reading(sourceName: String): CharacterImportSnapshot = CharacterImportSnapshot(
        step = CharacterImportStep.READING,
        sourceName = sourceName,
        progressLabel = "Reading card…",
    )

    /**
     * The card is ready to be judged.
     *
     * Every field shown here comes from the card; nothing is generated, because a preview
     * that adds a sentence the card did not contain would be a preview the user cannot
     * trust to decide on.
     */
    fun preview(sourceName: String, card: CharacterCardPreview): CharacterImportSnapshot =
        CharacterImportSnapshot(
            step = CharacterImportStep.PREVIEW,
            sourceName = sourceName,
            preview = card,
        )

    /** The read failed, in words rather than in an exception message. */
    fun failed(sourceName: String, error: CharacterCardError): CharacterImportSnapshot =
        CharacterImportSnapshot(
            step = CharacterImportStep.FAILED,
            sourceName = sourceName,
            error = error,
        )

    /**
     * Turns a reader result into a snapshot.
     *
     * The failure mapping is the important part: a `Result` carries a `Throwable`, and
     * `Throwable.message` is the worst thing that can be shown to a player. So every
     * failure collapses to one of the few sentences in [CharacterCardError].
     */
    fun fromResult(
        sourceName: String,
        result: Result<CharacterCardPreview>,
    ): CharacterImportSnapshot = result.fold(
        onSuccess = { preview(sourceName, it) },
        onFailure = { failed(sourceName, CharacterCardError.of(describe(it))) },
    )

    /** Confirmed and written. */
    fun imported(card: CharacterCardPreview): CharacterImportSnapshot = CharacterImportSnapshot(
        step = CharacterImportStep.IMPORTED,
        sourceName = card.sourceName,
        preview = card,
    )

    /** Cancelling returns to idle and, by construction, has written nothing. */
    fun cancelled(): CharacterImportSnapshot = idle()

    /**
     * The list screen.
     *
     * [inUseBy] maps a character id to the number of stories it appears in, so the list can
     * say "in 2 stories" rather than implying a card is unused when it is not.
     */
    fun library(
        imported: List<ImportedCharacter>,
        inUseBy: Map<CharacterId, Int> = emptyMap(),
    ): ImportedCharactersSnapshot {
        val cards = imported.map { record ->
            val preview = record.preview
            ImportedCharacterCard(
                id = preview.id,
                name = preview.name,
                summary = preview.summaryLine(),
                tagline = preview.summaryLine().take(64),
                personality = preview.personality,
                scenario = preview.scenario,
                greeting = preview.greeting,
                tags = preview.tags,
                loreCount = preview.loreCount,
                creator = preview.creator,
                formatLabel = if (preview.fromPng) "PNG card" else "JSON card",
                accent = ResolvedTheme.BRAND.primary,
                artwork = dev.charaly.runtime.domain.PackArtwork.generated("imported-${preview.id}"),
            )
        }

        // Counts *characters*, not stories, because that is what this list is about and what
        // the map actually contains: one entry per character, valued by how many stories it
        // appears in. A "In N stories" label here would be claiming a number this data does
        // not contain, and the total would silently change meaning if a character moved
        // between stories.
        val inUse = inUseBy.values.count { it > 0 }

        return ImportedCharactersSnapshot(
            cards = cards,
            isEmpty = cards.isEmpty(),
            emptyState = if (cards.isEmpty()) {
                EmptyState(
                    title = "No imported characters.",
                    body = "Import a SillyTavern character card and it becomes available in every story.",
                    actionLabel = "Import Character",
                    artSeed = "charaly-empty-characters",
                )
            } else {
                null
            },
            usageLabel = when {
                cards.isEmpty() -> ""
                inUse == 0 -> "Not used in a story yet"
                inUse == 1 -> "1 character in a story"
                else -> "$inUse characters in stories"
            },
        )
    }

    /**
     * A failure, in a sentence.
     *
     * Every distinct cause a card can have collapses to one of two user-facing sentences.
     * A user cannot repair a malformed PNG, so the useful response is to say the file could
     * not be read and offer the next action - not to enumerate which byte was wrong.
     */
    private fun describe(error: Throwable): String = when {
        error is OutOfMemoryError -> CharacterCardError.UNREADABLE.detail
        else -> CharacterCardError.NOT_A_CARD.detail
    }
}