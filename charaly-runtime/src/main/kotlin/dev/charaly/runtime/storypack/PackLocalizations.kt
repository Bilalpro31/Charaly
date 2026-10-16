package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import kotlinx.serialization.encodeToString

/**
 * Pack-level localization.
 *
 * ## What is translated
 *
 * Everything a *reader* reads: titles, premises, atmosphere, character sheets, scene
 * descriptions, objective text, the labels on branches. Not the ids, not the conditions,
 * not the effects - those are the pack's programming, and translating them would break it.
 *
 * ## The fallback chain
 *
 * ```
 * requested language (the device) -> the pack's own language -> English -> the authored text
 * ```
 *
 * Stated as a chain rather than a lookup because the fourth step matters: a key with no
 * translation at all still renders the author's original words, so a partially translated
 * pack degrades to "everything is in Turkish now" and never to blank labels.
 *
 * ## Key grammar
 *
 * ```
 * meta.title, meta.premise, meta.description
 * character.<id>.name, character.<id>.description, character.<id>.tagline
 * location.<id>.name, location.<id>.description
 * scene.<id>.name, scene.<id>.description, scene.<id>.atmosphere
 * objective.<id>.title, objective.<id>.description
 * scenario.<id>.title, scenario.<id>.tagline, scenario.<id>.description
 * persona.<id>.name, persona.<id>.tagline, persona.<id>.description
 * event.<id>.title, thread.<id>.title, faction.<id>.name, lore.<id>.title
 * ```
 *
 * Grammar is checked rather than assumed, so a typo in a translation file is reported as
 * "this translates nothing" instead of silently doing nothing forever.
 */
/**
 * The `language -> (key -> text)` table as a document.
 *
 * One place that knows how a translation file is spelled, so every reader and writer of
 * `localization/<lang>.json` agrees with every other one.
 */
object LocalizationCodec {
    fun encode(json: kotlinx.serialization.json.Json, table: Map<String, String>): String =
        json.encodeToString<Map<String, String>>(table)

    fun decode(json: kotlinx.serialization.json.Json, raw: String): Map<String, String>? =
        runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrNull()
}

object PackLocalizer {

    const val META_TITLE = "meta.title"
    const val META_PREMISE = "meta.premise"
    const val META_DESCRIPTION = "meta.description"

    private val KINDS = setOf(
        "meta", "character", "location", "scene", "objective",
        "scenario", "persona", "event", "thread", "faction", "lore", "choice",
    )

    /** The keys a complete translation would contain for this pack. */
    fun expectedKeys(pack: StoryPack): List<String> {
        val keys = mutableListOf(META_TITLE, META_PREMISE, META_DESCRIPTION)
        pack.characters.forEach { keys += listOf("character.${it.id.value}.name", "character.${it.id.value}.description") }
        pack.locations.forEach { keys += listOf("location.${it.id.value}.name", "location.${it.id.value}.description") }
        pack.scenes.forEach { scene ->
            keys += listOf("scene.${scene.id}.name", "scene.${scene.id}.description")
            scene.choices.forEach { keys += "choice.${scene.id}.${it.id}.label" }
        }
        pack.objectives.forEach { keys += listOf("objective.${it.id}.title", "objective.${it.id}.description") }
        pack.scenarios.forEach { keys += listOf("scenario.${it.id}.title", "scenario.${it.id}.tagline") }
        pack.personas.forEach { keys += listOf("persona.${it.id}.name", "persona.${it.id}.tagline") }
        pack.events.forEach { keys += "event.${it.id}.title" }
        pack.initialStoryThreads.forEach { keys += "thread.${it.id.value}.title" }
        pack.factions.forEach { keys += "faction.${it.id}.name" }
        pack.lore.forEach { keys += "lore.${it.id}.title" }
        return keys.distinct()
    }

    /** Whether a key addresses something this pack actually has. */
    fun isValidKey(key: String, pack: StoryPack): Boolean {
        val segments = key.split('.')
        if (segments.size < 2) return false
        val kind = segments[0]
        if (kind !in KINDS) return false
        return when (kind) {
            "meta" -> key == META_TITLE || key == META_PREMISE || key == META_DESCRIPTION
            "character" -> segments.size == 3 && pack.character(dev.charaly.runtime.domain.CharacterId(segments[1])) != null
            "location" -> segments.size == 3 && pack.location(dev.charaly.runtime.domain.LocationId(segments[1])) != null
            "scene" -> segments.size == 3 && pack.scene(segments[1]) != null
            "objective" -> segments.size == 3 && pack.objective(segments[1]) != null
            "scenario" -> segments.size == 3 && pack.scenario(segments[1]) != null
            "persona" -> segments.size == 3 && pack.persona(segments[1]) != null
            "event" -> segments.size == 3 && pack.event(segments[1]) != null
            "thread" -> segments.size == 3 && pack.initialStoryThreads.any { it.id.value == segments[1] }
            "faction" -> segments.size == 3 && pack.faction(segments[1]) != null
            "lore" -> segments.size == 3 && pack.loreEntry(segments[1]) != null
            "choice" -> segments.size == 4 &&
                pack.scene(segments[1])?.choice(segments[2]) != null &&
                segments[3] == "label"
            else -> false
        }
    }

    /**
     * Resolves a translator for one pack.
     *
     * @param preferred the language the device is in
     * @param fallbackLanguage the pack's own authored language
     */
    fun forPack(
        pack: StoryPack,
        tables: Map<String, Map<String, String>>,
        preferred: String,
    ): Table = Table(tables, preferred, pack.language)

    /** The resolved lookup, with the chain baked in. */
    class Table(
        private val tables: Map<String, Map<String, String>>,
        private val preferred: String,
        private val packLanguage: String,
    ) {
        private val chain: List<String> = listOf(preferred, packLanguage, "en")
            .map { it.lowercase().substringBefore('-').substringBefore('_') }
            .filter { it.isNotBlank() }
            .distinct()

        /** The translation of [key], or [fallback] - never blank, never a key. */
        fun text(key: String, fallback: String): String {
            chain.forEach { language ->
                val hit = tables[language]?.get(key)
                if (!hit.isNullOrBlank()) return hit
            }
            tables.values.forEach { table ->
                val hit = table[key]
                if (!hit.isNullOrBlank()) return hit
            }
            return fallback
        }

        fun title(pack: StoryPack): String = text(META_TITLE, pack.title)
        fun premise(pack: StoryPack): String = text(META_PREMISE, pack.identity.premise)
        fun description(pack: StoryPack): String = text(META_DESCRIPTION, pack.description)

        fun name(characterId: String, fallback: String): String =
            text("character.$characterId.name", fallback)

        fun descriptionOf(characterId: String, fallback: String): String =
            text("character.$characterId.description", fallback)

        fun sceneName(scene: SceneTemplate): String = text("scene.${scene.id}.name", scene.name)
        fun sceneDescription(scene: SceneTemplate): String = text("scene.${scene.id}.description", scene.description)
        fun choiceLabel(scene: SceneTemplate, choiceId: String, fallback: String): String =
            text("choice.${scene.id}.$choiceId.label", fallback)

        fun objectiveTitle(objective: PackObjective): String =
            text("objective.${objective.id}.title", objective.title)

        fun scenarioTitle(scenario: StartingScenario): String =
            text("scenario.${scenario.id}.title", scenario.title)

        fun personaName(persona: PersonaTemplate): String =
            text("persona.${persona.id}.name", persona.name)

        /** Which languages this pack actually carries. */
        val languages: List<String> get() = tables.keys.sorted()
    }
}