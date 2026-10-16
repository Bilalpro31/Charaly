package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.EventCondition
import dev.charaly.runtime.domain.EventEffect
import dev.charaly.runtime.domain.LocationId
import dev.charaly.runtime.domain.PackAudioAsset
import dev.charaly.runtime.domain.PackObjective
import dev.charaly.runtime.domain.SceneTemplate
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.VisualAsset

/**
 * Pack validation: the gate a story must pass through before it is allowed to exist on
 * this device.
 *
 * ## What it is for
 *
 * A `.charaly-pack` is authored by someone else. It can be incomplete, contradictory,
 * tangled, or hostile. Validation answers one question - *may this be installed?* - and
 * answers it in three words: `OK`, `UYARI`, `KRİTİK HATA`.
 *
 * `KRİTİK` blocks installation outright. Not because it is fussy, but because every one
 * of these findings is a story that will silently misbehave later: a character who
 * teleports because his start location does not exist, a branch that reads as a choice
 * and does nothing, an objective that can never be completed. Those bugs are undetectable
 * in play and corrosive to trust, and the only place they can be caught honestly is here,
 * before anything is written.
 *
 * `UYARI` does not block. It says "this will work, but this is not what you probably
 * meant".
 *
 * ## What it shows a person
 *
 * Messages are Turkish and name things the way an author named them ("Marinette"),
 * never as internal ids. Ids are kept in [ValidationIssue.detail] for the developer
 * panel, where they are useful, and never shown to a player, where they are noise.
 */
enum class Severity {
    /** Nothing wrong that would affect play. */
    OK,

    /** Works, but imperfect. Installation proceeds. */
    WARNING,

    /** Would corrupt play. Installation is refused. */
    CRITICAL,
    ;

    val isBlocking: Boolean get() = this == CRITICAL

    companion object {
        fun worst(a: Severity, b: Severity): Severity =
            if (a.ordinal >= b.ordinal) a else b
    }
}

/** The kind of finding, so a report can be grouped and a test can assert a specific claim. */
enum class IssueCode {
    INVALID_MANIFEST,
    INVALID_SCHEMA,
    DANGLING_REFERENCE,
    UNSUPPORTED_SCHEMA,
    UNSUPPORTED_ENGINE,
    INVALID_VERSION,
    DUPLICATE_ID,
    MISSING_ID,
    INVALID_CONDITION,
    INVALID_EFFECT,
    INVALID_CHARACTER_REF,
    INVALID_LOCATION_REF,
    INVALID_RELATIONSHIP_REF,
    INVALID_SCENE_REF,
    INVALID_EVENT_REF,
    INVALID_OBJECTIVE,
    INVALID_SCENARIO,
    INVALID_PERSONA,
    INVALID_ASSET,
    INVALID_LOCALIZATION,
    UNREACHABLE_CONTENT,
    IMPOSSIBLE_START,
    BROKEN_BRANCHING,
    CHECKSUM_MISMATCH,
    MISSING_FILE,
    UNKNOWN_FILE,
    ARCHIVE_REFUSED,
    STORY_HEALTH,
    ;
}

/**
 * One finding.
 *
 * [message] is what a person reads. [detail] and [subject] are what a developer reads.
 * The two audiences are separated in the type rather than in the screen, so no presenter
 * can accidentally print an id at a user.
 */
data class ValidationIssue(
    val code: IssueCode,
    val severity: Severity,
    val message: String,
    val detail: String = "",
    val subject: String = "",
) {
    val isBlocking: Boolean get() = severity.isBlocking

    companion object {
        fun critical(code: IssueCode, message: String, detail: String = "", subject: String = "") =
            ValidationIssue(code, Severity.CRITICAL, message, detail, subject)

        fun warning(code: IssueCode, message: String, detail: String = "", subject: String = "") =
            ValidationIssue(code, Severity.WARNING, message, detail, subject)

        fun archiveWarning(message: String) =
            ValidationIssue(IssueCode.UNKNOWN_FILE, Severity.WARNING, message)

        fun archiveRefusal(code: StoryPackArchive.Refusal, detail: String): ValidationIssue {
            val message = when (code) {
                StoryPackArchive.Refusal.NOT_AN_ARCHIVE ->
                    "Bu dosya bir hikâye paketi arşivi değil."
                StoryPackArchive.Refusal.PATH_TRAVERSAL ->
                    "Paket dışarıdaki dosyalara dokunmaya çalıştı. Kurulum engellendi."
                StoryPackArchive.Refusal.ABSOLUTE_PATH ->
                    "Paket geçersiz dosya yolları içeriyor. Kurulum engellendi."
                StoryPackArchive.Refusal.ENTRY_TOO_LARGE ->
                    "Paket içindeki bir dosya izin verilenden büyük."
                StoryPackArchive.Refusal.ARCHIVE_TOO_LARGE ->
                    "Paket açıldığında izin verilenden fazla yer kaplıyor."
                StoryPackArchive.Refusal.TOO_MANY_ENTRIES ->
                    "Paket izin verilenden fazla dosya içeriyor."
                StoryPackArchive.Refusal.ZIP_BOMB ->
                    "Paket olağandışı biçimde sıkıştırılmış; güvenlik nedeniyle reddedildi."
                StoryPackArchive.Refusal.DUPLICATE_ENTRY ->
                    "Paket aynı dosyayı iki kez içeriyor. Güvenlik nedeniyle reddedildi."
                StoryPackArchive.Refusal.MANIFEST_INVALID ->
                    "Paket tanımı (manifest.json) bulunamadı ya da okunamadı."
                StoryPackArchive.Refusal.MALFORMED_ENTRY ->
                    "Paket arşivi bozuk ya da yarım kalmış."
            }
            return ValidationIssue(IssueCode.ARCHIVE_REFUSED, Severity.CRITICAL, message, detail)
        }

        /** Worst severity across a report. Empty reports are `OK`. */
        fun verdictOf(issues: List<ValidationIssue>): Severity =
            issues.fold(Severity.OK) { acc, issue -> Severity.worst(acc, issue.severity) }
    }
}

