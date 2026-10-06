package dev.charaly.app.ui

import dev.charaly.runtime.presentation.Beat
import dev.charaly.runtime.presentation.BeatRole
import dev.charaly.runtime.presentation.ChapterCard

/**
 * The read-only story view.
 *
 * ## A story you are reading, not one you are playing
 *
 * The Library opens onto a shelf; a shelf row opens onto *this*. It is the same transcript
 * the stage would show, with the composer removed - and removing the composer is the point,
 * because it is what distinguishes reading a story you finished from being inside one that
 * is still happening.
 *
 * ## Why the beats are projected rather than reused wholesale
 *
 * The stage's beats are a live projection with streaming state and a phase; a record is a
 * frozen one. Reusing the same type with a different meaning is how a screen ends up
 * rendering "Adrien is speaking…" under a story that ended three days ago.
 */
data class StoryRecordSnapshot(
    val title: String,
    val worldName: String,
    /** The same sentence the lobby shows, from the same presenter. */
    val moment: String,
    /** "Evening · Day 3" */
    val contextLine: String,
    val beats: List<Beat>,
    val chapters: List<ChapterCard>,
) {
    val hasTranscript: Boolean get() = beats.isNotEmpty()
}

/**
 * The model hub, as one value.
 *
 * Assembled here rather than passed as four loose parameters, because the hero and the card
 * list are two views of the same registry: a screen that received them separately could
 * show a hero for a model that is not in the list beneath it.
 */
data class ModelHubScreenSnapshot(
    val hero: dev.charaly.runtime.presentation.ModelHero,
    val cards: List<dev.charaly.runtime.presentation.ModelCard>,
    val canDownload: Boolean,
    val totalInstalledBytes: Long,
    /**
     * How much of the library this device has measured, and what a running benchmark is
     * doing.
     *
     * Carried here rather than recomputed by the screen, so the banner and the cards cannot
     * disagree about which models have been measured.
     */
    val speedSummary: dev.charaly.runtime.presentation.BenchmarkSummary =
        dev.charaly.runtime.presentation.BenchmarkSummary(
            measuredCount = 0,
            unmeasuredCount = 0,
            isRunning = false,
        ),
) {
    val installed: List<dev.charaly.runtime.presentation.ModelCard>
        get() = cards.filter { it.isInstalled }

    val discoverable: List<dev.charaly.runtime.presentation.ModelCard>
        get() = cards.filterNot { it.isInstalled }
}

/**
 * One rendered transcript line, as a beat.
 *
 * The stage's presenter already produces beats for the live story; this converts the older
 * `StoryLine` shape a closed story is read from, so the two surfaces render identically
 * without either presenter having to know about the other.
 */
internal fun dev.charaly.runtime.presentation.StoryLine.toBeat(): Beat? {
    val text = rawText.trim()
    if (text.isEmpty()) return null
    return Beat(
        id = id,
        role = when (kind) {
            dev.charaly.runtime.presentation.LineKind.PLAYER -> BeatRole.PLAYER
            dev.charaly.runtime.presentation.LineKind.DIALOGUE -> BeatRole.CHARACTER
            dev.charaly.runtime.presentation.LineKind.NARRATION -> BeatRole.NARRATION
            else -> BeatRole.SYSTEM
        },
        speakerName = speakerName,
        speakerAccent = speakerAccent,
        speakerArtwork = speakerArtwork,
        dialogue = when (kind) {
            dev.charaly.runtime.presentation.LineKind.DIALOGUE -> text.trim('"', '“', '”')
            else -> ""
        },
        narration = when (kind) {
            dev.charaly.runtime.presentation.LineKind.NARRATION -> text
            else -> ""
        },
        action = when (kind) {
            dev.charaly.runtime.presentation.LineKind.ACTION -> text.trim('*')
            else -> ""
        },
        timeLabel = timeLabel,
    )
}
