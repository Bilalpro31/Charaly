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
    val note: String = "",
    val history: List<RelationshipChange> = emptyList(),
    val updatedAt: StoryTime = StoryTime.START,
) {
    init {
        require(sourceId != targetId) { "A character cannot have a relationship with itself ($sourceId)" }
        require(trust in 0..100) { "trust out of range: $trust" }
        require(familiarity in 0..100) { "familiarity out of range: $familiarity" }
        require(affinity in 0..100) { "affinity out of range: $affinity" }
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
            note = note ?: this.note,
            updatedAt = at,
            history = history + RelationshipChange(
                delta = delta,
                type = type ?: relationshipType,
                reason = note ?: "",
                at = at,
            ),
        )
        return updated
    }

    /** Short, prompt-friendly rendering. */
    fun describe(fromPerspectiveOf: CharacterId): String {
        val stance = if (fromPerspectiveOf == sourceId) relationshipType.label else relationshipType.reversedLabel
        return buildString {
            append(stance)
            append(" (trust $trust/100, familiarity $familiarity/100, affinity $affinity/100)")
        }
    }

    companion object {
        fun of(a: CharacterId, b: CharacterId): RelationshipKey = RelationshipKey(a, b)
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
) {
    val isZero: Boolean get() = trust == 0 && familiarity == 0 && affinity == 0

    companion object {
        val NONE = RelationshipDelta()
    }
}

@Serializable
data class RelationshipChange(
    val delta: RelationshipDelta,
    val type: RelationshipType,
    val reason: String = "",
    val at: StoryTime,
)

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
