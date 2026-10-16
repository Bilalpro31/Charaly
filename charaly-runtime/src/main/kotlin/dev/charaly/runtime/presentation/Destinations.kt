package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.model.InstalledModel

/**
 * THE LIBRARY.
 *
 * Four destinations, and they are not interchangeable.
 *
 * ```
 *   HOME      the world lobby: one story to go back to, worlds to go into
 *   WORLDS    discovery: the packs, as a feed of invitations
 *   CHAT      the stage: where a story actually happens
 *   LIBRARY   what you have made: every story on this device
 * ```
 *
 * Settings is deliberately *not* one of them. In a consumer app a settings destination
 * occupies the same visual weight as the thing the user came for, and Charaly's user came
 * for a world. Settings is reached from Home, where a settings screen belongs.
 *
 * This type exists so the shell and the nav bar agree on that list. Two independent
 * `when` blocks over the same set of routes is how a bar ends up offering a destination
 * the router cannot render.
 */
enum class CharalyDestination(
    /**
     * What the user calls it. Never an internal name.
     *
     * A localisation key rather than the text itself: this label is also the navigation
     * item's accessibility description, so leaving it as a constructor string would have
     * made the Turkish navigation bar read "Home" in a Turkish app - a failure a sighted
     * user would see and a screen-reader user could never report clearly.
     */
    val labelKey: String,
    /**
     * Whether the destination is one of the four primary ones.
     *
     * Secondary destinations are reachable but are not offered in the bar, which is what
     * keeps the bar to four items on a phone and to a rail on a tablet.
     */
    val isPrimary: Boolean = true,
) {
    HOME("nav.home"),
    WORLDS("nav.worlds"),
    CHAT("nav.chat"),
    LIBRARY("nav.library"),
    ;

    /** The localised label, resolved at read time so a language change takes effect. */
    val label: String get() = Loc.t(labelKey)

    companion object {
        /** The bar's contents, in order. */
        val PRIMARY: List<CharalyDestination> = entries.filter { it.isPrimary }

        /** Settings and the model hub: reachable, never in the bar. */
        val SECONDARY: List<CharalyDestination> = emptyList()
    }
}

/**
 * The library: every story on this device.
 *
 * ## What this is not
 *
 * A session manager. The previous version of this screen offered rename, branch and
 * delete on every row plus a detail screen with a full transcript - which made a library
 * of living stories read as a database of records.
 *
 * So the library is a *shelf*. One card per story: its world, its current moment, who is
 * there, when you last had it. Continue is the whole interaction. Destructive actions
 * exist but are reached deliberately, never by a swipe.
 */
data class LibraryShelf(
    val stories: List<ShelfStory>,
    val query: String,
    val totalCount: Int,
    val emptyState: EmptyState?,
) {
    val isEmpty: Boolean get() = stories.isEmpty()
}

data class ShelfStory(
    val id: String,
    val title: String,
    val worldName: String,
    /** The scene line: "Evening · Day 3". */
    val contextLine: String,
    /** One sentence about the current moment. The same sentence Home shows. */
    val moment: String,
    val lastPlayedLabel: String,
    val companionName: String,
    val companionAccent: Long,
    val theme: ResolvedTheme,
    val artwork: dev.charaly.runtime.domain.PackArtwork,
    val turnCount: Int,
) {
    /** Only the newest story is the obvious "continue" target. */
    fun isResumable(isNewest: Boolean): Boolean = isNewest
}

object LibraryShelfPresenter {

    /** A shelf, not an archive. */
    const val SHELF_LIMIT = 40

    fun build(
        nowEpochMs: Long,
        instances: List<StoryInstance>,
        query: String = "",
    ): LibraryShelf {
        val sorted = instances.sortedWith(
            compareByDescending<StoryInstance> { it.sessionMeta.lastPlayedAtEpochMs }
                .thenByDescending { it.updatedAt.totalMinutes },
        )

        val stories = sorted.mapIndexed { index, instance ->
            story(nowEpochMs, index, instance)
        }.filter { story ->
            query.isBlank() ||
                story.title.contains(query, ignoreCase = true) ||
                story.worldName.contains(query, ignoreCase = true) ||
                story.companionName.contains(query, ignoreCase = true)
        }.take(SHELF_LIMIT)

        return LibraryShelf(
            stories = stories,
            query = query,
            totalCount = sorted.size,
            emptyState = if (stories.isEmpty()) emptyState(sorted.isEmpty(), query) else null,
        )
    }

    private fun story(
        nowEpochMs: Long,
        index: Int,
        instance: StoryInstance,
    ): ShelfStory {
        val now = instance.worldClock.now
        val companionId = instance.focusCharacterId
            ?: instance.worldState.characters.keys.minByOrNull { it.value }
        val companion = instance.characters[companionId]

        return ShelfStory(
            id = instance.id.value,
            title = instance.displayTitle,
            worldName = instance.packTitle,
            contextLine = listOfNotNull(
                StoryContextPresenter.timeOfDayLabel(now.hour).takeIf { it.isNotBlank() },
                "${now.day}. Gün".takeIf { now.day > 1 },
            ).joinToString(" · "),
            // The same sentence Home shows, from the same presenter. A shelf that
            // described a story differently from the lobby would be two truths about one
            // thing, and the reader would notice.
            moment = StoryMomentPresenter.sentenceFor(instance),
            lastPlayedLabel = RelativeTime.describe(
                nowEpochMs,
                instance.sessionMeta.lastPlayedAtEpochMs,
            ),
            companionName = companion?.name.orEmpty(),
            companionAccent = ResolvedTheme.BRAND.primary,
            theme = ResolvedTheme.BRAND,
            artwork = dev.charaly.runtime.domain.PackArtwork.generated(
                "banner-${instance.storyPackId.value}",
            ),
            turnCount = instance.conversation.size,
        )
    }

