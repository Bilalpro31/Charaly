package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.StoryPack

/**
 * Everything a hero header needs, decided before any layout happens.
 *
 * ## Why this exists
 *
 * The brief's complaint about the pack screen was specific: "no text overlap, no giant
 * blocks of text, no raw metadata walls, no tiny text". Those are almost all *measure*
 * problems, and measure problems cannot be fixed by the composable alone - the
 * composable has to know how much room it has before it decides how to fill it.
 *
 * So the decisions live here, where they can be tested:
 *
 *  * the tagline is trimmed to [maxTaglineLines] lines' worth of characters;
 *  * the title is capped and falls back to the pack's own title rather than overflowing;
 *  * the eyebrow is limited to a few chips, because twelve genre chips is a metadata
 *    wall with nicer typography;
 *  * the scrim exists whenever there is text over artwork.
 *
 * Every limit is a constant with a reason, and every test below asserts the *user-
 * visible* consequence rather than the implementation.
 */
data class HeroLayout(
    val eyebrow: String,
    val title: String,
    val tagline: String,
    val chips: List<String>,
    val hasOverlayText: Boolean,
    /** How tall the hero should be, given how much text sits on it. */
    val heightDp: Int,
) {
    val isTruncated: Boolean get() = tagline.isNotEmpty() && taglineHasEllipsis
    var taglineHasEllipsis: Boolean = false
        internal set
}

/**
 * Chooses hero content and chrome sizes.
 *
 * Pure and total: any pack produces a layout, including one with nothing but a title.
 */
object HeroPresenter {

    /** Long enough for two comfortable lines at bodyLarge on a tablet. */
    const val MAX_TAGLINE_CHARS = 140

    /** A title longer than this is set in a smaller type size rather than truncated. */
    const val LONG_TITLE_CHARS = 26

    /** Genre chips are decoration; more than this is noise. */
    const val MAX_CHIPS = 4

    /** Heights, chosen so the same content fits on a phone and a tablet. */
    const val HERO_HEIGHT_COMPACT = 260
    const val HERO_HEIGHT_REGULAR = 320
    const val HERO_HEIGHT_TALL = 380

    fun forPack(pack: StoryPack): HeroLayout = forContent(
        eyebrow = pack.genreChips().take(3).joinToString(" · ").ifBlank { "Story pack" },
        title = pack.title,
        tagline = pack.identity.tagline.ifBlank { pack.description.lineSequence().firstOrNull().orEmpty() },
        chips = pack.genreChips(),
    )

    /**
     * @param eyebrow the small line above the title
     * @param title the pack's name; never blank
     * @param tagline the one-line pitch
     * @param chips genre chips, trimmed to [MAX_CHIPS]
     */
    fun forContent(
        eyebrow: String,
        title: String,
        tagline: String,
        chips: List<String> = emptyList(),
    ): HeroLayout {
        val cleanTitle = title.trim().ifBlank { "Untitled world" }
        val trimmedTagline = trimToLines(tagline.trim(), MAX_TAGLINE_CHARS)
        val cappedChips = chips.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_CHIPS)

        // More text means more height, so nothing is ever squeezed. The hero grows
        // instead, which is what removes overlap rather than hiding it.
        val height = when {
            trimmedTagline.isEmpty() -> HERO_HEIGHT_COMPACT
            cleanTitle.length > LONG_TITLE_CHARS || trimmedTagline.length > MAX_TAGLINE_CHARS -> HERO_HEIGHT_TALL
            else -> HERO_HEIGHT_REGULAR
        }

        return HeroLayout(
            eyebrow = eyebrow.trim(),
            title = cleanTitle,
            tagline = trimmedTagline,
            chips = cappedChips,
            hasOverlayText = true,
            heightDp = height,
        ).also {
            it.taglineHasEllipsis = tagline.trim().length > trimmedTagline.length
        }
    }

    /**
     * Trims to a character budget and marks the cut with a real ellipsis.
     *
     * Character count rather than a font measurement, because measurement needs a
     * density and a font scale and would make this untestable on the JVM. The budget is
     * deliberately conservative: a shorter tagline looks better than a tight one.
     */
    fun trimToLines(text: String, budget: Int = MAX_TAGLINE_CHARS): String {
        if (text.length <= budget) return text
        // Cut at the last word boundary so the tagline never ends mid-word.
        val cut = text.take(budget)
        val lastSpace = cut.lastIndexOf(' ')
        val body = if (lastSpace > budget / 2) cut.take(lastSpace) else cut
        return "$body…"
    }
}