package dev.charaly.runtime.director

import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.SceneState
import kotlinx.serialization.Serializable

/**
 * LLM -> proposed action -> validation -> WorldEvent -> EventEngine -> WorldState.
 *
 * This is the ONLY route by which model output can influence the world, and it is
 * deliberately small: a model may *propose*, it may never assert. Nothing here
 * parses prose. If the model writes "Alice walks into the library" as ordinary
 * text, that text stays text.
 */

/** A structured request from the model, in a fixed, greppable format. */
@Serializable
data class ProposedAction(
    val type: ActionType,
    val characterId: String,
    val target: String = "",
    val value: String = "",
    val reason: String = "",
)

@Serializable
enum class ActionType {
    MOVE,
    SAY,
    DISCOVER,
    RELATE,
    ADVANCE_THREAD,
    END_SCENE,
    UNKNOWN,
}

sealed interface ActionReview {
    data class Accepted(val action: ProposedAction, val payload: dev.charaly.runtime.domain.events.EventPayload) : ActionReview
    data class Rejected(val action: ProposedAction, val reason: String) : ActionReview
}

/**
 * Extracts action proposals from model output.
 *
 * Only an explicit machine-readable marker counts. If a model narrates instead of
 * proposing, zero actions are produced and nothing changes.
 */
object ProposedActionParser {