    private fun emptyState(noStories: Boolean, query: String): EmptyState = when {
        noStories -> EmptyState(
            title = Loc.t("empty.no_stories_title"),
            body = Loc.t("empty.no_stories_body"),
            actionLabel = Loc.t("action.find_world"),
            artSeed = "charaly-empty-library",
        )

        else -> EmptyState(
            title = "Bu aramayla eşleşen bir şey yok.",
            body = "\"$query\" adında bir hikâye yok ve bu adda bir dünyada geçen bir hikâye de yok.",
            actionLabel = Loc.t("worlds.clear_filters"),
            artSeed = "charaly-empty-library-search",
        )
    }
}

/**
 * SETTINGS.
 *
 * ## The privacy copy, precisely
 *
 * This build *declares* `android.permission.INTERNET`. That permission exists for one
 * feature: fetching a GGUF from the Hugging Face Hub when the user asks for one. So the
 * honest sentence is:
 *
 * > Charaly works on this device. Network access is optional and is used only for model
 * > discovery and download when you turn it on.
 *
 * The earlier wording claimed the app had no internet access at all. That was true of the
 * offline core build and stopped being true the moment a real transfer pipeline was
 * wired - at which point the claim was not merely stale, it was a false statement about
 * the app's own manifest, on a screen whose whole purpose is to be believed.
 */
enum class SettingsSection(val title: String, val blurb: String) {
    APP(
        "Uygulama",
        "Charaly'nin görünümü ve hareketi.",
    ),
    AI(
        "Yapay Zekâ",
        "Dünyalarınızın sesi olan yerel model.",
    ),

    /**
     * Where the real model test lives.
     *
     * Its own section rather than a line inside AI or Advanced, because it is neither
     * configuration nor engine inspection. It is the one control on this screen that *runs
     * something*: it loads a GGUF and decodes a prompt. A user whose inference is not
     * working should not have to turn on Developer Mode to find out.
     */
    DIAGNOSTICS(
        "Tanılama",
        "Bu cihazda gerçek bir model çalıştırın.",
    ),
    OFFLINE(
        "Çevrimdışı",
        "Hiç bağlantı olmadan ne çalışır.",
    ),
    STORAGE(
        "Depolama",
        "Bu cihazda ne var ve ne kadar yer kaplıyor.",
    ),
    PRIVACY(
        "Gizlilik",
        "Charaly neyi nereye gönderir: neredeyse hiçbir şey.",
    ),
    ADVANCED(
        "Gelişmiş",
        "Motorun, çalışan bir hikâyeye kendi bakışı.",
    ),
}

object SettingsPresenter {

    /** The headline privacy claim, in one sentence. Asserted in the test suite. */
    const val PRIVACY_HEADLINE = "Charaly bu cihazda çalışır."

    /**
     * The privacy body.
     *
     * Names the permission that exists and what it is for, because a user who checks the
     * manifest and finds INTERNET deserves to find the app already told them so.
     */
    const val PRIVACY_BODY =
        "Ağ erişimi isteğe bağlıdır ve yalnızca siz bir model istediğinizde model keşfi " +
            "ve indirme için kullanılır. Sohbetler, anılar, dünya durumu ve çıkarım " +
            "asla bu cihazdan çıkmaz."

    /** The single-sentence offline claim. */
    const val OFFLINE_BODY =
        "Her dünya, her hikâye ve yüklü her model bağlantı olmadan çalışır. " +
            "Sadece yeni modellere göz atmak için bağlantı gerekir."

    /** What "local" means, stated concretely rather than as a slogan. */
    /**
     * What "local" means, stated concretely.
     *
     * Takes the authoritative selection rather than a bare boolean so the wording matches
     * what the app can actually do: a model that is installed and still loading is ready
     * for the user's purposes, and telling them "no model is loaded yet" about it is the
     * same wrong answer this whole pass exists to remove.
     */
    fun localAiBody(model: dev.charaly.runtime.model.ModelSelection, modelName: String): String {
        val detail = ModelStagePresenter.reassurance(model)
        return if (model.canGenerate) "$modelName · $detail" else detail
    }

    /** Storage, in the user's terms. */
    fun storageBody(modelCount: Int, storyCount: Int, bytes: Long): String {
        val parts = buildList {
            add(if (modelCount == 1) "1 model" else "$modelCount model")
            add(if (storyCount == 1) "1 hikâye" else "$storyCount hikâye")
        }
        val size = ModelStagePresenter.storageLabel(bytes)
        return if (size.isBlank()) parts.joinToString(" · ") else "${parts.joinToString(" · ")} · $size"
    }
}

/**
 * How a route relates to the new destinations.
 *
 * Kept in the runtime so the shell, the navigation bar and the tests all resolve the same
 * mapping. A `when` duplicated in the app layer is exactly how "Chat" ends up as a
 * bottom-bar item that routes nowhere.
 */
object DestinationRouting {

    /** The destination a route belongs to, or null when it is a full-screen destination. */
    fun primaryFor(
        isHome: Boolean,
        isWorlds: Boolean,
        isLibrary: Boolean,
        isChat: Boolean,
    ): CharalyDestination? = when {
        isHome -> CharalyDestination.HOME
        isWorlds -> CharalyDestination.WORLDS
        isLibrary -> CharalyDestination.LIBRARY
        isChat -> CharalyDestination.CHAT
        else -> null
    }

    /** Whether a destination should be offered a top bar. */
    fun showsNavigation(destination: CharalyDestination?): Boolean = destination != null
}