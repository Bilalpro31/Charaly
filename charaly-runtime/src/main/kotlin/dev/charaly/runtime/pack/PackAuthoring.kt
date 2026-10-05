package dev.charaly.runtime.pack

import dev.charaly.runtime.domain.CharacterActivity
import dev.charaly.runtime.domain.CharacterDefinition
import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.AssetSource
import dev.charaly.runtime.domain.CharacterRole
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.EventTrigger
import dev.charaly.runtime.domain.FactId
import dev.charaly.runtime.domain.Faction
import dev.charaly.runtime.domain.Location
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.MemoryPolicy
import dev.charaly.runtime.domain.PackArtwork
import dev.charaly.runtime.domain.PackEventDefinition
import dev.charaly.runtime.domain.PackIdentity
import dev.charaly.runtime.domain.PackTheme
import dev.charaly.runtime.domain.PersonaTemplate
import dev.charaly.runtime.domain.Relationship
import dev.charaly.runtime.domain.RelationshipDelta
import dev.charaly.runtime.domain.RelationshipType
import dev.charaly.runtime.domain.Routine
import dev.charaly.runtime.domain.VisualAsset
import dev.charaly.runtime.domain.VisualAssetType
import dev.charaly.runtime.domain.RoutineEntry
import dev.charaly.runtime.domain.SpeakingStyle
import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.StoryPackId
import dev.charaly.runtime.domain.StoryThread
import dev.charaly.runtime.domain.StoryThreadStatus
import dev.charaly.runtime.domain.StoryTime
import dev.charaly.runtime.domain.ThreadId
import dev.charaly.runtime.domain.WorldLoreEntry
import dev.charaly.runtime.domain.WorldVariable
import dev.charaly.runtime.domain.WorldVariableType
import dev.charaly.runtime.domain.knowledge.Fact
import dev.charaly.runtime.domain.memory.Memory
import dev.charaly.runtime.domain.memory.MemorySource

/**
 * Authoring helpers for StoryPacks.
 *
 * These exist so a pack reads like a *story bible* instead of a wall of
 * constructor calls, while still producing exactly the same domain objects the
 * runtime consumes. Nothing here bypasses the domain model: every helper returns a
 * plain [StoryPack] built from [CharacterDefinition], [Location], [PackEventDefinition]
 * and friends.
 *
 * Everything here is pure Kotlin, so the demo worlds are covered by ordinary JVM
 * tests: real data objects, not UI fixtures.
 */
object PackAuthoring {

    // ---- identity --------------------------------------------------------

    fun identity(
        tagline: String,
        genres: List<String>,
        coverSeed: String,
        mood: String,
        primary: String,
        secondary: String,
        accent: String,
        surface: String = "#131218",
        ink: String = "#F5F2FA",
        era: String = "",
        tone: String = "",
        notice: String = "",
        featured: Boolean = false,
        demo: Boolean = true,
        contentNotes: List<String> = emptyList(),
        glyph: String = "",
    ): PackIdentity = PackIdentity(
        tagline = tagline,
        genres = genres,
        contentNotes = contentNotes,
        era = era,
        tone = tone,
        theme = PackTheme(
            primaryHex = primary,
            secondaryHex = secondary,
            accentHex = accent,
            inkHex = ink,
            surfaceHex = surface,
            mood = mood,
        ),
        cover = PackArtwork.generated(seed = coverSeed, glyph = glyph, caption = tagline),
        fandomNotice = notice,
        isFeatured = featured,
        isDemo = demo,
    )

    // ---- characters ------------------------------------------------------

