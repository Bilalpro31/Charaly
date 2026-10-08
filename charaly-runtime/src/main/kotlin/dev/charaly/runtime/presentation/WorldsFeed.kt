package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition

/**
 * WORLD DISCOVERY.
 *
 * ## The old shape, and why it failed
 *
 * The library was three labelled sections - Featured, Recently played, All story packs -
 * each a grid of identically sized cards carrying a title, a tagline, two genre chips and
 * three counts.
 *
 * The counts are the problem. "19 characters · 26 locations · 9 events" is the *engine's*
 * inventory, printed on a product surface. A player does not choose a world because it
 * has twenty-six locations; they choose it because they want to be somewhere. And a feed
 * of identical cards has no lead, so the eye has nowhere to land and the screen reads as
 * an index rather than as an invitation.
 *
 * So the feed is:
 *
 * ```
 *   one dominant card      the world you were last in, or the featured one
 *   two full cards         the rest of what is worth your attention
 *   the long tail, quiet   everything else, narrower and smaller
 * ```
 *
 * ## Every field is a pitch, not an inventory
 *
 * Premise, tone and one hook, all authored by the pack. No character count, no location
 * count, no event count, no "sessions" badge on a discovery surface.
 *
 * ## Ordering is play history first
 *
 * The world you were last in is the world you most likely want again, so it leads. A pack
 * an author marked as featured is promoted, but never above a world you have actually
 * stood in - a recommendation the user has ignored should not outrank their own history
 * forever.
 */
data class WorldsFeedSnapshot(
    /** The worlds, already weighted and ordered. */
    val worlds: List<WorldFeedItem>,
    val query: String,
    /** The tone/genre words available, for a compact filter row. */
    val availableGenres: List<String>,
    val selectedGenres: Set<String>,
    /** Total before filtering, so "3 of 7 worlds" is answerable without a second query. */
    val totalCount: Int,
    val emptyState: EmptyState?,
) {
    val isEmpty: Boolean get() = worlds.isEmpty()

    /** "3 of 7" / "7", for a filter row that says what it is doing. */
    fun matchCountLabel(): String = when {
        query.isBlank() && selectedGenres.isEmpty() -> totalCount.toString()
        worlds.size == totalCount -> totalCount.toString()
        else -> "$worlds of $totalCount"
    }
}

object WorldsFeedPresenter {

    /** How many worlds the feed will ever show. A feed, not an index. */
    const val FEED_LIMIT = 24

    /** Genres shown in the filter row before it becomes a wall of chips. */
    const val FILTER_LIMIT = 8

    /**
     * Builds the feed.
     *
     * Filtering runs on *authored* metadata only - title, premise, tagline, tone, hooks,
     * genres. Nothing here reads world state, so the feed is instant and cannot be wrong
     * about what a world contains.
     */
    fun build(
        nowEpochMs: Long,
        instances: List<StoryInstance>,
        packs: List<StoryPack>,
        query: String = "",
        selectedGenres: Set<String> = emptySet(),
    ): WorldsFeedSnapshot {
        val ordered = packs.sortedWith(ordering(instances))

        val all = ordered.mapIndexed { index, pack -> item(nowEpochMs, index, pack, instances) }

        val filtered = all.filter { item ->
            val matchesQuery = query.isBlank() || item.matches(query)
            val matchesGenre = selectedGenres.isEmpty() || selectedGenres.any { genre ->
                item.genres.any { it.equals(genre, ignoreCase = true) }
            }
            matchesQuery && matchesGenre
        }

        // Weights are re-derived after filtering, so the *first result* is always the
        // dominant card. Weighting before filtering would let a search return a screen
        // whose largest element was the third result, which reads as a bug.
        val weighted = filtered
            .sortedBy { it.weight.ordinal }
            .mapIndexed { index, item -> item.copy(weight = weightFor(index)) }
            .take(FEED_LIMIT)

        return WorldsFeedSnapshot(
            worlds = weighted,
            query = query,
            availableGenres = packs.flatMap { it.genreChips() }
                .distinct()
                .sorted()
                .take(FILTER_LIMIT),
            selectedGenres = selectedGenres,
            totalCount = all.size,
            emptyState = if (filtered.isEmpty()) emptyState(packs.isEmpty(), query, selectedGenres) else null,
        )
    }

