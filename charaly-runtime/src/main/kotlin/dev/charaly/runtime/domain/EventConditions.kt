package dev.charaly.runtime.domain

/**
 * Deterministic evaluation of authored [EventCondition]s.
 *
 * Pure functions over authoritative state. No LLM, no randomness, no wall clock:
 * the same world always produces the same answer, so "conditional events" stay a
 * property of the simulation instead of a mood.
 */
object EventConditions {

    fun all(conditions: List<EventCondition>, instance: StoryInstance): Boolean =
        conditions.all { evaluate(it, instance) }

    fun any(conditions: List<EventCondition>, instance: StoryInstance): Boolean =
        conditions.any { evaluate(it, instance) }

    fun evaluate(condition: EventCondition, instance: StoryInstance): Boolean = when (condition) {
        is EventCondition.VariableEquals ->
            instance.worldState.variables[condition.key]?.value.equals(condition.value, ignoreCase = true)

        is EventCondition.VariableNotEquals ->
            !instance.worldState.variables[condition.key]?.value.equals(condition.value, ignoreCase = true)

        is EventCondition.CharacterAtLocation ->
            instance.characters[condition.characterId]?.locationId == condition.locationId

        is EventCondition.CharacterNotAtLocation ->
            instance.characters[condition.characterId]?.locationId != condition.locationId

        is EventCondition.ThreadStageAtLeast ->
            (instance.storyThreads[condition.threadId]?.stage ?: -1) >= condition.stage

        is EventCondition.ThreadStatusIs ->
            instance.storyThreads[condition.threadId]?.status == condition.status

        is EventCondition.RelationshipTrustAtLeast ->
            (instance.worldState.relationship(condition.sourceId, condition.targetId)?.trust ?: -1) >=
                condition.minTrust

        is EventCondition.StoryTimeReached ->
            !instance.worldClock.now.isBefore(condition.time)

        is EventCondition.CharacterKnowsFact ->
            instance.knowledge.knows(condition.characterId, condition.factId)

        is EventCondition.FactExists ->
            instance.knowledge.fact(condition.factId) != null

        is EventCondition.SceneIsOpen ->
            instance.worldState.scenesAt(condition.locationId).any { it.isActive() }
    }

    /** Human readable form of a whole condition list. */
    fun describe(conditions: List<EventCondition>): String = when {
        conditions.isEmpty() -> "always"
        else -> conditions.joinToString(" and ") { it.describe() }
    }
}