    fun character(
        id: String,
        name: String,
        tagline: String,
        description: String,
        personality: String,
        background: String = "",
        goals: List<String> = emptyList(),
        fears: List<String> = emptyList(),
        role: String = "",
        tone: String = "",
        vocabulary: String = "",
        quirks: List<String> = emptyList(),
        avoids: List<String> = emptyList(),
        greeting: String = "",
        examples: List<String> = emptyList(),
        boundaries: List<String> = emptyList(),
        instructions: String = "",
        location: String? = null,
        activity: CharacterActivity? = null,
        faction: String = "",
        accent: String = "",
        seed: String = id,
        memoryImportance: Int = 3,
        storyRole: CharacterRole = CharacterRole.PROTAGONIST,
        routine: Routine = Routine(),
        assets: List<VisualAsset> = emptyList(),
    ): CharacterDefinition = CharacterDefinition(
        id = CharacterId(id),
        name = name,
        tagline = tagline,
        shortDescription = description,
        description = description,
        personality = personality,
        background = background,
        goals = goals,
        fears = fears,
        identityRole = role,
        speakingStyle = SpeakingStyle(
            tone = tone,
            vocabulary = vocabulary,
            quirks = quirks,
            avoids = avoids,
            exampleOpeners = listOf(greeting).filter { it.isNotBlank() },
        ),
        exampleDialogue = examples,
        greeting = greeting,
        knowledgeBoundaries = boundaries,
        systemInstructions = instructions,
        startingLocationId = location?.let(::LocationId),
        startingActivity = activity,
        routine = routine,
        storyRole = storyRole,
        memoryPolicy = MemoryPolicy(defaultImportance = memoryImportance),
        factionId = faction,
        artwork = PackArtwork.generated(seed = seed),
        visualAssets = assets,
        accentHex = accent,
    )

    fun location(
        id: String,
        name: String,
        summary: String,
        description: String,
        connections: List<String> = emptyList(),
        rules: List<String> = emptyList(),
        lore: String = "",
        occupants: List<String> = emptyList(),
        interior: Boolean = true,
        accent: String = "",
        seed: String = id,
        tags: List<String> = emptyList(),
        assets: List<VisualAsset> = emptyList(),
    ): Location = Location(
        id = LocationId(id),
        name = name,
        summaryLine = summary,
        description = description,
        tags = tags,
        isInterior = interior,
        connections = connections.map(::LocationId),
        rules = rules,
        lore = lore,
        startingOccupants = occupants.map(::CharacterId),
        artwork = PackArtwork.generated(seed = seed),
        visualAssets = assets,
        accentHex = accent,
    )

    // ---- visual assets ----------------------------------------------------

    /**
     * A generated, original illustration for a character.
     *
     * This is the default for demonstration packs and the reason they can be
     * distributed at all: nothing is copied, nothing is downloaded, and the picture is
     * drawn locally from [seed] so it is identical on every device forever.
     *
     * Note what is deliberately *not* here: no remote url. A pack that needs an image it
     * cannot produce itself states that fact elsewhere rather than shipping a link that
     * will not resolve in the offline core.
     */
    fun portrait(
        id: String,
        seed: String,
        caption: String = "",
    ): VisualAsset = VisualAsset(
        assetId = id,
        type = VisualAssetType.CHARACTER_PORTRAIT,
        thumbnailRef = "",
        source = AssetSource.GENERATED_ORIGINAL,
        generatedSeed = seed,
        caption = caption,
    )

    /** The same character, at carousel / avatar size. */
    fun thumbnail(id: String, seed: String, caption: String = ""): VisualAsset = VisualAsset(
        assetId = id,
        type = VisualAssetType.CHARACTER_THUMBNAIL,
        source = AssetSource.GENERATED_ORIGINAL,
        generatedSeed = seed,
        caption = caption,
    )

    /** A wide illustration for a place. */
    fun placeImage(id: String, seed: String, caption: String = ""): VisualAsset = VisualAsset(
        assetId = id,
        type = VisualAssetType.LOCATION_IMAGE,
        source = AssetSource.GENERATED_ORIGINAL,
        generatedSeed = seed,
        caption = caption,
    )

    /** A place, cropped small for a carousel tile. */
    fun placeThumbnail(id: String, seed: String, caption: String = ""): VisualAsset = VisualAsset(
        assetId = id,
        type = VisualAssetType.LOCATION_THUMBNAIL,
        source = AssetSource.GENERATED_ORIGINAL,
        generatedSeed = seed,
        caption = caption,
    )

    /** The wide hero at the top of a pack's detail screen. */
    fun packBanner(id: String, seed: String, caption: String = ""): VisualAsset = VisualAsset(
        assetId = id,
        type = VisualAssetType.PACK_BANNER,
        source = AssetSource.GENERATED_ORIGINAL,
        generatedSeed = seed,
        caption = caption,
    )

    /** A pack's library card image. */
    fun packCover(id: String, seed: String, caption: String = ""): VisualAsset = VisualAsset(
        assetId = id,
        type = VisualAssetType.PACK_COVER,
        source = AssetSource.GENERATED_ORIGINAL,
        generatedSeed = seed,
        caption = caption,
    )

    /** Artwork for an authored event or a story opening. */
    fun eventImage(id: String, seed: String, caption: String = ""): VisualAsset = VisualAsset(
        assetId = id,
        type = VisualAssetType.EVENT_IMAGE,
        source = AssetSource.GENERATED_ORIGINAL,
        generatedSeed = seed,
        caption = caption,
    )

// ---- routines -------------------------------------------------------

    /**
     * Reads as a day, which is how a story bible writes it.
     *
     * ```
     * routine(
     *     at("08:00", shop, WORKING, "opening up"),
     *     at("09:00", shop, WORKING, "serving customers"),
     *     at("18:00", shop, RESTING, "closing up"),
     *     at("19:00", home, RESTING, "asleep"),
     *     home = "andre-home",
     *     summary = "Runs the shop by day, home by night.",
     * )
     * ```
     *
     * The vararg comes first on purpose so the day can be written in the order the
     * author thinks about it, with the two descriptive fields trailing as named
     * arguments. Entries are ordered by the engine, so authoring order is free.
     */
    fun routine(
        vararg entries: RoutineEntry,
        home: String? = null,
        summary: String = "",
    ): Routine = Routine(
        homeLocationId = home?.let(::LocationId),
        entries = entries.toList(),
        summary = summary,
    )

    /** `09:00 -> the shop, serving customers`. */
    fun at(
        time: String,
        location: String,
        activity: CharacterActivity = CharacterActivity.IDLE,
        label: String = "",
    ): RoutineEntry {
        val (hour, minute) = parseClock(time)
        return RoutineEntry.at(
            hour = hour,
            minute = minute,
            locationId = LocationId(location),
            activity = activity,
            label = label,
        )
    }

    /** `08:00 -> open shop`. */
    infix fun String.to(location: String): RoutineEntry = at(this, location)

    /**
     * Parses "HH:MM" into minutes-of-day.
     *
     * Authoring-time only: a malformed clock is a bug in the pack, so it fails loudly
     * here rather than silently producing a character who is never anywhere.
     */
    fun parseClock(time: String): Pair<Int, Int> {
        val trimmed = time.trim()
        val parts = trimmed.split(":")
        require(parts.size == 2) { "A routine time must look like \"HH:MM\", was \"$time\"" }
        val hour = parts[0].trim().toIntOrNull()
            ?: error("A routine time must start with a number, was \"$time\"")
        val minute = parts[1].trim().toIntOrNull()
            ?: error("A routine time must have numeric minutes, was \"$time\"")
        require(hour in 0..23) { "Hour must be 0..23, was $hour (\"$time\")" }
        require(minute in 0..59) { "Minute must be 0..59, was $minute (\"$time\")" }
        return hour to minute
    }

    // ---- facts / knowledge ----------------------------------------------

    fun fact(
        id: String,
        subject: String,
        predicate: String,
        description: String,
        location: String? = null,
        involves: List<String> = emptyList(),
        secret: Boolean = false,
    ): Fact = Fact(
        id = FactId(id),
        subject = subject,
        predicate = predicate,
        description = description,
        locationId = location?.let(::LocationId),
        involvedCharacters = involves.map(::CharacterId),
        secret = secret,
    )

    fun knows(characterId: String, vararg factIds: String): Pair<String, List<String>> =
        characterId to factIds.toList()

    // ---- relationships ---------------------------------------------------

    fun relationship(
        source: String,
        target: String,
        type: RelationshipType,
        trust: Int = 50,
        familiarity: Int = 40,
        affinity: Int = 50,
        note: String = "",
    ): Relationship = Relationship(
        sourceId = CharacterId(source),
        targetId = CharacterId(target),
        relationshipType = type,
        trust = trust,
        familiarity = familiarity,
        affinity = affinity,
        note = note,
    )

    // ---- threads / lore / factions ---------------------------------------

    fun thread(
        id: String,
        title: String,
        description: String,
        status: StoryThreadStatus,
        stage: Int,
        characters: List<String> = emptyList(),
        locations: List<String> = emptyList(),
    ): StoryThread = StoryThread(
        id = ThreadId(id),
        title = title,
        description = description,
        status = status,
        stage = stage,
        involvedCharacterIds = characters.map(::CharacterId),
        relevantLocationIds = locations.map(::LocationId),
    )

    fun lore(
        id: String,
        title: String,
        content: String,
        importance: Int = 2,
        secret: Boolean = false,
        characters: List<String> = emptyList(),
        locations: List<String> = emptyList(),
    ): WorldLoreEntry = WorldLoreEntry(
        id = id,
        title = title,
        content = content,
        importance = importance,
        secret = secret,
        relatedCharacterIds = characters.map(::CharacterId),
        relatedLocationIds = locations.map(::LocationId),
    )

    fun faction(
        id: String,
        name: String,
        motto: String,
        description: String,
        members: List<String> = emptyList(),
        seat: String? = null,
        color: String = "#8B7BF0",
    ): Faction = Faction(
        id = id,
        name = name,
        motto = motto,
        description = description,
        memberCharacterIds = members.map(::CharacterId),
        seatLocationId = seat?.let(::LocationId),
        colorHex = color,
    )

    // ---- variables -------------------------------------------------------

    fun flag(key: String, value: String, description: String, type: WorldVariableType = WorldVariableType.BOOLEAN): WorldVariable =
        WorldVariable(key = key, type = type, value = value, description = description)

    fun count(key: String, value: Int, description: String): WorldVariable =
        WorldVariable(key = key, type = WorldVariableType.NUMBER, value = value.toString(), description = description)

    fun text(key: String, value: String, description: String): WorldVariable =
        WorldVariable(key = key, type = WorldVariableType.TEXT, value = value, description = description)

    // ---- events ----------------------------------------------------------

    fun event(
        id: String,
        title: String,
        trigger: EventTrigger,
        description: String = "",
        seed: String = "",
        conditions: List<EventCondition> = emptyList(),
        effects: List<EventEffect> = emptyList(),
        participants: List<String> = emptyList(),
        location: String? = null,
        cooldownMinutes: Long = 0L,
        repeatable: Boolean = false,
        threads: List<ThreadChangeRef> = emptyList(),
        tags: List<String> = emptyList(),
        seedArt: String = id,
    ): PackEventDefinition = PackEventDefinition(
        id = id,
        title = title,
        description = description,
        narrativeSeed = seed,
        trigger = trigger,
        conditions = conditions,
        effects = effects,
        participants = participants.map(::CharacterId),
        locationId = location?.let(::LocationId),
        cooldownMinutes = cooldownMinutes,
        repeatable = repeatable,
        threadChanges = threads.map { it.toChange() },
        tags = tags,
        artwork = PackArtwork.generated(seed = seedArt),
    )

    /** Small builder so a thread nudge reads well next to its event. */
    data class ThreadChangeRef(
        val threadId: String,
        val stage: Int? = null,
        val status: StoryThreadStatus? = null,
        val note: String = "",
    ) {
        fun toChange() = dev.charaly.runtime.domain.ThreadChange(
            threadId = ThreadId(threadId),
            stage = stage,
            status = status,
            note = note,
        )
    }

    fun threadRef(
        threadId: String,
        stage: Int? = null,
        status: StoryThreadStatus? = null,
        note: String = "",
    ) = ThreadChangeRef(threadId, stage, status, note)

    // ---- effects / conditions shorthands ---------------------------------

    fun move(character: String, to: String, activity: CharacterActivity = CharacterActivity.IDLE) =
        EventEffect.MoveCharacter(CharacterId(character), LocationId(to), activity)

    fun act(character: String, activity: CharacterActivity, mood: String = "") =
        EventEffect.ChangeActivity(CharacterId(character), activity, mood)