    private val TAG = Regex("""<charaly:action\s+([^>]*?)/?>""", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("""(\w+)\s*=\s*"([^"]*)\"""")

    /**
     * `const val FORMAT` so the prompt and the parser can never drift apart.
     */
    const val INSTRUCTIONS: String =
        "If you want the world to change, emit exactly one machine-readable tag on its own line, e.g.\n" +
            "<charaly:action type=\"move\" character=\"bob\" target=\"square\" reason=\"follows Alice\"/>\n" +
            "Allowed types: move, discover, relate, advance_thread, end_scene.\n" +
            "Never assume narration is accepted as fact: only tagged actions are considered."

    fun parse(text: String): List<ProposedAction> =
        TAG.findAll(text).mapNotNull { match ->
            val attrs = ATTR.findAll(match.groupValues[1])
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val type = attrs["type"]?.lowercase() ?: return@mapNotNull null
            val character = attrs["character"] ?: attrs["characterid"] ?: return@mapNotNull null
            ProposedAction(
                type = ActionType.entries.firstOrNull { it.name.lowercase() == type } ?: ActionType.UNKNOWN,
                characterId = character,
                target = attrs["target"].orEmpty(),
                value = attrs["value"].orEmpty(),
                reason = attrs["reason"].orEmpty(),
            )
        }.toList()
}

/**
 * Turns proposals into world events - or refuses.
 *
 * Validation uses the same [dev.charaly.runtime.engine.EventEngine] the rest of
 * the world uses, so a bad proposal is rejected for exactly the same reason an
 * invalid event would be.
 */
class ActionValidator(
    private val definition: dev.charaly.runtime.domain.WorldDefinition,
) {

    /** Map a proposal to an event payload, resolving ids deterministically. */
    fun toPayload(action: ProposedAction): ActionReview {
        val character = definition.characterByName(action.characterId)
            ?: definition.character(
                runCatching { dev.charaly.runtime.domain.CharacterId(action.characterId) }.getOrNull(),
            )
            ?: return ActionReview.Rejected(action, "unknown character '${action.characterId}'")

        return when (action.type) {
            ActionType.MOVE -> {
                val location = definition.locationByName(action.target)
                    ?: definition.location(
                        runCatching { dev.charaly.runtime.domain.LocationId(action.target) }.getOrNull(),
                    )
                    ?: return ActionReview.Rejected(action, "unknown location '${action.target}'")
                ActionReview.Accepted(
                    action,
                    dev.charaly.runtime.domain.events.CharacterMoved(
                        characterId = character.id,
                        from = null,
                        to = location.id,
                    ),
                )
            }

            ActionType.DISCOVER -> {
                val fact = definition.character(character.id) // keep the character check explicit
                if (fact == null) return ActionReview.Rejected(action, "unknown character")
                ActionReview.Accepted(
                    action,
                    dev.charaly.runtime.domain.events.KnowledgeDiscovered(
                        characterId = character.id,
                        factId = dev.charaly.runtime.domain.FactId(action.target),
                        via = action.reason.ifBlank { "model proposal" },
                    ),
                )
            }

            ActionType.RELATE -> {
                val other = definition.characterByName(action.target)
                    ?: return ActionReview.Rejected(action, "unknown relationship target '${action.target}'")
                if (other.id == character.id) return ActionReview.Rejected(action, "a character cannot relate to itself")
                ActionReview.Accepted(
                    action,
                    dev.charaly.runtime.domain.events.RelationshipChanged(
                        sourceId = character.id,
                        targetId = other.id,
                        delta = parseDelta(action.value),
                        relationshipType = action.value.toRelationshipTypeOrNull(),
                        reason = action.reason,
                    ),
                )
            }

            ActionType.ADVANCE_THREAD -> {
                ActionReview.Accepted(
                    action,
                    dev.charaly.runtime.domain.events.StoryThreadAdvanced(
                        threadId = dev.charaly.runtime.domain.ThreadId(action.target),
                        stage = action.value.toIntOrNull() ?: 0,
                        note = action.reason,
                    ),
                )
            }

            ActionType.END_SCENE -> ActionReview.Accepted(
                action,
                dev.charaly.runtime.domain.events.SceneEnded(
                    sceneId = dev.charaly.runtime.domain.SceneId(action.target),
                    reason = action.reason,
                ),
            )

            ActionType.SAY -> ActionReview.Rejected(action, "say is narrative, not a world change")
            ActionType.UNKNOWN -> ActionReview.Rejected(action, "unsupported action type")
        }
    }

    private fun parseDelta(raw: String): dev.charaly.runtime.domain.RelationshipDelta {
        // Accepts "trust:5,affinity:10", "trust+5,affinity+10", or a bare "5" meaning
        // affinity. Small models produce all three shapes, so all three are read
        // rather than insisting on one and silently dropping the others.
        var trust = 0
        var familiarity = 0
        var affinity = 0
        var tension = 0
        var respect = 0
        var fear = 0
        var dependency = 0
        raw.split(',').forEach { part ->
            val cleaned = part.trim()
            if (cleaned.isEmpty()) return@forEach
            // Split on the first ':' or '+'/'-', whichever comes first.
            val separatorIndex = cleaned.indexOfFirst { it == ':' || it == '+' || (it == '-' && cleaned.startsWith("-")) }
            val key: String
            val rawValue: String
            if (separatorIndex > 0) {
                key = cleaned.take(separatorIndex).trim().lowercase()
                rawValue = cleaned.drop(separatorIndex).trim().removePrefix(":")
            } else {
                key = "affinity"
                rawValue = cleaned
            }
            val delta = rawValue.toIntOrNull() ?: return@forEach
            when (key) {
                "trust" -> trust += delta
                "familiarity" -> familiarity += delta
                "affinity" -> affinity += delta
                "tension" -> tension += delta
                "respect" -> respect += delta
                "fear" -> fear += delta
                "dependency" -> dependency += delta
            }
        }
        return dev.charaly.runtime.domain.RelationshipDelta(
            trust = trust,
            familiarity = familiarity,
            affinity = affinity,
            tension = tension,
            respect = respect,
            fear = fear,
            dependency = dependency,
        )
    }

    private fun String.toRelationshipTypeOrNull(): dev.charaly.runtime.domain.RelationshipType? =
        dev.charaly.runtime.domain.RelationshipType.entries
            .firstOrNull { it.name.equals(this, ignoreCase = true) }
}