    /**
     * Play history first, then featured, then how much of the story is here, then name.
     *
     * The final tiebreak is alphabetical rather than by pack id so the feed does not
     * reorder itself between launches when two worlds have never been played - an
     * unstable order reads as the app having changed its mind.
     */
    private fun ordering(instances: List<StoryInstance>): Comparator<StoryPack> {
        val lastPlayed = instances
            .groupBy { it.storyPackId.value }
            .mapValues { (_, stories) -> stories.maxOf { it.sessionMeta.lastPlayedAtEpochMs } }

        return compareByDescending<StoryPack> { lastPlayed[it.id.value] ?: 0L }
            .thenByDescending { it.identity.isFeatured }
            .thenByDescending { instances.count { story -> story.storyPackId == it.id } }
            .thenBy { it.title.lowercase() }
    }

    private fun item(
        nowEpochMs: Long,
        index: Int,
        pack: StoryPack,
        instances: List<StoryInstance>,
    ): WorldFeedItem {
        // The strict showcase projection. A feed card is a pitch; the engine delivers on
        // it later, in play. Nothing here can render a character list even by accident,
        // because PackShowcase has no field for one.
        val showcase = PackShowcaseBuilder.build(pack)
        val sessions = instances.filter { it.storyPackId == pack.id }
        return WorldFeedItem(
            id = pack.id.value,
            title = showcase.title,
            premise = showcase.premise.ifBlank { showcase.tagline },
            tone = showcase.tone,
            hook = showcase.hooks.firstOrNull().orEmpty(),
            genres = showcase.genres.take(2),
            artwork = showcase.artwork,
            theme = showcase.theme,
            weight = weightFor(index),
            lastPlayedLabel = sessions
                .maxByOrNull { it.sessionMeta.lastPlayedAtEpochMs }
                ?.let { RelativeTime.compact(nowEpochMs, it.sessionMeta.lastPlayedAtEpochMs) }
                .orEmpty(),
            sessionCount = sessions.size,
        )
    }

    /**
     * Three weights, deliberately.
     *
     * More than three and the feed becomes a gradient nobody can read; fewer and every
     * card is the same size, which is the grid the brief rules out.
     */
    private fun weightFor(index: Int): WorldWeight = when (index) {
        0 -> WorldWeight.DOMINANT
        in 1..2 -> WorldWeight.FULL
        else -> WorldWeight.QUIET
    }

    /**
     * Whether a feed card matches a free-text query.
     *
     * Public because the search field's *placeholder* should describe what can be
     * searched, and the honest way to know that is to ask the matcher.
     */
    fun WorldFeedItem.matches(query: String): Boolean {
        if (query.isBlank()) return true
        return title.contains(query, ignoreCase = true) ||
            premise.contains(query, ignoreCase = true) ||
            tone.contains(query, ignoreCase = true) ||
            hook.contains(query, ignoreCase = true) ||
            genres.any { it.contains(query, ignoreCase = true) }
    }

    /** The search field's placeholder, phrased as what is actually indexed. */
    const val SEARCH_HINT = "Dünyalar, atmosferler, türler ara"

    private fun emptyState(
        noPacks: Boolean,
        query: String,
        genres: Set<String>,
    ): EmptyState = when {
        noPacks -> EmptyState(
            title = "İlk dünyanız sizi bekliyor.",
            body = "Bir dünya; insanları, mekânları ve olanı hatırlayan kurallarıyla " +
                "bir bütündür.",
            actionLabel = "Bir dünya kur",
            artSeed = "charaly-empty-worlds",
        )

        query.isNotBlank() && genres.isNotEmpty() -> EmptyState(
            title = "Bu aramayla eşleşen bir şey yok.",
            body = "\"$query\" ve ${genres.joinToString(" ve ")} ile aynı anda " +
                "filtreliyorsunuz. Birini ya da ikisini deneyin.",
            actionLabel = "Filtreleri temizle",
            artSeed = "charaly-empty-worlds-filtered",
        )

        query.isNotBlank() -> EmptyState(
            title = "Bu aramayla eşleşen bir şey yok.",
            body = "\"$query\" adında bir dünya yok ve hiçbiri o şekilde yazılmamış.",
            actionLabel = "Aramayı temizle",
            artSeed = "charaly-empty-worlds-search",
        )

        else -> EmptyState(
            title = "Bu filtreye uyan bir şey yok.",
            body = "${genres.joinToString(" veya ")} türünde bir dünya yok.",
            actionLabel = "Filtreleri temizle",
            artSeed = "charaly-empty-worlds-genre",
        )
    }
}