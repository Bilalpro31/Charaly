package dev.charaly.runtime.engine

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.RoutinePlacement
import dev.charaly.runtime.domain.StoryInstance
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.domain.events.CharacterRoutineApplied

/**
 * Where every character in a world is *supposed* to be right now.
 *
 * ## The problem this solves
 *
 * Charaly is a world simulator, not a chat client. An ice-cream vendor exists in
 * Paris whether or not the player is talking to him: he opens his shop at 09:00,
 * serves customers through the afternoon, closes at 18:00 and goes home at 19:00.
 * If the player walks into that shop in the evening, the vendor must already be
 * there - discovered from world state, not conjured into the conversation.
 *
 * ## Why it is coarse, not per-second
 *
 * A full simulation would re-evaluate every character on every tick. That is
 * wasteful and, worse, it makes the world hard to replay. Instead this runs only when
 * story time actually moves, compares each character's *scheduled* placement against
 * their *current* authoritative state, and emits a relocation only for the characters
 * whose placement genuinely differs.
 *
 * Consequences that matter:
 *  * idle time costs nothing;
 *  * the set of relocations is a pure function of (definition, state, time);
 *  * re-running it at the same time is a no-op, so a replayed or duplicated story
 *    produces identical results.
 *
 * ## It proposes; the engine disposes
 *
 * [relocationsFor] returns payloads. Nothing here mutates a [StoryInstance]. The
 * caller schedules and applies them through [EventEngine] like every other world
 * change, which keeps "the LLM never writes world state" true for routines too.
 */
class PresenceEngine(private val definition: WorldDefinition) {

    /**
     * The characters whose routine placement differs from their current state.
     *
     * Ordered deterministically by character id so a replay produces the same event
     * log in the same order.
     */
    fun relocationsFor(instance: StoryInstance): List<CharacterRoutineApplied> {
        val now = instance.worldClock.now
        val out = mutableListOf<CharacterRoutineApplied>()
        for (character in definition.characters) {
            // Background crowds are collective: no individual schedule, no state.
            if (!character.storyRole.hasIndividualState) continue
            if (!character.routine.isScheduled) continue
            val placement = character.routine.resolve(now) ?: continue
            relocation(character.id, placement, instance)?.let(out::add)
        }
        return out.sortedBy { it.characterId.value }
    }

    /** Convenience: who is standing in [locationId] according to the clock. */
    fun scheduledAt(instance: StoryInstance, locationId: dev.charaly.runtime.domain.LocationId): List<CharacterId> =
        relocationsFor(instance)
            .filter { it.to == locationId }
            .map { it.characterId }
            .distinct()
            .sortedBy { it.value }

    /** Who among [candidates] has a routine at all, with where they should be. */
    fun placementsFor(
        instance: StoryInstance,
        candidates: Collection<CharacterId>,
    ): Map<CharacterId, RoutinePlacement> {
        val now = instance.worldClock.now
        val out = LinkedHashMap<CharacterId, RoutinePlacement>()
        candidates.sortedBy { it.value }.forEach { id ->
            val character = definition.character(id) ?: return@forEach
            if (!character.storyRole.hasIndividualState) return@forEach
            character.routine.resolve(now)?.let { out[id] = it }
        }
        return out
    }

    /**
     * Every character the routine places at [locationId] at [time], whether or not
     * the world state has caught up yet.
     *
     * This is what "Andre is at his shop" means for a character profile: the
     * schedule is the truth about his day, independent of whether the player has
     * already caused the clock to reach that hour.
     */
    fun residentsOf(
        locationId: dev.charaly.runtime.domain.LocationId,
        time: StoryTime,
    ): List<CharacterId> =
        definition.characters
            .filter { it.storyRole.hasIndividualState && it.storyRole.isScheduled }
            .filter { it.routine.resolve(time)?.locationId == locationId }
            .map { it.id }
            .sortedBy { it.value }

    private fun relocation(
        id: CharacterId,
        placement: RoutinePlacement,
        instance: StoryInstance,
    ): CharacterRoutineApplied? {
        val runtime = instance.characters[id] ?: return null
        // Never relocate a character to a place the pack does not define.
        if (definition.location(placement.locationId) == null) return null

        val outOfSync = runtime.locationId != placement.locationId ||
            runtime.activity != placement.activity ||
            runtime.activityLabel != placement.activityLabel
        val entryMoved = placement.fromEntryMinute != runtime.routineEntryMinute

        /*
         * A routine is a *default*, not a leash.
         *
         * The engine re-places a character only when their schedule actually moves on
         * to the next entry, or the first time they are placed at all. A story event
         * that deliberately puts someone somewhere off-routine - Marinette staying out
         * late, Gabriel summoning someone to the mansion - is therefore respected until
         * the next entry boundary instead of being undone on every tick.
         *
         * Without this, any authored placement would be reverted the moment the player
         * advanced the clock, which would make the event system fight itself.
         */
        if (!entryMoved) return null
        if (!outOfSync) return null

        return CharacterRoutineApplied(
            characterId = id,
            from = runtime.locationId,
            to = placement.locationId,
            activity = placement.activity,
            activityLabel = placement.activityLabel,
            routineEntryMinute = placement.fromEntryMinute,
        )
    }
}