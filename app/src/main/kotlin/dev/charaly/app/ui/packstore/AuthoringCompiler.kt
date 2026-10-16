package dev.charaly.app.ui.packstore

import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.PackSettings
import dev.charaly.runtime.domain.PackWorld
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.SceneChoice
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.pack.PackAuthoring
import dev.charaly.runtime.storypack.PackManifestBuilder
import dev.charaly.runtime.storypack.Severity
import dev.charaly.runtime.storypack.StoryPackBundle
import dev.charaly.runtime.storypack.StoryPackCodec
import dev.charaly.runtime.storypack.StoryPackFormat
import dev.charaly.runtime.storypack.StoryPackValidator
import dev.charaly.runtime.storypack.ValidationIssue
import dev.charaly.runtime.storypack.ValidationReport

/**
 * THE DRAFT-TO-PACK COMPILER.
 *
 * ## What it is for
 *
 * The wizard edits an [AuthoringDraft]; the runtime plays a `StoryPack`; this is the only
 * bridge between them. It is a pure function - same draft in, same pack out - so the
 * authoring flow can be proven on the JVM without a device: compiled, validated, exported
 * to real `.charaly-pack` bytes, decoded again, and even started as a story.
 *
 * ## The auto-linking rule, and why it exists
 *
 * A wizard collects rows independently, but a pack is a graph. If the author had to wire
 * "this scenario's cast is these three characters and its opening scene is that one", the
 * wizard would be an ER modelling tool. So the compiler derives the links from what was
 * authored: a scenario takes the cast that was checked in its card, all objectives join
 * every opening, a scene declares the threads its own branches advance, and every variable
 * a branch writes or an objective watches is declared in the starting world. The author
 * writes *content*; the graph is a consequence.
 *
 * ## The drop rule
 *
 * A row missing its one required field does not compile - see [AuthoringDraft]. What the
 * author gets instead is a Turkish critical finding from the real validators ("Paketin
 * kadrosu boş"), on the review step, exactly where a fix is possible.
 */
object AuthoringCompiler {

    /** What the author types when they mean "an ordinary first edition". */
    const val DEFAULT_VERSION = "1.0.0"

    /** Shown when the title - the one required field - is missing. Turkish, like everything a user sees. */
    const val MISSING_TITLE = "Paket adı gerekli."

