package dev.charaly.app.ui.packstore

import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.RelationshipType

/**
 * THE AUTHORING DRAFT: everything the pack wizard collects, before it is a pack.
 *
 * ## Why the draft is its own pure model
 *
 * The wizard's six steps edit *this* - plain data classes with no Compose in sight - and a
 * separate compiler turns it into a real `StoryPack`. The split is what lets the whole
 * authoring pipeline be unit tested on the JVM: a draft is built in a test the way the
 * screen builds it, compiled, validated, exported and re-decoded, with no device anywhere.
 * A draft that lived inside a composable would be untestable, and untestable authoring is
 * how a pack creator starts minting packs the installer later refuses.
 *
 * ## Why rows carry their own row ids *and* grow stable slugs
 *
 * Two different jobs. A [rowId] is scaffolding: it keeps a LazyColumn key unique and keeps
 * a dropdown pointed at the same row while the author renames things. A *slug* is the
 * published identity: derived from the row's own content (its name), so two devices
 * authoring the same story produce the same ids and a scene can link to a character
 * without a hidden registry. The compiler derives the slugs; the UI never shows them
 * except in step 6's "paket kimliği", which is the author's own id and explicitly theirs
 * to read.
 *
 * ## Why incomplete rows are dropped instead of rescued
 *
 * A character with no name is not a character, and inventing a name for it ("Kişi 1")
 * would be fabricating content - the one thing the authoring flow must never do. Rows that
 * lack their one required field simply do not compile, and the validators then say so in
 * Turkish ("Paketin kadrosu boş"), which is a better lesson than a silent placeholder.
 */

/**
 * The one thing every editor row shares: a stable scaffolding id.
 *
 * Implemented by every draft row so the list helpers (`updated` / `removed`) and the lazy
 * keys work on one shape - and deliberately *not* the domain id: this is UI scaffolding,
 * the slug is the publication identity, and conflating the two is how a rename silently
 * breaks every cross reference in a pack.
 */
interface DraftRow {
    val rowId: String
}

/** One free-text line in a repeatable list (a world rule, a world fact). */
data class TextDraft(
    override val rowId: String = newRowId(),
    val text: String = "",
) : DraftRow

/** A place. [interior] is the only structural property the wizard asks for. */
data class LocationDraft(
    override val rowId: String = newRowId(),
    val name: String = "",
    val description: String = "",
    val interior: Boolean = true,
) : DraftRow

/** A piece of authored background lore. */
data class LoreDraft(
    override val rowId: String = newRowId(),
    val title: String = "",
    val content: String = "",
) : DraftRow

/** One person in the cast. [startLocationRowId] points at a [LocationDraft]. */
data class CharacterDraft(
    override val rowId: String = newRowId(),
    val name: String = "",
    val tagline: String = "",
    val description: String = "",
    val personality: String = "",
    val startLocationRowId: String = "",
    val role: CharacterRole = CharacterRole.PROTAGONIST,
) : DraftRow

/** A role the *player* can take. */
data class PersonaDraft(
    override val rowId: String = newRowId(),
    val name: String = "",
    val tagline: String = "",
    val rolePrompt: String = "",
    val abilitiesRaw: String = "",
    val restrictionsRaw: String = "",
) : DraftRow

/**
 * A directed relationship between two [CharacterDraft]s.
 *
 * The four axes are free integers rather than prose because `Relationship` refuses
 * anything outside 0..100 at construction time - and a slider cannot hand it 140.
 */
data class RelationshipDraft(
    override val rowId: String = newRowId(),
    val sourceRowId: String = "",
    val targetRowId: String = "",
    /** `RelationshipType.name`, or blank for "not classified". */
    val typeName: String = "",
    val trust: Int = 50,
    val affinity: Int = 50,
    val tension: Int = 0,
    val familiarity: Int = 0,
) : DraftRow {
    /** The optional type resolved, null when the author left it unclassified. */
    fun resolvedType(): RelationshipType? =
        RelationshipType.entries.firstOrNull { it.name == typeName }
}

/** One way of opening the story. [startLocationRowId] is required to compile. */
data class ScenarioDraft(
    override val rowId: String = newRowId(),
    val title: String = "",
    val tagline: String = "",
    val startLocationRowId: String = "",
    val focusCharacterRowId: String = "",
    val castRowIds: Set<String> = emptySet(),
) : DraftRow

/**
 * The three kinds of consequence a branch may have.
 *
 * Deliberately the smallest honest set: a flag, a nudge between two people, and the plot
 * moving. Everything else the engine can do is engine business, and an effect editor that
 * offered all of it would be an event editor wearing a wizard's coat.
 */
enum class EffectKind(val label: String) {
    SET_VARIABLE("Değişken ata"),
    TRUST_SHIFT("Güveni değiştir"),
    ADVANCE_THREAD("Çizgiyi ilerlet"),
}

/**
 * One typed effect, as the small editor stores it.
 *
 * A single shape with unused fields rather than a sealed hierarchy, because the editor is
 * one card that switches on [kind] - and a sealed hierarchy would buy type purity at the
 * price of three parallel editors that drift apart.
 */
data class EffectDraft(
    val kind: EffectKind = EffectKind.SET_VARIABLE,
    /** SET_VARIABLE: the world variable written. */
    val variableKey: String = "",
    val variableValue: String = "",
    /** TRUST_SHIFT: who grows to trust whom, and by how much (can be negative). */
    val sourceRowId: String = "",
    val targetRowId: String = "",
    val trustDelta: Int = 0,
    /** ADVANCE_THREAD: which story thread moves. */
    val threadRowId: String = "",
)