/**
 * The report a user sees after an import or in the authoring review step.
 *
 * Counts of *content* lead ("50 olay"), problems follow, and the ids stay behind. The
 * wording of [headline] is the sentence the pack lives or dies by.
 */
data class ValidationReport(
    val issues: List<ValidationIssue>,
    val census: StoryPackBundle.Census? = null,
) {
    val severity: Severity get() = ValidationIssue.verdictOf(issues)

    val isInstallable: Boolean get() = severity != Severity.CRITICAL

    val errors: List<ValidationIssue> get() = issues.filter { it.severity == Severity.CRITICAL }
    val warnings: List<ValidationIssue> get() = issues.filter { it.severity == Severity.WARNING }

    /** "Kuruluma hazır", "2 uyarı ile kurulabilir", "3 kritik hata". Turkish. */
    fun headline(): String = when {
        errors.isNotEmpty() && warnings.isNotEmpty() ->
            "${errors.size} kritik hata, ${warnings.size} uyarı"
        errors.isNotEmpty() -> "${errors.size} kritik hata"
        warnings.isNotEmpty() -> "${warnings.size} uyarı ile kurulabilir"
        issues.isEmpty() -> "Kontrol tamamlandı, sorun bulunamadı"
        else -> "Kuruluma hazır"
    }

    /** "5 karakter · 3 mekan · 12 olay" style summary of what the pack actually holds. */
    fun censusLine(): String =
        census?.lines()?.joinToString(" · ") { (label, count) -> "$count $label" }.orEmpty()

    /** Everything a developer needs, and a player never sees. */
    fun debugLines(): List<String> = issues.map { "${it.severity.name} ${it.code}: ${it.message} ${it.detail}" }
}

/**
 * The validator itself. Pure functions over pack content: no IO, no clock, no model.
 *
 * Grouped by *kind of wrong* rather than by file, because a dangling reference can be in
 * any of nine places and "which files have dangling references" is not a question anyone
 * asks. Each group is individually callable so the authoring step can explain one class
 * of problem at a time.
 */
object StoryPackValidator {

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------

    fun validateManifest(manifest: PackManifest, engineVersion: String): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (manifest.schemaVersion > StoryPackFormat.SCHEMA_VERSION) {
            issues += ValidationIssue.critical(
                IssueCode.UNSUPPORTED_SCHEMA,
                "Paket bu Charaly sürümünün okuyamadığı bir içerik biçimi kullanıyor.",
                detail = "schemaVersion=${manifest.schemaVersion}",
            )
        } else if (manifest.schemaVersion < StoryPackFormat.MINIMUM_SCHEMA_VERSION) {
            issues += ValidationIssue.critical(
                IssueCode.UNSUPPORTED_SCHEMA,
                "Paket çok eski bir içerik biçimi kullanıyor.",
                detail = "schemaVersion=${manifest.schemaVersion}",
            )
        }

        if (!SemVer.isValid(manifest.packVersion)) {
            issues += ValidationIssue.critical(
                IssueCode.INVALID_VERSION,
                "Paket sürümü geçerli bir sürüm numarası değil.",
                detail = "packVersion=${manifest.packVersion}",
            )
        }

        if (manifest.minimumEngineVersion.isNotBlank() &&
            !SemVer.satisfies(engineVersion, minimum = manifest.minimumEngineVersion)
        ) {
            issues += ValidationIssue.critical(
                IssueCode.UNSUPPORTED_ENGINE,
                "Paket en az Charaly ${manifest.minimumEngineVersion} istiyor; bu cihazda $engineVersion var.",
                detail = "minimumEngineVersion=${manifest.minimumEngineVersion} engine=$engineVersion",
            )
        }

        if (manifest.maximumEngineVersion.isNotBlank() &&
            !SemVer.satisfies(engineVersion, maximum = manifest.maximumEngineVersion)
        ) {
            issues += ValidationIssue.critical(
                IssueCode.UNSUPPORTED_ENGINE,
                "Paket Charaly ${manifest.maximumEngineVersion} sonrasıyla çalışmıyor; bu cihazda $engineVersion var.",
                detail = "maximumEngineVersion=${manifest.maximumEngineVersion} engine=$engineVersion",
            )
        }

        if (manifest.cover.isNotBlank() &&
            !StoryPackFormat.isSafeRelativePath(manifest.cover) &&
            !manifest.cover.startsWith("generated:")
        ) {
            issues += ValidationIssue.warning(
                IssueCode.INVALID_ASSET,
                "Kapak görselinin yolu bir cihaz yolu gibi görünüyor; paket taşınabilirliğini kaybeder.",
                detail = "cover=${manifest.cover}",
            )
        }

        return issues
    }

    // ------------------------------------------------------------------
    // Structure: duplicate and missing ids
    // ------------------------------------------------------------------

    fun validateBundle(bundle: StoryPackBundle): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        fun duplicates(values: List<String>): List<String> =
            values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()

        fun check(kind: String, label: String, values: List<String>) {
            duplicates(values).forEach { id ->
                issues += ValidationIssue.critical(
                    IssueCode.DUPLICATE_ID,
                    "$label içinde aynı tanımlı iki öğe var.",
                    detail = "$kind duplicate id: $id",
                    subject = id,
                )
            }
            values.filter { it.isBlank() }.forEach {
                issues += ValidationIssue.critical(
                    IssueCode.MISSING_ID,
                    "$label içinde tanımı olmayan bir öğe var.",
                    detail = "$kind blank id",
                )
            }
        }

        check("character", "Kadro", bundle.characters.map { it.id.value })
        check("location", "Mekânlar", bundle.locations.map { it.id.value })
        check("scene", "Sahneler", bundle.scenes.map { it.id })
        check("event", "Olaylar", bundle.events.events.map { it.id })
        check("thread", "Hikâye çizgileri", bundle.threads.map { it.id.value })
        check("objective", "Görevler", bundle.objectives.map { it.id })
        check("scenario", "Açılışlar", bundle.scenarios.map { it.id })
        check("persona", "Roller", bundle.personas.map { it.id })
        check("faction", "Topluluklar", bundle.world.factions.map { it.id })
        check("lore", "Dünya bilgisi", bundle.world.lore.map { it.id })
        check("fact", "Gerçekler", bundle.knowledge.facts.map { it.id.value })
        check("visual-asset", "Görseller", bundle.assets.visualAssets.map { it.assetId })
        check("audio-asset", "Sesler", bundle.assets.audioAssets.map { it.id })

        bundle.scenes.forEach { scene ->
            duplicates(scene.choices.map { it.id }).forEach { id ->
                issues += ValidationIssue.critical(
                    IssueCode.DUPLICATE_ID,
                    "\"${scene.name}\" sahnesinde aynı seçenek iki kez tanımlanmış.",
                    detail = "scene ${scene.id} duplicate choice $id",
                    subject = id,
                )
            }
        }

        return issues
    }

    // ------------------------------------------------------------------
    // Content sanity
    // ------------------------------------------------------------------

    fun validateContent(pack: StoryPack, bundle: StoryPackBundle): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        if (pack.characters.isEmpty()) {
            issues += ValidationIssue.critical(
                IssueCode.IMPOSSIBLE_START,
                "Paketin kadrosu boş. Kimse yoksa hikâye başlayamaz.",
                detail = "characters=0",
            )
        }
        if (pack.locations.isEmpty()) {
            issues += ValidationIssue.critical(
                IssueCode.IMPOSSIBLE_START,
                "Paketin hiç mekânı yok. Geçilecek bir dünya yok.",
                detail = "locations=0",
            )
        }
        if (pack.scenarios.isEmpty()) {
            issues += ValidationIssue.critical(
                IssueCode.IMPOSSIBLE_START,
                "Pakette hiç açılış senaryosu yok; hikâyenin nasıl başlayacağı tanımlı değil.",
                detail = "scenarios=0",
            )
        }
        if (pack.personas.isEmpty()) {
            issues += ValidationIssue.warning(
                IssueCode.IMPOSSIBLE_START,
                "Pakette oyuncunun üstlenebileceği bir rol tanımlı değil.",
                detail = "personas=0",
            )
        }
        if (pack.settings.requiresNetwork) {
            issues += ValidationIssue.critical(
                IssueCode.IMPOSSIBLE_START,
                "Hikâye paketi oynamak için ağ istiyor. Charaly'de hikâye her zaman çevrimdışı oynanır.",
                detail = "settings.requiresNetwork=true",
            )
        }

        // Every opening must lead somewhere that exists.
        pack.scenarios.forEach { scenario ->
            if (pack.location(scenario.startLocationId) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_SCENARIO,
                    "\"${scenario.title}\" açılışı var olmayan bir mekânda başlıyor.",
                    detail = "scenario=${scenario.id} startLocation=${scenario.startLocationId.value}",
                    subject = scenario.id,
                )
            }
            scenario.castCharacterIds.forEach { characterId ->
                if (pack.character(characterId) == null) {
                    issues += ValidationIssue.critical(
                        IssueCode.INVALID_CHARACTER_REF,
                        "\"${scenario.title}\" açılışı kadroda olmayan birini sahneye çıkarıyor.",
                        detail = "scenario=${scenario.id} cast=${characterId.value}",
                        subject = scenario.id,
                    )
                }
            }
            scenario.objectiveIds.forEach { objectiveId ->
                if (pack.objective(objectiveId) == null) {
                    issues += ValidationIssue.critical(
                        IssueCode.INVALID_OBJECTIVE,
                        "\"${scenario.title}\" açılışı var olmayan bir görevi açıyor.",
                        detail = "scenario=${scenario.id} objective=$objectiveId",
                        subject = scenario.id,
                    )
                }
            }
            if (scenario.initialSceneId.isNotBlank() && pack.scene(scenario.initialSceneId) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_SCENE_REF,
                    "\"${scenario.title}\" açılışı var olmayan bir sahnede başlıyor.",
                    detail = "scenario=${scenario.id} initialScene=${scenario.initialSceneId}",
                    subject = scenario.id,
                )
            }
        }

        // Characters and places must be able to begin.
        pack.characters.forEach { character ->
            val start = pack.startLocationOf(character.id) ?: character.startingLocationId
            if (start != null && pack.location(start) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_LOCATION_REF,
                    "${character.name} var olmayan bir mekânda başlıyor.",
                    detail = "character=${character.id.value} start=${start.value}",
                    subject = character.id.value,
                )
            }
            if (start == null && pack.locations.isNotEmpty()) {
                issues += ValidationIssue.warning(
                    IssueCode.IMPOSSIBLE_START,
                    "${character.name} için bir başlangıç mekânı belirtilmemiş.",
                    detail = "character=${character.id.value}",
                    subject = character.id.value,
                )
            }
        }

        pack.objectives.forEach { objective -> issues += validateObjective(pack, objective) }
        pack.scenes.forEach { scene -> issues += validateScene(pack, scene) }
        bundle.knowledge.characterKnowledge.forEach { (characterId, factIds) ->
            if (pack.character(characterId) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_CHARACTER_REF,
                    "Bilgi dağıtımı kadroda olmayan birine yapılıyor.",
                    detail = "characterKnowledge=${characterId.value}",
                    subject = characterId.value,
                )
            }
            factIds.forEach { factId ->
                if (bundle.knowledge.facts.none { it.id.value == factId }) {
                    issues += ValidationIssue.critical(
                        IssueCode.DANGLING_REFERENCE,
                        "Tanımlanmamış bir bilgi bir kişiye verilmiş.",
                        detail = "character=${characterId.value} fact=$factId",
                        subject = factId,
                    )
                }
            }
        }
        bundle.knowledge.characterMinds.keys.forEach { characterId ->
            if (pack.character(characterId) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_CHARACTER_REF,
                    "Zihin kaydı kadroda olmayan biri için yazılmış.",
                    detail = "characterMinds=${characterId.value}",
                    subject = characterId.value,
                )
            }
        }

        return issues
    }

    private fun validateObjective(pack: StoryPack, objective: PackObjective): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (!objective.isResolvable) {
            issues += ValidationIssue.critical(
                IssueCode.INVALID_OBJECTIVE,
                "\"${objective.title}\" görevi hiçbir zaman tamamlanamaz: bitiş koşulu yazılmamış.",
                detail = "objective=${objective.id} completionConditions=0",
                subject = objective.id,
            )
        }
        issues += validateConditions(pack, objective.completionConditions, "görev \"${objective.title}\"", objective.id)
        issues += validateConditions(pack, objective.failureConditions, "görev \"${objective.title}\"", objective.id)
        issues += validateEffects(pack, objective.onComplete, "görev \"${objective.title}\"", objective.id)
        issues += validateEffects(pack, objective.onFailure, "görev \"${objective.title}\"", objective.id)
        objective.threadId?.let { threadId ->
            if (pack.initialStoryThreads.none { it.id == threadId }) {
                issues += ValidationIssue.critical(
                    IssueCode.DANGLING_REFERENCE,
                    "\"${objective.title}\" görevi var olmayan bir hikâye çizgisine bağlı.",
                    detail = "objective=${objective.id} thread=${threadId.value}",
                    subject = objective.id,
                )
            }
        }
        objective.relatedCharacterIds.forEach { id ->
            if (pack.character(id) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_CHARACTER_REF,
                    "\"${objective.title}\" görevi kadroda olmayan birini ilgilendiriyor.",
                    detail = "objective=${objective.id} character=${id.value}",
                    subject = objective.id,
                )
            }
        }
        objective.relatedLocationIds.forEach { id ->
            if (pack.location(id) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_LOCATION_REF,
                    "\"${objective.title}\" görevi var olmayan bir mekândan söz ediyor.",
                    detail = "objective=${objective.id} location=${id.value}",
                    subject = objective.id,
                )
            }
        }
        return issues
    }

    private fun validateScene(pack: StoryPack, scene: SceneTemplate): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (pack.location(scene.locationId) == null) {
            issues += ValidationIssue.critical(
                IssueCode.INVALID_SCENE_REF,
                "\"${scene.name}\" sahnesi var olmayan bir mekânda geçiyor.",
                detail = "scene=${scene.id} location=${scene.locationId.value}",
                subject = scene.id,
            )
        }
        scene.participantCharacterIds.forEach { id ->
            if (pack.character(id) == null) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_CHARACTER_REF,
                    "\"${scene.name}\" sahnesinde kadroda olmayan biri var.",
                    detail = "scene=${scene.id} character=${id.value}",
                    subject = scene.id,
                )
            }
        }
        scene.threadIds.forEach { id ->
            if (pack.initialStoryThreads.none { it.id == id }) {
                issues += ValidationIssue.critical(
                    IssueCode.DANGLING_REFERENCE,
                    "\"${scene.name}\" sahnesi var olmayan bir hikâye çizgisine bağlı.",
                    detail = "scene=${scene.id} thread=${id.value}",
                    subject = scene.id,
                )
            }
        }
        issues += validateConditions(pack, scene.entryConditions, "sahne \"${scene.name}\"", scene.id)
        issues += validateConditions(pack, scene.exitConditions, "sahne \"${scene.name}\"", scene.id)
        scene.choices.forEach { choice ->
            if (choice.effects.isEmpty() && choice.threadChanges.isEmpty()) {
                issues += ValidationIssue.critical(
                    IssueCode.BROKEN_BRANCHING,
                    "\"${scene.name}\" sahnesindeki \"${choice.label}\" seçeneği hiçbir şeyi değiştirmiyor.",
                    detail = "scene=${scene.id} choice=${choice.id} effects=0",
                    subject = choice.id,
                )
            }
            issues += validateConditions(pack, choice.conditions, "seçenek \"${choice.label}\"", choice.id)
            issues += validateEffects(pack, choice.effects, "seçenek \"${choice.label}\"", choice.id)
        }
        return issues
    }

    // ------------------------------------------------------------------
    // Cross references
    // ------------------------------------------------------------------

    fun validateReferences(pack: StoryPack): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val characterIds = pack.characters.map { it.id.value }.toSet()
        val locationIds = pack.locations.map { it.id.value }.toSet()
        val threadIds = pack.initialStoryThreads.map { it.id.value }.toSet()
        val eventIds = pack.events.map { it.id }.toSet()
        val factIds = pack.initialKnowledge.facts.map { it.id.value }.toSet()

        fun nameOf(id: CharacterId): String = pack.character(id)?.name ?: "bilinmeyen biri"

        fun characterRef(id: CharacterId, where: String, subject: String) {
            if (id.value !in characterIds) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_CHARACTER_REF,
                    "$where kadroda olmayan birini işaret ediyor.",
                    detail = "character=${id.value}",
                    subject = subject,
                )
            }
        }

        fun locationRef(id: LocationId, where: String, subject: String) {
            if (id.value !in locationIds) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_LOCATION_REF,
                    "$where var olmayan bir mekânı işaret ediyor.",
                    detail = "location=${id.value}",
                    subject = subject,
                )
            }
        }

        fun threadRef(id: String, where: String, subject: String) {
            if (id !in threadIds) {
                issues += ValidationIssue.critical(
                    IssueCode.DANGLING_REFERENCE,
                    "$where var olmayan bir hikâye çizgisini işaret ediyor.",
                    detail = "thread=$id",
                    subject = subject,
                )
            }
        }

        fun factRef(id: String, where: String, subject: String) {
            if (id !in factIds) {
                issues += ValidationIssue.critical(
                    IssueCode.DANGLING_REFERENCE,
                    "$where var olmayan bir bilgiyi işaret ediyor.",
                    detail = "fact=$id",
                    subject = subject,
                )
            }
        }

        // Relationships. Named in words - the two people are what a reader recognises.
        pack.initialRelationships.forEach { relationship ->
            val pairLabel = "\"${nameOf(relationship.sourceId)} ile ${nameOf(relationship.targetId)}\" arasındaki ilişki"
            characterRef(relationship.sourceId, pairLabel, relationship.toString())
            characterRef(relationship.targetId, pairLabel, relationship.toString())
        }

        // Events: participants, location, effects, nested scheduling.
        pack.events.forEach { event ->
            event.participants.forEach { characterRef(it, "Olay \"${event.title}\"", event.id) }
            event.locationId?.let { locationRef(it, "Olay \"${event.title}\"", event.id) }
            issues += validateConditions(pack, event.conditions, "olay \"${event.title}\"", event.id)
            issues += validateEffects(pack, event.effects, "olay \"${event.title}\"", event.id)
            event.threadChanges.forEach {
                if (it.threadId.value !in threadIds) {
                    issues += ValidationIssue.critical(
                        IssueCode.DANGLING_REFERENCE,
                        "Olay \"${event.title}\" var olmayan bir hikâye çizgisini ilerletiyor.",
                        detail = "thread=${it.threadId.value}",
                        subject = event.id,
                    )
                }
            }
            event.effects.filterIsInstance<EventEffect.ScheduleEvent>().forEach { nested ->
                if (nested.eventId !in eventIds) {
                    issues += ValidationIssue.critical(
                        IssueCode.INVALID_EVENT_REF,
                        "Olay \"${event.title}\" var olmayan bir olayı zamanlıyor.",
                        detail = "schedule=${nested.eventId}",
                        subject = event.id,
                    )
                }
            }
        }

        // Threads
        pack.initialStoryThreads.forEach { thread ->
            thread.involvedCharacterIds.forEach { characterRef(it, "\"${thread.title}\" çizgisi", thread.id.value) }
            thread.relevantLocationIds.forEach { locationRef(it, "\"${thread.title}\" çizgisi", thread.id.value) }
        }

        // Factions and lore
        pack.factions.forEach { faction ->
            faction.memberCharacterIds.forEach { characterRef(it, "\"${faction.name}\" topluluğu", faction.id) }
            faction.seatLocationId?.let { locationRef(it, "\"${faction.name}\" topluluğu", faction.id) }
        }
        pack.lore.forEach { entry ->
            entry.relatedCharacterIds.forEach { characterRef(it, "\"${entry.title}\" bilgisi", entry.id) }
            entry.relatedLocationIds.forEach { locationRef(it, "\"${entry.title}\" bilgisi", entry.id) }
        }

        // Canon indexes
        pack.canon.factIds.forEach { factRef(it.value, "Kanon", "canon") }
        pack.canon.majorArcThreadIds.forEach { threadRef(it.value, "Kanon", "canon") }

        // Persona knowledge and relationships
        pack.personas.forEach { persona ->
            persona.startingKnowledgeFactIds.forEach { factRef(it.value, "\"${persona.name}\" rolü", persona.id) }
            persona.startingRelationships.forEach { relationship ->
                characterRef(relationship.targetId, "\"${persona.name}\" rolü", persona.id)
            }
        }

        // Scenarios: threads and events
        pack.scenarios.forEach { scenario ->
            scenario.eventIds.forEach { eventId ->
                if (eventId !in eventIds) {
                    issues += ValidationIssue.critical(
                        IssueCode.INVALID_EVENT_REF,
                        "\"${scenario.title}\" açılışı var olmayan bir olayı çağırıyor.",
                        detail = "event=$eventId",
                        subject = scenario.id,
                    )
                }
            }
            scenario.threadStages.keys.forEach { threadRef(it, "\"${scenario.title}\" açılışı", scenario.id) }
        }

        return issues
    }

    private fun validateConditions(
        pack: StoryPack,
        conditions: List<EventCondition>,
        where: String,
        subject: String,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val characterIds = pack.characters.map { it.id.value }.toSet()
        val locationIds = pack.locations.map { it.id.value }.toSet()
        val threadIds = pack.initialStoryThreads.map { it.id.value }.toSet()
        val factIds = pack.initialKnowledge.facts.map { it.id.value }.toSet()

        conditions.forEach { condition ->
            val missing = when (condition) {
                is EventCondition.VariableEquals,
                is EventCondition.VariableNotEquals,
                -> null
                is EventCondition.CharacterAtLocation ->
                    condition.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                        ?: condition.locationId.value.takeIf { it !in locationIds }?.let { "mekân" to it }
                is EventCondition.CharacterNotAtLocation ->
                    condition.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                is EventCondition.ThreadStageAtLeast ->
                    condition.threadId.value.takeIf { it !in threadIds }?.let { "çizgi" to it }
                is EventCondition.ThreadStatusIs ->
                    condition.threadId.value.takeIf { it !in threadIds }?.let { "çizgi" to it }
                is EventCondition.RelationshipTrustAtLeast ->
                    condition.sourceId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                        ?: condition.targetId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                is EventCondition.StoryTimeReached -> null
                is EventCondition.CharacterKnowsFact ->
                    condition.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                        ?: condition.factId.value.takeIf { it !in factIds }?.let { "bilgi" to it }
                is EventCondition.FactExists ->
                    condition.factId.value.takeIf { it !in factIds }?.let { "bilgi" to it }
                is EventCondition.SceneIsOpen ->
                    condition.locationId.value.takeIf { it !in locationIds }?.let { "mekân" to it }
            }
            if (missing != null) {
                val (kind, id) = missing
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_CONDITION,
                    "$where koşulu $kind olarak var olmayan bir şeyi kullanıyor.",
                    detail = "condition=${condition.describe()} missing=$kind:$id",
                    subject = subject,
                )
            }
        }
        return issues
    }

    private fun validateEffects(
        pack: StoryPack,
        effects: List<EventEffect>,
        where: String,
        subject: String,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val characterIds = pack.characters.map { it.id.value }.toSet()
        val locationIds = pack.locations.map { it.id.value }.toSet()
        val threadIds = pack.initialStoryThreads.map { it.id.value }.toSet()
        val factIds = pack.initialKnowledge.facts.map { it.id.value }.toSet()
        val eventIds = pack.events.map { it.id }.toSet()

        effects.forEach { effect ->
            val problem = when (effect) {
                is EventEffect.MoveCharacter ->
                    effect.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                        ?: effect.toLocationId.value.takeIf { it !in locationIds }?.let { "mekân" to it }
                is EventEffect.ChangeActivity ->
                    effect.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                is EventEffect.ChangeRelationship ->
                    effect.sourceId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                        ?: effect.targetId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                is EventEffect.SetVariable -> null
                is EventEffect.AdvanceThread ->
                    effect.threadId.value.takeIf { it !in threadIds }?.let { "çizgi" to it }
                is EventEffect.GrantKnowledge ->
                    effect.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                        ?: effect.factId.value.takeIf { it !in factIds }?.let { "bilgi" to it }
                is EventEffect.RevokeKnowledge ->
                    effect.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                is EventEffect.CreateMemory ->
                    effect.characterId.value.takeIf { it !in characterIds }?.let { "karakter" to it }
                is EventEffect.StartScene ->
                    effect.locationId.value.takeIf { it !in locationIds }?.let { "mekân" to it }
                is EventEffect.EndScene -> null
                is EventEffect.AdvanceTime -> null
                is EventEffect.ScheduleEvent ->
                    effect.eventId.takeIf { it !in eventIds }?.let { "olay" to it }
            }
            if (problem != null) {
                val (kind, id) = problem
                val code = when (effect) {
                    is EventEffect.ScheduleEvent -> IssueCode.INVALID_EVENT_REF
                    is EventEffect.AdvanceThread -> IssueCode.BROKEN_BRANCHING
                    else -> IssueCode.INVALID_EFFECT
                }
                issues += ValidationIssue.critical(
                    code,
                    "$where sonucu $kind olarak var olmayan bir şeyi değiştiriyor.",
                    detail = "effect=${effect.describe()} missing=$kind:$id",
                    subject = subject,
                )
            }
        }
        return issues
    }

    // ------------------------------------------------------------------
    // Reachability
    // ------------------------------------------------------------------

    /**
     * Content nothing can ever reach.
     *
     * Not an error: an author may keep a scene in the drawer. But a *warning*, because a
     * scene nobody can arrive at is almost always a wiring mistake rather than an
     * artistic choice, and the cost of finding out at review time is zero.
     */
    fun validateReachability(pack: StoryPack): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val referencedThreads = buildSet {
            pack.events.forEach { event -> addAll(event.threadChanges.map { it.threadId.value }) }
            pack.events.forEach { event ->
                event.effects.filterIsInstance<EventEffect.AdvanceThread>().forEach { add(it.threadId.value) }
            }
            pack.scenes.forEach { scene -> addAll(scene.threadIds.map { it.value }) }
            pack.objectives.forEach { objective -> objective.threadId?.let { add(it.value) } }
            pack.scenarios.forEach { scenario -> addAll(scenario.threadStages.keys) }
            pack.canon.majorArcThreadIds.forEach { add(it.value) }
        }
        pack.initialStoryThreads.forEach { thread ->
            if (thread.id.value !in referencedThreads) {
                issues += ValidationIssue.warning(
                    IssueCode.UNREACHABLE_CONTENT,
                    "\"${thread.title}\" hikâye çizgisine hiçbir olay, sahne ya da görev değmiyor.",
                    detail = "thread=${thread.id.value}",
                    subject = thread.id.value,
                )
            }
        }

        val referencedScenes = buildSet {
            pack.scenarios.forEach { scenario -> if (scenario.initialSceneId.isNotBlank()) add(scenario.initialSceneId) }
            pack.scenes.forEach { scene -> scene.choices.forEach { choice -> choice.effects.filterIsInstance<EventEffect.StartScene>() } }
        }
        pack.scenes.forEach { scene ->
            val reachedDirectly = scene.id in referencedScenes || scene.scenarioIds.isNotEmpty()
            val reachable = reachedDirectly || scene.entryConditions.isEmpty()
            if (!reachable) {
                issues += ValidationIssue.warning(
                    IssueCode.UNREACHABLE_CONTENT,
                    "\"${scene.name}\" sahnesine giden bir yol yok.",
                    detail = "scene=${scene.id}",
                    subject = scene.id,
                )
            }
        }

        val referencedCharacters = buildSet {
            pack.scenarios.forEach { scenario ->
                addAll(scenario.castCharacterIds.map { it.value })
                scenario.focusCharacterId?.let { add(it.value) }
            }
            pack.scenes.forEach { scene -> addAll(scene.participantCharacterIds.map { it.value }) }
            pack.events.forEach { event -> addAll(event.participants.map { it.value }) }
            pack.initialStoryThreads.forEach { thread -> addAll(thread.involvedCharacterIds.map { it.value }) }
            pack.initialRelationships.forEach { add(it.sourceId.value); add(it.targetId.value) }
            pack.objectives.forEach { objective -> addAll(objective.relatedCharacterIds.map { it.value }) }
        }
        pack.characters.forEach { character ->
            if (character.id.value !in referencedCharacters) {
                issues += ValidationIssue.warning(
                    IssueCode.UNREACHABLE_CONTENT,
                    "${character.name} hiçbir açılışta, sahnede, olayda ya da çizgide yer almıyor.",
                    detail = "character=${character.id.value}",
                    subject = character.id.value,
                )
            }
        }

        val referencedLocations = buildSet {
            pack.initialWorldState.startLocations.values.forEach { add(it.value) }
            pack.characters.forEach { it.startingLocationId?.let { id -> add(id.value) } }
            pack.scenarios.forEach { add(it.startLocationId.value) }
            pack.scenes.forEach { add(it.locationId.value) }
            pack.locations.forEach { location -> addAll(location.connections.map { it.value }) }
            pack.events.forEach { event -> event.locationId?.let { add(it.value) } }
        }
        pack.locations.forEach { location ->
            if (location.id.value !in referencedLocations) {
                issues += ValidationIssue.warning(
                    IssueCode.UNREACHABLE_CONTENT,
                    "${location.name} hiçbir başlangıca, sahneye ya da yola bağlı değil.",
                    detail = "location=${location.id.value}",
                    subject = location.id.value,
                )
            }
        }

        return issues
    }

    // ------------------------------------------------------------------
    // Branching
    // ------------------------------------------------------------------

    /**
     * Branches that are not branches.
     *
     * The failure mode this catches is uniquely nasty because it is invisible to anyone
     * skimming the authoring screen: a choice reads like a decision and writes nothing, so
     * "help her" and "betray her" leave the world identical. Determinism is what makes
     * branching real - the same inputs must produce the same state change every time, and
     * "no state change at all" is technically deterministic and completely wrong.
     */
    fun validateBranching(pack: StoryPack): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val declaredVariables = pack.initialWorldState.variables.map { it.key }.toSet() +
            pack.scenarios.flatMap { scenario -> scenario.startVariables.map { it.key } }

        pack.scenes.forEach { scene ->
            scene.choices.forEach { choice ->
                choice.conditions.filterIsInstance<EventCondition.VariableEquals>().forEach { condition ->
                    if (condition.key !in declaredVariables) {
                        issues += ValidationIssue.warning(
                            IssueCode.BROKEN_BRANCHING,
                            "\"${scene.name}\" sahnesindeki \"${choice.label}\" seçeniği hiç tanınmamış bir dünyasal değişkene bakıyor.",
                            detail = "variable=${condition.key}",
                            subject = choice.id,
                        )
                    }
                }
            }
        }

        pack.scenarios.forEach { scenario ->
            scenario.openingChoices.forEach { choice ->
                if (choice.effects.isEmpty() && choice.threadChanges.isEmpty()) {
                    issues += ValidationIssue.critical(
                        IssueCode.BROKEN_BRANCHING,
                        "\"${scenario.title}\" açılışındaki \"${choice.label}\" seçeneği hiçbir şeyi değiştirmiyor.",
                        detail = "scenario=${scenario.id} choice=${choice.id}",
                        subject = choice.id,
                    )
                }
            }
        }

        pack.objectives.forEach { objective ->
            if (objective.completionConditions.isEmpty() && objective.failureConditions.isEmpty()) {
                issues += ValidationIssue.warning(
                    IssueCode.BROKEN_BRANCHING,
                    "\"${objective.title}\" görevinin ne kazanıldığı ne de kaybedildiği tanımlı.",
                    detail = "objective=${objective.id}",
                    subject = objective.id,
                )
            }
        }

        return issues
    }

    // ------------------------------------------------------------------
    // Assets
    // ------------------------------------------------------------------

    /**
     * Assets must be *inside the pack* and addressable relatively.
     *
     * A reference is `assets/backgrounds/paris_rooftop.webp` and nothing else. Not
     * `/data/user/0/...`, not `file:///sdcard/...`, not `../../../etc/passwd`. The first
     * two break portability; the third is the pack trying to read the device, and the
     * answer to all three is the same refusal.
     */
    fun validateAssets(
        pack: StoryPack,
        contents: StoryPackArchive.Contents?,
        assetPresence: (String) -> Boolean,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        fun checkRef(ref: String, owner: String, required: Boolean, subject: String) {
            if (ref.isBlank()) return
            if (ref.startsWith("generated:")) return
            if (!StoryPackFormat.isSafeRelativePath(ref) || !ref.startsWith("${StoryPackFormat.ASSET_DIR}/")) {
                issues += ValidationIssue.critical(
                    IssueCode.INVALID_ASSET,
                    "$owner görselinin yolu paket içinde geçerli bir yol değil.",
                    detail = "ref=$ref",
                    subject = subject,
                )
                return
            }
            val present = contents?.assets?.containsKey(ref) == true || assetPresence(ref)
            if (!present) {
                if (required) {
                    issues += ValidationIssue.critical(
                        IssueCode.INVALID_ASSET,
                        "$owner için gereken dosya pakette yok.",
                        detail = "missing=$ref",
                        subject = subject,
                    )
                } else {
                    issues += ValidationIssue.warning(
                        IssueCode.INVALID_ASSET,
                        "$owner için isteğe bağlı dosya pakette yok; yerine üretilmiş görsel kullanılacak.",
                        detail = "missing=$ref",
                        subject = subject,
                    )
                }
            }
        }

        pack.visualAssets.forEach { asset: VisualAsset ->
            checkRef(asset.preferredRef(), "\"${asset.assetId}\" varlığının", required = asset.isPresent, subject = asset.assetId)
            if (asset.thumbnailRef.isNotBlank()) {
                checkRef(asset.thumbnailRef, "\"${asset.assetId}\" küçük görselinin", required = false, subject = asset.assetId)
            }
            if (asset.needsNetwork) {
                issues += ValidationIssue.warning(
                    IssueCode.INVALID_ASSET,
                    "\"${asset.assetId}\" varlığı ağdan indirilmeyi bekliyor; çevrimdışı üretilmiş görsel gösterilecek.",
                    detail = "asset=${asset.assetId} source=${asset.source}",
                    subject = asset.assetId,
                )
            }
        }
        pack.audioAssets.forEach { asset: PackAudioAsset ->
            checkRef("assets/${asset.path.removePrefix("assets/")}", "\"${asset.id}\" sesinin", asset.required, asset.id)
        }

        contents?.assets?.keys?.forEach { path ->
            val declared = pack.visualAssets.any { it.preferredRef() == path || it.thumbnailRef == path } ||
                pack.audioAssets.any { "assets/${it.path.removePrefix("assets/")}" == path }
            if (!declared) {
                issues += ValidationIssue.warning(
                    IssueCode.UNKNOWN_FILE,
                    "Pakette kimseye ait olmayan bir dosya var: $path",
                    detail = "undeclared=$path",
                    subject = path,
                )
            }
        }

        return issues
    }

    // ------------------------------------------------------------------
    // Localization
    // ------------------------------------------------------------------

    /**
     * Translations are checked for shape and coverage, never for quality.
     *
     * A missing translation is a warning with a *fallback*: the pack's own language, then
     * English. A key that names nothing is a mistake, and is flagged so an author learns
     * their typo is not being applied rather than discovering it on a Turkish device.
     */
    fun validateLocalization(
        pack: StoryPack,
        tables: Map<String, Map<String, String>>,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val expectedKeys = PackLocalizer.expectedKeys(pack)

        if (tables.isEmpty()) {
            issues += ValidationIssue.warning(
                IssueCode.INVALID_LOCALIZATION,
                "Paketin hiç çevirisi yok; her şey ${pack.language.uppercase()} dilinde gösterilecek.",
                detail = "localization=none",
            )
            return issues
        }

        StoryPackFormat.SUPPORTED_LANGUAGES.forEach { language ->
            val table = tables[language]
            if (table == null) {
                issues += ValidationIssue.warning(
                    IssueCode.INVALID_LOCALIZATION,
                    "${language.uppercase()} çevirisi pakette yok; yedek dil kullanılacak.",
                    detail = "missing=$language",
                )
                return@forEach
            }
            val missing = expectedKeys.filterNot { it in table }
            if (missing.isNotEmpty()) {
                issues += ValidationIssue.warning(
                    IssueCode.INVALID_LOCALIZATION,
                    "${language.uppercase()} çevirisinde ${missing.size} metin eksik.",
                    detail = "missingKeys=${missing.take(8).joinToString(",")}",
                )
            }
        }

        tables.forEach { (language, table) ->
            table.keys.forEach { key ->
                if (!PackLocalizer.isValidKey(key, pack)) {
                    issues += ValidationIssue.warning(
                        IssueCode.INVALID_LOCALIZATION,
                        "$language çevirisinde hiçbir şeye karşılık gelmeyen bir anahtar var.",
                        detail = "language=$language key=$key",
                        subject = key,
                    )
                }
                if (table[key].isNullOrBlank()) {
                    issues += ValidationIssue.warning(
                        IssueCode.INVALID_LOCALIZATION,
                        "$language çevirisinde boş bırakılmış bir metin var.",
                        detail = "language=$language key=$key",
                        subject = key,
                    )
                }
            }
        }

        return issues
    }

    // ------------------------------------------------------------------
    // Inventory and digest
    // ------------------------------------------------------------------

    /**
     * Does the manifest describe *this* archive?
     *
     * The check that makes a downloaded pack trustworthy: every inventoried file is
     * present, of the declared size, with the declared hash, and the pack digest matches.
     * Anything else is the file having been altered on the way here.
     */
    fun validateInventory(
        manifest: PackManifest,
        contents: StoryPackArchive.Contents,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        manifest.files.forEach { entry ->
            val bytes = contents.assets[entry.path]
                ?: contents.textFiles[entry.path]?.toByteArray(Charsets.UTF_8)
            if (bytes == null) {
                if (entry.required) {
                    issues += ValidationIssue.critical(
                        IssueCode.MISSING_FILE,
                        "Paketin parçası olması gereken bir dosya eksik.",
                        detail = "missing=${entry.path}",
                        subject = entry.path,
                    )
                } else {
                    issues += ValidationIssue.warning(
                        IssueCode.MISSING_FILE,
                        "Paketin isteğe bağlı bir dosyası eksik.",
                        detail = "missing=${entry.path}",
                        subject = entry.path,
                    )
                }
                return@forEach
            }
            if (entry.sha256.isNotBlank() && Sha256.of(bytes) != entry.sha256) {
                issues += ValidationIssue.critical(
                    IssueCode.CHECKSUM_MISMATCH,
                    "Bir dosya indirme sırasında bozulmuş görünüyor.",
                    detail = "corrupt=${entry.path}",
                    subject = entry.path,
                )
            }
            if (entry.sizeBytes > 0 && bytes.size.toLong() != entry.sizeBytes) {
                issues += ValidationIssue.warning(
                    IssueCode.MISSING_FILE,
                    "Bir dosyanın boyutu paket tanımındakiyle uyuşmuyor.",
                    detail = "size=${entry.path} declared=${entry.sizeBytes} actual=${bytes.size}",
                    subject = entry.path,
                )
            }
        }

        contents.paths.filter { it != StoryPackFormat.MANIFEST_PATH }.forEach { path ->
            if (manifest.file(path) == null) {
                issues += ValidationIssue.warning(
                    IssueCode.UNKNOWN_FILE,
                    "Paket tanımında listelenmemiş bir dosya var.",
                    detail = "unlisted=$path",
                    subject = path,
                )
            }
        }

        if (manifest.sha256.isNotBlank() && PackDigest.of(manifest.files) != manifest.sha256) {
            issues += ValidationIssue.critical(
                IssueCode.CHECKSUM_MISMATCH,
                "Paketin bütünlük özeti tutmuyor: dosya yolda değiştirilmiş olabilir.",
                detail = "expected=${manifest.sha256} actual=${PackDigest.of(manifest.files)}",
            )
        }

        return issues
    }
}

private fun SceneTemplate.choicesAreImpossible(): Boolean = false

private fun dev.charaly.runtime.domain.SceneChoice.isOfferedImpossible(): Boolean = false