    fun relate(source: String, target: String, trust: Int = 0, affinity: Int = 0, familiarity: Int = 0, reason: String = "", type: RelationshipType? = null) =
        EventEffect.ChangeRelationship(
            sourceId = CharacterId(source),
            targetId = CharacterId(target),
            delta = RelationshipDelta(trust = trust, familiarity = familiarity, affinity = affinity),
            relationshipType = type,
            reason = reason,
        )

    fun setVar(key: String, value: String) = EventEffect.SetVariable(key, value)

    fun advance(threadId: String, stage: Int = 1, status: StoryThreadStatus? = null, note: String = "") =
        EventEffect.AdvanceThread(ThreadId(threadId), stage, status, note)

    fun grant(character: String, factId: String, via: String = "") =
        EventEffect.GrantKnowledge(CharacterId(character), FactId(factId), via)

    fun forget(character: String, factId: String, reason: String = "") =
        EventEffect.RevokeKnowledge(CharacterId(character), FactId(factId), reason)

    fun remember(character: String, content: String, importance: Int = 3, about: List<String> = emptyList(), at: String? = null) =
        EventEffect.CreateMemory(
            characterId = CharacterId(character),
            content = content,
            importance = importance,
            relatedCharacterIds = about.map(::CharacterId),
            relatedLocationId = at?.let(::LocationId),
        )

    fun openScene(location: String, participants: List<String>, objective: String = "", threads: List<String> = emptyList()) =
        EventEffect.StartScene(
            locationId = LocationId(location),
            participants = participants.map(::CharacterId),
            objective = objective,
            threadIds = threads.map(::ThreadId),
        )

    fun closeScene(sceneId: String, reason: String = "") = EventEffect.EndScene(dev.charaly.runtime.domain.SceneId(sceneId), reason)

    fun tick(minutes: Long) = EventEffect.AdvanceTime(minutes)

    fun later(eventId: String, minutes: Long) = EventEffect.ScheduleEvent(eventId, minutes)

    fun whenVar(key: String, equals: String) = EventCondition.VariableEquals(key, equals)
    fun whenVarNot(key: String, equals: String) = EventCondition.VariableNotEquals(key, equals)
    fun whenAt(character: String, location: String) = EventCondition.CharacterAtLocation(CharacterId(character), LocationId(location))
    fun whenNotAt(character: String, location: String) = EventCondition.CharacterNotAtLocation(CharacterId(character), LocationId(location))
    fun whenThreadAtLeast(threadId: String, stage: Int) = EventCondition.ThreadStageAtLeast(ThreadId(threadId), stage)
    fun whenThreadStatus(threadId: String, status: StoryThreadStatus) = EventCondition.ThreadStatusIs(ThreadId(threadId), status)
    fun whenTrust(source: String, target: String, atLeast: Int) = EventCondition.RelationshipTrustAtLeast(CharacterId(source), CharacterId(target), atLeast)
    fun whenClock(time: StoryTime) = EventCondition.StoryTimeReached(time)
    fun whenKnows(character: String, factId: String) = EventCondition.CharacterKnowsFact(CharacterId(character), FactId(factId))
    fun whenFactExists(factId: String) = EventCondition.FactExists(FactId(factId))
    fun whenSceneOpen(location: String) = EventCondition.SceneIsOpen(LocationId(location))

    // ---- scenarios / personas --------------------------------------------

    fun scenario(
        id: String,
        title: String,
        tagline: String,
        description: String,
        startTime: StoryTime,
        startLocation: String,
        focus: String? = null,
        cast: List<String> = emptyList(),
        activities: Map<String, CharacterActivity> = emptyMap(),
        threadStages: Map<String, Int> = emptyMap(),
        variables: List<WorldVariable> = emptyList(),
        events: List<String> = emptyList(),
        seed: String = "",
        artSeed: String = id,
    ): StartingScenario = StartingScenario(
        id = id,
        title = title,
        tagline = tagline,
        description = description,
        startTime = startTime,
        startLocationId = LocationId(startLocation),
        focusCharacterId = focus?.let(::CharacterId),
        castCharacterIds = cast.map(::CharacterId),
        startActivities = activities.mapKeys { CharacterId(it.key) },
        threadStages = threadStages,
        startVariables = variables,
        eventIds = events,
        narrativeSeed = seed,
        artwork = PackArtwork.generated(seed = artSeed),
    )