/** One branch inside a scene: a label in the player's voice, and one real effect. */
data class BranchDraft(
    override val rowId: String = newRowId(),
    val label: String = "",
    val effect: EffectDraft = EffectDraft(),
) : DraftRow

/** A situation the story can arrive in. */
data class SceneDraft(
    override val rowId: String = newRowId(),
    val name: String = "",
    val description: String = "",
    val atmosphere: String = "",
    val locationRowId: String = "",
    val participants: Set<String> = emptySet(),
    val branches: List<BranchDraft> = emptyList(),
) : DraftRow

/**
 * What the story asks of the player, won and lost on world variables.
 *
 * Completion and failure are both `variable key equals value` conditions - the one shape
 * the wizard's small editor can honestly offer, and the same shape scene branches write,
 * which is what makes an authored objective reachable in play rather than decorative.
 */
data class ObjectiveDraft(
    override val rowId: String = newRowId(),
    val title: String = "",
    val description: String = "",
    val completionKey: String = "",
    val completionValue: String = "",
    /** Blank [failureKey] means "this objective can be won but never lost". */
    val failureKey: String = "",
    val failureValue: String = "",
) : DraftRow

/** A narrative thread: an arc the world keeps track of. */
data class ThreadDraft(
    override val rowId: String = newRowId(),
    val title: String = "",
    val description: String = "",
) : DraftRow

/**
 * The one optional conditional event: "when key equals value, do this".
 *
 * Optional in the type (`AuthoringDraft.event` is nullable) rather than by a flag,
 * because "no conditional event" and "a conditional event with no trigger" must not be
 * the same state - the latter would fire unconditionally in play.
 */
data class EventDraft(
    override val rowId: String = newRowId(),
    val title: String = "",
    val whenKey: String = "",
    val whenValue: String = "",
    val effect: EffectDraft = EffectDraft(),
) : DraftRow

/**
 * The whole wizard state: six steps of editing, one value.
 *
 * Held as one immutable value so a step change is a `copy`, and so the compiler can be
 * handed exactly what the screen shows - there is no second, hidden source of truth that
 * a test and the UI could disagree about.
 */
data class AuthoringDraft(
    // ---- 1. KİMLİK --------------------------------------------------------
    val title: String = "",
    val premise: String = "",
    val description: String = "",
    val author: String = "",
    val publisher: String = "",
    /** Comma-separated, split at compile time. Tags are a typing convenience, not a list. */
    val tagsRaw: String = "",
    /** Two-letter content language ("tr" or "en"). */
    val language: String = "tr",
    val coverSeed: String = "",
    val version: String = "1.0.0",
    // ---- 2. DÜNYA ---------------------------------------------------------
    val universe: String = "",
    val era: String = "",
    val geography: String = "",
    val rules: List<TextDraft> = emptyList(),
    val worldFacts: List<TextDraft> = emptyList(),
    val locations: List<LocationDraft> = emptyList(),
    val lore: List<LoreDraft> = emptyList(),
    // ---- 3. KADRO ---------------------------------------------------------
    val characters: List<CharacterDraft> = emptyList(),
    val personas: List<PersonaDraft> = emptyList(),
    val relationships: List<RelationshipDraft> = emptyList(),
    // ---- 4. HİKÂYE -------------------------------------------------------
    val scenarios: List<ScenarioDraft> = emptyList(),
    val scenes: List<SceneDraft> = emptyList(),
    val objectives: List<ObjectiveDraft> = emptyList(),
    val threads: List<ThreadDraft> = emptyList(),
    val event: EventDraft? = null,
) {
    /** The one field nothing can be built without. */
    val hasTitle: Boolean get() = title.isNotBlank()
}

/** A fresh row id. Scaffolding only - never rendered, never a domain id. */
fun newRowId(): String = java.util.UUID.randomUUID().toString().take(8)

/**
 * A stable id from a name.
 *
 * ## Why it folds Turkish characters instead of keeping them
 *
 * A pack id becomes a file name and a URL path segment - `mirac.paris.charaly-pack` - so
 * "Kaçış Yıldızı" must become `kacis-yildizi` and not `kaçış-yıldızı`. Diacritics are
 * stripped by Unicode decomposition (ç → c, ş → s, İ → i) and the dotless ı is mapped by
 * hand because it has no decomposition. Everything not ASCII becomes a hyphen, so no
 * title can ever produce an id that cannot be encoded.
 *
 * Deterministic on purpose: the same draft compiled on two devices must produce the same
 * ids, or cross-references between rows would stop resolving on the second one.
 */
fun stableSlug(name: String): String {
    val folded = name
        .lowercase()
        .replace('ı', 'i')
        .let { java.text.Normalizer.normalize(it, java.text.Normalizer.Form.NFD) }
        .replace(Regex("\\p{M}"), "")
    val slug = folded
        .map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }
        .joinToString("")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString("-")
    return slug.ifBlank { "world" }.take(48)
}

/**
 * Hands out slugs that are unique within one kind of content.
 *
 * Two "Ada" locations would otherwise collide into one id and silently merge rows in
 * every cross reference. The second one gets a numeric suffix (`ada-2`), which is ugly
 * and honest and - crucially - deterministic: row order is author order, so the suffix
 * survives a recompile unchanged.
 */
class SlugRegister {
    private val taken = mutableMapOf<String, Int>()

    fun reserve(name: String): String {
        val base = stableSlug(name)
        val count = (taken[base] ?: 0) + 1
        taken[base] = count
        return if (count == 1) base else "$base-$count"
    }
}

/** Splits a comma-separated editor field into trimmed, non-empty entries. */
fun splitCsv(raw: String): List<String> =
    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }