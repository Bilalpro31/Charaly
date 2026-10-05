package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * An explicit, persistent relationship between two characters.
 *
 * Relationships are authoritative world state: they are changed by
 * [dev.charaly.runtime.domain.events.RelationshipChanged] events applied by the
 * [dev.charaly.runtime.engine.EventEngine] and never by parsing prose.
 */
@Serializable
data class Relationship(
    val sourceId: CharacterId,
    val targetId: CharacterId,
    val relationshipType: RelationshipType = RelationshipType.UNKNOWN,
    /** 0..100 */
    val trust: Int = 50,
    /** 0..100 */
    val familiarity: Int = 0,
    /** 0..100, positive = liking */
    val affinity: Int = 50,

    // ---- the four axes affinity cannot express --------------------------
    //
    // Trust, familiarity and affinity are not enough to describe a relationship.
    // Someone can be trusted, well known and *not liked* (a competent co-worker), or
    // deeply liked and distrusted (the friend who keeps lying). Collapsing those states
    // into one number is what makes a cast behave like a single friendship score.
    //
    // All 0..100, all independent, all changed only by validated events.

    /** 0..100. Unresolved conflict: resentment, awkward history, an argument. */
    val tension: Int = 0,
    /** 0..100. Esteem for the other's judgement or ability. */
    val respect: Int = 50,
    /** 0..100. How much the source is afraid of the target. */
    val fear: Int = 0,
    /** 0..100. How badly the source needs the target. */
    val dependency: Int = 0,

    /**
     * How far along this relationship is.
     *
     * Deliberately *not* derived from the numeric axes. Familiarity 60 means the same
     * thing on day one and day fifty; what changes is how settled the relationship is,
     * and that is its own piece of state the engine advances on its own events.
     */
    val stage: RelationshipStage = RelationshipStage.STRANGER,

    /** Story time of the last interaction between these two. */
    val lastInteraction: StoryTime? = null,
    val note: String = "",
    val history: List<RelationshipChange> = emptyList(),
    val updatedAt: StoryTime = StoryTime.START,
) {
    init {
        require(sourceId != targetId) { "A character cannot have a relationship with itself ($sourceId)" }
        require(trust in 0..100) { "trust out of range: $trust" }
        require(familiarity in 0..100) { "familiarity out of range: $familiarity" }
        require(affinity in 0..100) { "affinity out of range: $affinity" }
        require(tension in 0..100) { "tension out of range: $tension" }
        require(respect in 0..100) { "respect out of range: $respect" }
        require(fear in 0..100) { "fear out of range: $fear" }
        require(dependency in 0..100) { "dependency out of range: $dependency" }
    }

    fun key(): RelationshipKey = RelationshipKey(sourceId, targetId)

    fun apply(
        delta: RelationshipDelta,
        type: RelationshipType? = null,
        note: String? = null,
        at: StoryTime,
    ): Relationship {
        val updated = copy(
            relationshipType = type ?: relationshipType,
            trust = (trust + delta.trust).coerceIn(0, 100),
            familiarity = (familiarity + delta.familiarity).coerceIn(0, 100),
            affinity = (affinity + delta.affinity).coerceIn(0, 100),
            tension = (tension + delta.tension).coerceIn(0, 100),
            respect = (respect + delta.respect).coerceIn(0, 100),
            fear = (fear + delta.fear).coerceIn(0, 100),
            dependency = (dependency + delta.dependency).coerceIn(0, 100),
            stage = stage.advanceTo(delta, familiarityAfter = (familiarity + delta.familiarity).coerceIn(0, 100)),
            note = note ?: this.note,
            updatedAt = at,
            lastInteraction = at,
            history = history + RelationshipChange(
                delta = delta,
                type = type ?: relationshipType,
                reason = note ?: "",
                at = at,
                stageBefore = stage,
                stageAfter = stage.advanceTo(
                    delta,
                    familiarityAfter = (familiarity + delta.familiarity).coerceIn(0, 100),
                ),
            ),
        )
        return updated
    }

    /**
     * The one-line summary a prompt actually needs.
     *
     * Only the axes that are *noteworthy* are named, because a relationship described
     * as "trust 50, familiarity 30, affinity 50, tension 0, respect 50, fear 0,
     * dependency 0" teaches a small model nothing and wastes most of the context
     * window. Neutral values are omitted; anything above [NOTABLE_THRESHOLD] is named.
     */
    fun describeBriefly(): String {
        val parts = mutableListOf<String>()
        if (tension >= NOTABLE_THRESHOLD) parts += "unresolved tension ($tension)"
        if (fear >= NOTABLE_THRESHOLD) parts += "afraid of them ($fear)"
        if (dependency >= NOTABLE_THRESHOLD) parts += "dependent on them ($dependency)"
        if (trust <= LOW_THRESHOLD && familiarity >= FRIENDLY_FAMILIARITY) {
            parts += "known well and not trusted ($trust)"
        }
        if (respect >= HIGH_THRESHOLD && affinity <= LOW_THRESHOLD) {
            parts += "respected but not liked ($respect)"
        }
        return parts.joinToString("; ")
    }

    /**
     * The full prompt line, including the stage.
     *
     * [describe] is for the UI; this is for the model, and it names the stage because
     * "a close friend" and "someone she met yesterday" call for entirely different
     * behaviour from the same three numbers.
     */
    fun describe(fromPerspectiveOf: CharacterId): String {
        val stance = if (fromPerspectiveOf == sourceId) relationshipType.label else relationshipType.reversedLabel
        return buildString {
            append(stage.describe(stance))
            // The "/100" is kept deliberately: it tells the model these are bounded
            // measurements rather than adjectives, which changes how literally it
            // treats "trust 20".
            append(" (trust $trust/100, familiarity $familiarity/100, affinity $affinity/100")
            val notable = describeBriefly()
            if (notable.isNotBlank()) append("; ").append(notable)
            append(")")
        }
    }

    companion object {
        fun of(a: CharacterId, b: CharacterId): RelationshipKey = RelationshipKey(a, b)

        /** Above this, an axis is worth telling the model about. */
        const val NOTABLE_THRESHOLD = 60
        private const val HIGH_THRESHOLD = 70
        private const val LOW_THRESHOLD = 35
        private const val FRIENDLY_FAMILIARITY = 40
    }
}

/**
 * Directed identity of a relationship pair.
 *
 * Serialized as a plain string ("source->target") so it can be used as a JSON
 * map key in [dev.charaly.runtime.domain.WorldState.relationships] without
 * resorting to untyped structure maps.
 */
@Serializable(with = RelationshipKeySerializer::class)
data class RelationshipKey(val sourceId: CharacterId, val targetId: CharacterId) {
    override fun toString(): String = "${sourceId.value}->${targetId.value}"

    fun reversed(): RelationshipKey = RelationshipKey(targetId, sourceId)
}

object RelationshipKeySerializer : kotlinx.serialization.KSerializer<RelationshipKey> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "dev.charaly.runtime.domain.RelationshipKey",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: RelationshipKey) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): RelationshipKey {
        val raw = decoder.decodeString()
        val separator = raw.indexOf(ARROW)
        require(separator > 0) { "malformed relationship key '$raw'" }
        return RelationshipKey(
            sourceId = CharacterId(raw.substring(0, separator)),
            targetId = CharacterId(raw.substring(separator + ARROW.length)),
        )
    }

    private const val ARROW = "->"
}

@Serializable
data class RelationshipDelta(
    val trust: Int = 0,
    val familiarity: Int = 0,
    val affinity: Int = 0,
    val tension: Int = 0,
    val respect: Int = 0,
    val fear: Int = 0,
    val dependency: Int = 0,
) {
    val isZero: Boolean
        get() = trust == 0 && familiarity == 0 && affinity == 0 &&
            tension == 0 && respect == 0 && fear == 0 && dependency == 0

    /**
     * Whether this delta moves the relationship's *stage*, not merely its numbers.
     *
     * Separate from [isZero] because familiarity +2 is a real change that must not
     * advance a stage: if any single nudge moved people from acquaintance to friend,
     * the stage would be a function of how many small events happened rather than of
     * how close they are.
     */
    fun movesStage(current: RelationshipStage, familiarityAfter: Int): Boolean =
        stageAfter(current, familiarityAfter) != current

    /**
     * The stage this relationship has reached, given its familiarity.
     *
     * Purely a function of familiarity, and clamped to [current] so it can never jump
     * more than one step. Familiarity is not enough to *leave* a stage, though: a
     * CLOSE relationship whose familiarity collapses does not demote on this rule,
     * because demotion needs a deliberate event. Silently demoting people would let a
     * single bad roll undo a relationship the story built.
     */
    internal fun stageAfter(current: RelationshipStage, familiarityAfter: Int): RelationshipStage {
        val derived = when {
            familiarityAfter >= FRIEND_FAMILIARITY -> RelationshipStage.CLOSE
            familiarityAfter >= ACQUAINTED_FAMILIARITY -> RelationshipStage.ESTABLISHED
            familiarityAfter > 0 -> RelationshipStage.ACQUAINTED
            else -> RelationshipStage.STRANGER
        }
        if (derived.ordinal <= current.ordinal) return current
        // Never more than one step per change.
        return current.next()
    }

    companion object {
        val NONE = RelationshipDelta()

        private const val ACQUAINTED_FAMILIARITY = 20
        private const val FRIEND_FAMILIARITY = 65
    }
}