    /**
     * Compiles a draft into a real [StoryPack].
     *
     * Throws [IllegalArgumentException] only for a missing title: a pack without a name
     * cannot even be constructed (`StoryPack` refuses one), and no amount of validation
     * downstream can turn "" into content. Everything else degrades into dropped rows and
     * validator findings rather than exceptions, because the review step - not a crash -
     * is where an incomplete draft belongs.
     */
    fun compile(draft: AuthoringDraft): StoryPack {
        require(draft.title.isNotBlank()) { MISSING_TITLE }

        // ---- ids: one slug register per kind of content -------------------
        //
        // Separate registers on purpose. "Ada" the character and "Ada" the location are
        // different things and may share an id; two "Ada" locations may not. Only rows that
        // have their required name enter a register at all - an unnamed row gets no id
        // because it is not content yet (see the drop rule).
        val locationRows = draft.locations.filter { it.name.isNotBlank() }
        val characterRows = draft.characters.filter { it.name.isNotBlank() }
        val personaRows = draft.personas.filter { it.name.isNotBlank() }
        val threadRows = draft.threads.filter { it.title.isNotBlank() }
        val objectiveRows = draft.objectives.filter { it.title.isNotBlank() }
        val scenarioRows = draft.scenarios.filter { it.title.isNotBlank() }
        val loreRows = draft.lore.filter { it.title.isNotBlank() && it.content.isNotBlank() }

        val locationSlugs = slugMap(locationRows.map { it.rowId to it.name })
        val characterSlugs = slugMap(characterRows.map { it.rowId to it.name })
        val personaSlugs = slugMap(personaRows.map { it.rowId to it.name })
        val threadSlugs = slugMap(threadRows.map { it.rowId to it.title })
        val objectiveSlugs = slugMap(objectiveRows.map { it.rowId to it.title })
        val scenarioSlugs = slugMap(scenarioRows.map { it.rowId to it.title })
        val loreSlugs = slugMap(loreRows.map { it.rowId to it.title })
        val sceneSlugs = slugMap(
            draft.scenes
                .filter { it.name.isNotBlank() && it.locationRowId in locationSlugs }
                .map { it.rowId to it.name },
        )

        // ---- world: places -------------------------------------------------
        val locations = locationRows.map { row ->
            val id = locationSlugs.getValue(row.rowId)
            PackAuthoring.location(
                id = id,
                name = row.name.trim(),
                summary = row.description.trim().lineSequence().firstOrNull().orEmpty(),
                description = row.description.trim(),
                interior = row.interior,
                seed = id,
            )
        }

        // ---- cast ----------------------------------------------------------
        val characters = characterRows.map { row ->
            val id = characterSlugs.getValue(row.rowId)
            PackAuthoring.character(
                id = id,
                name = row.name.trim(),
                tagline = row.tagline.trim(),
                description = row.description.trim(),
                personality = row.personality.trim(),
                location = locationSlugs[row.startLocationRowId],
                storyRole = row.role,
                seed = id,
            )
        }

        // ---- roles the player may take ------------------------------------
        val personas = personaRows.map { row ->
            PackAuthoring.persona(
                id = personaSlugs.getValue(row.rowId),
                name = row.name.trim(),
                tagline = row.tagline.trim(),
                description = "",
                rolePrompt = row.rolePrompt.trim(),
            ).copy(
                abilities = splitCsv(row.abilitiesRaw),
                restrictions = splitCsv(row.restrictionsRaw),
            )
        }

        // ---- threads -------------------------------------------------------
        val threads = threadRows.map { row ->
            PackAuthoring.thread(
                id = threadSlugs.getValue(row.rowId),
                title = row.title.trim(),
                description = row.description.trim(),
                status = StoryThreadStatus.ACTIVE,
                stage = 0,
            )
        }

        // ---- lore ----------------------------------------------------------
        val lore = loreRows.map { row ->
            PackAuthoring.lore(
                id = loreSlugs.getValue(row.rowId),
                title = row.title.trim(),
                content = row.content.trim(),
            )
        }

        // ---- relationships -------------------------------------------------
        val relationships = draft.relationships.mapNotNull { row ->
            val source = characterSlugs[row.sourceRowId] ?: return@mapNotNull null
            val target = characterSlugs[row.targetRowId] ?: return@mapNotNull null
            if (source == target) return@mapNotNull null
            PackAuthoring.relationship(
                source = source,
                target = target,
                type = row.resolvedType() ?: dev.charaly.runtime.domain.RelationshipType.UNKNOWN,
                trust = row.trust.coerceIn(0, 100),
                familiarity = row.familiarity.coerceIn(0, 100),
                affinity = row.affinity.coerceIn(0, 100),
            ).copy(tension = row.tension.coerceIn(0, 100))
        }

        // ---- variables every writer and watcher agrees on -------------------
        //
        // A variable that a branch writes or an objective watches is declared in the
        // starting world with a blank value. Declared beats discovered: `VariableEquals`
        // against a variable nobody declared is the classic "objective never fires" bug,
        // and the starting value being blank guarantees the win condition is not true the
        // moment the story opens.
        val watchedKeys = buildSet {
            draft.scenes.forEach { scene ->
                scene.branches.forEach { branch ->
                    if (branch.effect.kind == EffectKind.SET_VARIABLE && branch.effect.variableKey.isNotBlank()) {
                        add(branch.effect.variableKey.trim())
                    }
                }
            }
            draft.objectives.forEach { objective ->
                if (objective.completionKey.isNotBlank()) add(objective.completionKey.trim())
                if (objective.failureKey.isNotBlank()) add(objective.failureKey.trim())
            }
            draft.event?.let { event ->
                if (event.whenKey.isNotBlank()) add(event.whenKey.trim())
                if (event.effect.kind == EffectKind.SET_VARIABLE && event.effect.variableKey.isNotBlank()) {
                    add(event.effect.variableKey.trim())
                }
            }
        }
        val variables = watchedKeys.sorted().map { key ->
            PackAuthoring.text(key = key, value = "", description = "Sihirbazın dalları ve görevleri bu değişkene bakıyor.")
        }

        // ---- objectives ----------------------------------------------------
        //
        // Compiled before scenarios because every opening names every objective.
        val objectives = objectiveRows.map { row ->
            PackObjective(
                id = objectiveSlugs.getValue(row.rowId),
                title = row.title.trim(),
                description = row.description.trim(),
                completionConditions = equalsCondition(row.completionKey, row.completionValue),
                failureConditions = equalsCondition(row.failureKey, row.failureValue),
                autoActivate = true,
            )
        }

        // ---- scenes and their branches -------------------------------------
        val scenes = draft.scenes.filter { it.name.isNotBlank() && it.locationRowId in locationSlugs }.map { row ->
            val sceneId = sceneSlugs.getValue(row.rowId)
            val choices = row.branches
                .filter { it.label.isNotBlank() }
                .mapIndexed { index, branch ->
                    SceneChoice(
                        id = "$sceneId-secim-${index + 1}",
                        label = branch.label.trim(),
                        effects = compileEffect(branch.effect, characterSlugs, threadSlugs),
                        once = true,
                    )
                }
            // A scene declares the threads its own branches touch. Left implicit, a thread
            // only a branch advances reads to the validator as "nothing touches this",
            // which trains authors to ignore reachability warnings.
            val touchedThreads = choices
                .flatMap { choice -> choice.effects.filterIsInstance<EventEffect.AdvanceThread>() }
                .map { it.threadId.value }
                .distinct()
            SceneTemplate(
                id = sceneId,
                name = row.name.trim(),
                description = row.description.trim(),
                atmosphere = row.atmosphere.trim(),
                locationId = dev.charaly.runtime.domain.LocationId(locationSlugs.getValue(row.locationRowId)),
                participantCharacterIds = row.participants.mapNotNull { characterSlugs[it] }
                    .map { dev.charaly.runtime.domain.CharacterId(it) },
                choices = choices,
                threadIds = touchedThreads.map { ThreadId(it) },
            )
        }

        // ---- openings ------------------------------------------------------
        val scenarios = draft.scenarios
            .filter { it.title.isNotBlank() && it.startLocationRowId in locationSlugs }
            .map { row ->
                val startSlug = locationSlugs.getValue(row.startLocationRowId)
                // The opening scene is a scene where the story starts - preferring one at
                // the opening location, because `StoryInstanceFactory` only opens a scene
                // whose participants are genuinely there. Falling back to the first scene
                // keeps a one-location pack working with no extra authoring.
                val openingScene = scenes.firstOrNull { it.locationId.value == startSlug } ?: scenes.firstOrNull()
                PackAuthoring.scenario(
                    id = scenarioSlugs.getValue(row.rowId),
                    title = row.title.trim(),
                    tagline = row.tagline.trim(),
                    description = "",
                    startTime = StoryTime.START,
                    startLocation = startSlug,
                    focus = row.focusCharacterRowId.takeIf { it in characterSlugs }?.let { characterSlugs.getValue(it) },
                    cast = row.castRowIds.mapNotNull { characterSlugs[it] },
                ).copy(
                    objectiveIds = objectives.map { it.id },
                    initialSceneId = openingScene?.id.orEmpty(),
                )
            }

        // ---- the one optional conditional event ----------------------------
        val eventRow = draft.event
        val events = if (eventRow != null &&
            eventRow.title.isNotBlank() &&
            eventRow.whenKey.isNotBlank() &&
            eventRow.whenValue.isNotBlank()
        ) {
            val effects = compileEffect(eventRow.effect, characterSlugs, threadSlugs)
            // An event whose effect did not compile is dropped whole: a conditional event
            // that fires and does nothing is worse than no event, and a *triggerless* one
            // would fire unconditionally - the one thing a conditional event must not do.
            if (effects.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    PackAuthoring.event(
                        id = stableSlug(eventRow.title),
                        title = eventRow.title.trim(),
                        trigger = EventTrigger.WhenConditionMet(),
                        conditions = equalsCondition(eventRow.whenKey, eventRow.whenValue),
                        effects = effects,
                    ),
                )
            }
        } else {
            emptyList()
        }