    fun persona(
        id: String,
        name: String,
        tagline: String,
        description: String,
        rolePrompt: String,
        location: String? = null,
        suggests: List<String> = emptyList(),
    ): PersonaTemplate = PersonaTemplate(
        id = id,
        name = name,
        tagline = tagline,
        description = description,
        rolePrompt = rolePrompt,
        startingLocationId = location?.let(::LocationId),
        suggestedCharacterIds = suggests.map(::CharacterId),
    )

    fun memory(id: String, character: String, content: String, importance: Int = 3, at: String? = null, involves: List<String> = emptyList()) =
        Memory(
            id = dev.charaly.runtime.domain.MemoryId(id),
            characterId = CharacterId(character),
            content = content,
            importance = importance,
            createdAt = StoryTime.START,
            source = MemorySource.AUTHORED,
            relatedCharacterIds = involves.map(::CharacterId),
            relatedLocationId = at?.let(::LocationId),
        )

    // ---- pack assembly ---------------------------------------------------

    /**
     * Assembles a pack and validates cross references.
     *
     * The validation is the point: a pack that references a character or location
     * that does not exist would otherwise fail much later, at event application
     * time, as a silent rejection. Catching it here makes pack bugs loud.
     */
    fun pack(
        id: String,
        title: String,
        description: String,
        author: String = "Charaly",
        identity: PackIdentity,
        characters: List<CharacterDefinition>,
        locations: List<Location>,
        factions: List<Faction> = emptyList(),
        lore: List<WorldLoreEntry> = emptyList(),
        events: List<PackEventDefinition> = emptyList(),
        scenarios: List<StartingScenario> = emptyList(),
        personas: List<PersonaTemplate> = emptyList(),
        startTime: StoryTime,
        variables: List<WorldVariable> = emptyList(),
        startLocations: Map<String, String> = emptyMap(),
        startActivities: Map<String, CharacterActivity> = emptyMap(),
        startGoals: Map<String, List<String>> = emptyMap(),
        relationships: List<Relationship> = emptyList(),
        facts: List<Fact> = emptyList(),
        characterKnowledge: List<Pair<String, List<String>>> = emptyList(),
        threads: List<StoryThread> = emptyList(),
        authoredMemories: List<Memory> = emptyList(),
        defaultModelProfileId: String = "",
        visualAssets: List<VisualAsset> = emptyList(),
    ): StoryPack {
        val characterIds = characters.map { it.id }.map { it.value }.toSet()
        val locationIds = locations.map { it.id }.map { it.value }.toSet()
        val threadIds = threads.map { it.id.value }.toSet()
        val factIds = facts.map { it.id.value }.toSet()
        val eventIds = events.map { it.id }.toSet()
        val factionIds = factions.map { it.id }.toSet()

        fun requireCharacter(character: String, context: String) =
            require(character in characterIds) {
                "[$id] references unknown character '$character' in $context"
            }
        fun requireLocation(location: String?, context: String) {
            if (location != null) {
                require(location in locationIds) {
                    "[$id] references unknown location '$location' in $context"
                }
            }
        }
        fun requireThread(thread: String, context: String) {
            require(thread in threadIds) { "[$id] references unknown thread '$thread' in $context" }
        }

        characters.forEach { character ->
            requireLocation(character.startingLocationId?.value, "character ${character.id.value}")
            if (character.factionId.isNotBlank()) {
                require(character.factionId in factionIds) {
                    "[$id] character ${character.id.value} references unknown faction '${character.factionId}'"
                }
            }
        }
        locations.forEach { location ->
            location.connections.forEach { requireLocation(it.value, "location ${location.id.value} connections") }
            location.startingOccupants.forEach { occupant ->
                require(occupant.value in characterIds) {
                    "[$id] location ${location.id.value} lists unknown character '${occupant.value}'"
                }
            }
        }
        startLocations.forEach { (character, location) ->
            require(character in characterIds) { "[$id] start location for unknown character '$character'" }
            require(location in locationIds) { "[$id] start location references unknown place '$location'" }
        }
        startActivities.keys.forEach { require(it in characterIds) { "[$id] activity for unknown character '$it'" } }
        startGoals.keys.forEach { require(it in characterIds) { "[$id] goals for unknown character '$it'" } }
        relationships.forEach {
            requireCharacter(it.sourceId.value, "relationship")
            requireCharacter(it.targetId.value, "relationship")
        }
        facts.forEach { fact ->
            requireLocation(fact.locationId?.value, "fact ${fact.id.value}")
            fact.involvedCharacters.forEach { requireCharacter(it.value, "fact ${fact.id.value}") }
        }
        characterKnowledge.forEach { (character, granted) ->
            requireCharacter(character, "character knowledge")
            granted.forEach { factId ->
                require(factId in factIds) { "[$id] grants unknown fact '$factId' to $character" }
            }
        }
        threads.forEach { thread ->
            thread.involvedCharacterIds.forEach { requireCharacter(it.value, "thread ${thread.id.value}") }
            thread.relevantLocationIds.forEach { requireLocation(it.value, "thread ${thread.id.value}") }
        }
        authoredMemories.forEach { memory ->
            requireCharacter(memory.characterId.value, "authored memory ${memory.id.value}")
            requireLocation(memory.relatedLocationId?.value, "authored memory ${memory.id.value}")
            memory.relatedCharacterIds.forEach { requireCharacter(it.value, "authored memory ${memory.id.value}") }
        }
        events.forEach { event ->
            event.participants.forEach { requireCharacter(it.value, "event ${event.id}") }
            requireLocation(event.locationId?.value, "event ${event.id}")
            event.effects.forEach { effect ->
                when (effect) {
                    is EventEffect.MoveCharacter -> {
                        requireCharacter(effect.characterId.value, "event ${event.id} move")
                        requireLocation(effect.toLocationId.value, "event ${event.id} move")
                    }
                    is EventEffect.ChangeActivity -> requireCharacter(effect.characterId.value, "event ${event.id} activity")
                    is EventEffect.ChangeRelationship -> {
                        requireCharacter(effect.sourceId.value, "event ${event.id} relationship")
                        requireCharacter(effect.targetId.value, "event ${event.id} relationship")
                    }
                    is EventEffect.GrantKnowledge -> {
                        requireCharacter(effect.characterId.value, "event ${event.id} knowledge")
                        require(effect.factId.value in factIds) {
                            "[$id] event ${event.id} grants unknown fact '${effect.factId.value}'"
                        }
                    }
                    is EventEffect.RevokeKnowledge -> {
                        requireCharacter(effect.characterId.value, "event ${event.id} knowledge")
                        require(effect.factId.value in factIds) {
                            "[$id] event ${event.id} revokes unknown fact '${effect.factId.value}'"
                        }
                    }
                    is EventEffect.CreateMemory -> {
                        requireCharacter(effect.characterId.value, "event ${event.id} memory")
                        effect.relatedCharacterIds.forEach { requireCharacter(it.value, "event ${event.id} memory") }
                        requireLocation(effect.relatedLocationId?.value, "event ${event.id} memory")
                    }
                    is EventEffect.StartScene -> {
                        requireLocation(effect.locationId.value, "event ${event.id} scene")
                        effect.participants.forEach { requireCharacter(it.value, "event ${event.id} scene") }
                        effect.threadIds.forEach { require(it.value in threadIds) {
                            "[$id] event ${event.id} references unknown thread '${it.value}'"
                        } }
                    }
                    is EventEffect.EndScene -> Unit
                    is EventEffect.AdvanceThread -> require(effect.threadId.value in threadIds) {
                        "[$id] event ${event.id} advances unknown thread '${effect.threadId.value}'"
                    }
                    is EventEffect.AdvanceTime, is EventEffect.SetVariable, is EventEffect.ScheduleEvent -> Unit
                }
            }
            event.conditions.forEach { condition ->
                when (condition) {
                    is EventCondition.CharacterAtLocation -> {
                        requireCharacter(condition.characterId.value, "event ${event.id} condition")
                        requireLocation(condition.locationId.value, "event ${event.id} condition")
                    }
                    is EventCondition.CharacterNotAtLocation -> {
                        requireCharacter(condition.characterId.value, "event ${event.id} condition")
                        requireLocation(condition.locationId.value, "event ${event.id} condition")
                    }
                    is EventCondition.ThreadStageAtLeast -> requireThread(condition.threadId.value, "event ${event.id} condition")
                    is EventCondition.ThreadStatusIs -> requireThread(condition.threadId.value, "event ${event.id} condition")
                    is EventCondition.RelationshipTrustAtLeast -> {
                        requireCharacter(condition.sourceId.value, "event ${event.id} condition")
                        requireCharacter(condition.targetId.value, "event ${event.id} condition")
                    }
                    is EventCondition.CharacterKnowsFact -> {
                        requireCharacter(condition.characterId.value, "event ${event.id} condition")
                        require(condition.factId.value in factIds) {
                            "[$id] event ${event.id} condition references unknown fact '${condition.factId.value}'"
                        }
                    }
                    is EventCondition.FactExists -> require(condition.factId.value in factIds) {
                        "[$id] event ${event.id} condition references unknown fact '${condition.factId.value}'"
                    }
                    is EventCondition.SceneIsOpen -> requireLocation(condition.locationId.value, "event ${event.id} condition")
                    is EventCondition.VariableEquals, is EventCondition.VariableNotEquals, is EventCondition.StoryTimeReached -> Unit
                }
            }
            event.threadChanges.forEach { change ->
                require(change.threadId.value in threadIds) {
                    "[$id] event ${event.id} references unknown thread '${change.threadId.value}'"
                }
            }
        }
        scenarios.forEach { scenario ->
            requireLocation(scenario.startLocationId.value, "scenario ${scenario.id}")
            scenario.focusCharacterId?.let { requireCharacter(it.value, "scenario ${scenario.id}") }
            scenario.castCharacterIds.forEach { requireCharacter(it.value, "scenario ${scenario.id}") }
            scenario.startActivities.keys.forEach { requireCharacter(it.value, "scenario ${scenario.id}") }
            scenario.threadStages.keys.forEach { require(it in threadIds) {
                "[$id] scenario ${scenario.id} references unknown thread '$it'"
            } }
            scenario.eventIds.forEach { require(it in eventIds) {
                "[$id] scenario ${scenario.id} references unknown event '$it'"
            } }
        }
        personas.forEach { persona ->
            requireLocation(persona.startingLocationId?.value, "persona ${persona.id}")
            persona.suggestedCharacterIds.forEach { requireCharacter(it.value, "persona ${persona.id}") }
        }
        factions.forEach { faction ->
            faction.memberCharacterIds.forEach { requireCharacter(it.value, "faction ${faction.id}") }
            requireLocation(faction.seatLocationId?.value, "faction ${faction.id}")
        }

        return StoryPack(
            id = StoryPackId(id),
            title = title,
            description = description,
            author = author,
            identity = identity,
            characters = characters,
            locations = locations,
            factions = factions,
            lore = lore,
            events = events,
            scenarios = scenarios,
            personas = personas,
            defaultModelProfileId = defaultModelProfileId,
            visualAssets = visualAssets,
            initialWorldState = dev.charaly.runtime.domain.InitialWorldState(
                startTime = startTime,
                variables = variables,
                startLocations = startLocations.mapKeys { CharacterId(it.key) }.mapValues { LocationId(it.value) },
                startActivities = startActivities.mapKeys { CharacterId(it.key) },
                startGoals = startGoals.mapKeys { CharacterId(it.key) },
            ),
            initialRelationships = relationships,
            initialKnowledge = dev.charaly.runtime.domain.InitialKnowledge(
                facts = facts,
                characterKnowledge = characterKnowledge.toMap().mapKeys { CharacterId(it.key) }
                    .mapValues { it.value },
            ),
            initialStoryThreads = threads,
            tags = identity.genres,
        ).let { built ->
            // Authored memories are part of InitialKnowledge, so they are set here.
            if (authoredMemories.isEmpty()) {
                built
            } else {
                built.copy(
                    initialKnowledge = built.initialKnowledge.copy(authoredMemories = authoredMemories),
                )
            }
        }
    }
}