/**
 * How settled a relationship is.
 *
 * Its own axis, deliberately *not* derived on read. Familiarity 60 means the same
 * thing on day one and day fifty; what changes is whether the two have history, and a
 * relationship that has been interacted with for a week should behave differently from
 * a brand new acquaintance with the same numbers - so the engine advances this
 * explicitly, and [RelationshipStage.advanceTo] records what happened.
 */
@Serializable
enum class RelationshipStage {
    /** Never properly met, or met and nothing came of it. */
    STRANGER,

    /** Knows of each other. */
    ACQUAINTED,

    /** Has a history of actual interaction. */
    ESTABLISHED,

    /** Close. Would be missed. */
    CLOSE,
    ;

    /**
     * The next stage, or this one.
     *
     * Monotone in familiarity, which means stage can never jump more than one step per
     * event: a single relationship change can nudge someone along, it cannot teleport
     * a stranger into the closest relationship in the story.
     */
    fun advanceTo(delta: RelationshipDelta, familiarityAfter: Int): RelationshipStage =
        delta.stageAfter(this, familiarityAfter)

    /** Whether moving forward from here is possible. */
    fun canAdvanceTo(other: RelationshipStage): Boolean = other.ordinal <= ordinal + 1

    /** The next stage, or this one if already the last. */
    fun next(): RelationshipStage =
        entries.getOrNull(ordinal + 1) ?: this

    fun describe(stance: String): String = when (this) {
        STRANGER -> stance
        ACQUAINTED -> "$stance, not well known yet"
        ESTABLISHED -> "$stance, with real history"
        CLOSE -> "close $stance"
    }
}

@Serializable
data class RelationshipChange(
    val delta: RelationshipDelta,
    val type: RelationshipType,
    val reason: String = "",
    val at: StoryTime,
    /**
     * The stage before and after this change.
     *
     * Kept on the record rather than only on the relationship, because "when did these
     * two become friends, and what happened" is exactly the question a story asks and
     * exactly the question a bare current-stage value cannot answer.
     */
    val stageBefore: RelationshipStage = RelationshipStage.STRANGER,
    val stageAfter: RelationshipStage = RelationshipStage.STRANGER,
) {
    /** Did this particular change move the relationship forward a stage? */
    val advancedStage: Boolean get() = stageAfter.ordinal > stageBefore.ordinal
}

@Serializable
enum class RelationshipType(val label: String, val reversedLabel: String) {
    STRANGER("a stranger", "a stranger"),
    ACQUAINTANCE("an acquaintance", "an acquaintance"),
    FRIEND("a friend", "a friend"),
    CLOSE_FRIEND("a close friend", "a close friend"),
    ALLY("an ally", "an ally"),
    RIVAL("a rival", "a rival"),
    ENEMY("an enemy", "an enemy"),
    MENTOR("a mentor", "a mentee"),
    STUDENT("a student", "a mentor"),
    FAMILY("family", "family"),
    PARTNER("a partner", "a partner"),
    UNKNOWN("someone they have not classified", "someone they have not classified"),
    ;

    val isHostile: Boolean get() = this == ENEMY || this == RIVAL
    val isFriendly: Boolean get() = this == FRIEND || this == CLOSE_FRIEND || this == ALLY || this == PARTNER || this == FAMILY
}