        // ---- assemble, cross-checked, then extended ------------------------
        //
        // `PackAuthoring.pack` is the runtime's own assembler and refuses dangling
        // references loudly. Everything it cannot know about - scenes, objectives, the
        // setting frame, the semantic version - is attached afterwards, and every id those
        // carry was reserved above, so no reference can dangle.
        val packSlug = stableSlug(draft.title.trim())
        val identity = PackAuthoring.identity(
            tagline = draft.premise.trim(),
            genres = splitCsv(draft.tagsRaw),
            coverSeed = draft.coverSeed.trim().ifBlank { packSlug },
            mood = "",
            primary = dev.charaly.runtime.domain.CharalyAccent.NEUTRAL.primaryHex,
            secondary = dev.charaly.runtime.domain.CharalyAccent.NEUTRAL.secondaryHex,
            accent = dev.charaly.runtime.domain.CharalyAccent.NEUTRAL.accentHex,
            premise = draft.premise.trim(),
            demo = false,
        )
        val assembled = PackAuthoring.pack(
            id = "pack-$packSlug",
            title = draft.title.trim(),
            description = draft.description.trim().ifBlank { draft.premise.trim() },
            author = draft.author.trim(),
            identity = identity,
            characters = characters,
            locations = locations,
            lore = lore,
            events = events,
            scenarios = scenarios,
            personas = personas,
            startTime = StoryTime.START,
            variables = variables,
            startLocations = draft.characters
                .filter { it.name.isNotBlank() && it.startLocationRowId in locationSlugs }
                .associate { characterSlugs.getValue(it.rowId) to locationSlugs.getValue(it.startLocationRowId) },
            relationships = relationships,
            threads = threads,
        )
        return assembled.copy(
            packVersion = draft.version.trim().ifBlank { DEFAULT_VERSION },
            publisher = draft.publisher.trim(),
            language = draft.language.trim().ifBlank { "tr" },
            entryPoint = scenarios.firstOrNull()?.id.orEmpty(),
            world = PackWorld(
                universe = draft.universe.trim(),
                era = draft.era.trim(),
                geography = draft.geography.trim(),
                rules = draft.rules.map { it.text.trim() }.filter { it.isNotEmpty() },
                worldFacts = draft.worldFacts.map { it.text.trim() }.filter { it.isNotEmpty() },
            ),
            scenes = scenes,
            objectives = objectives,
            settings = PackSettings(),
        )
    }

    /**
     * Runs the same validators the installer runs, as the review step's report.
     *
     * ## Why the manifest check joins the five content checks
     *
     * Step 5 must agree with the archive inspector that will judge the exported bytes, or
     * "Kuruluma hazır" becomes a promise the importer immediately breaks. The manifest is
     * where the *version* lives, so this is also the only place a malformed version ("abc")
     * surfaces before an export. It is a real validator over real data - the exact manifest
     * the export will carry - not a reimplemented rule.
     */
    fun review(pack: StoryPack): ValidationReport {
        val bundle = StoryPackBundle.of(pack)
        val issues = StoryPackValidator.validateBundle(bundle) +
            StoryPackValidator.validateContent(pack, bundle) +
            StoryPackValidator.validateReferences(pack) +
            StoryPackValidator.validateReachability(pack) +
            StoryPackValidator.validateBranching(pack) +
            StoryPackValidator.validateManifest(manifestOf(pack), StoryPackFormat.ENGINE_VERSION)
        return ValidationReport(issues = issues, census = bundle.census())
    }

    /** The manifest the export will carry, rebuilt from the pack exactly as the exporter does. */
    private fun manifestOf(pack: StoryPack): dev.charaly.runtime.storypack.PackManifest {
        val files = StoryPackCodec.encode(pack).mapValues { (_, text) -> text.toByteArray(Charsets.UTF_8) }
        return PackManifestBuilder.build(pack = pack, files = files)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** `rowId -> slug`, unique per kind, in author order. */
    private fun slugMap(entries: List<Pair<String, String>>): Map<String, String> {
        val register = SlugRegister()
        return entries.associate { (rowId, name) -> rowId to register.reserve(name) }
    }

    /** `variable key equals value` as a single condition list, or empty when half-written. */
    private fun equalsCondition(key: String, value: String): List<EventCondition> =
        if (key.isNotBlank() && value.isNotBlank()) {
            listOf(EventCondition.VariableEquals(key.trim(), value.trim()))
        } else {
            emptyList()
        }

    /**
     * One authored effect row to zero-or-one typed effect.
     *
     * Zero, not a placeholder: a half-written effect (a trust shift with nobody selected)
     * is dropped so the branch it belongs to can be caught by the branching validator as
     * "this choice changes nothing" - a much louder and more accurate finding than a
     * silently broken reference.
     */
    private fun compileEffect(
        row: EffectDraft,
        characterSlugs: Map<String, String>,
        threadSlugs: Map<String, String>,
    ): List<EventEffect> {
        return when (row.kind) {
            EffectKind.SET_VARIABLE ->
                if (row.variableKey.isNotBlank()) {
                    listOf(EventEffect.SetVariable(row.variableKey.trim(), row.variableValue.trim()))
                } else {
                    emptyList()
                }

            EffectKind.TRUST_SHIFT -> {
                val source = characterSlugs[row.sourceRowId] ?: return emptyList()
                val target = characterSlugs[row.targetRowId] ?: return emptyList()
                if (source == target) return emptyList()
                listOf(
                    EventEffect.ChangeRelationship(
                        sourceId = dev.charaly.runtime.domain.CharacterId(source),
                        targetId = dev.charaly.runtime.domain.CharacterId(target),
                        delta = RelationshipDelta(trust = row.trustDelta.coerceIn(-100, 100)),
                        reason = "",
                    ),
                )
            }

            EffectKind.ADVANCE_THREAD -> {
                val thread = threadSlugs[row.threadRowId] ?: return emptyList()
                listOf(EventEffect.AdvanceThread(ThreadId(thread), stage = 1))
            }
        }
    }
}