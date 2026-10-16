package dev.charaly.runtime.storypack

import dev.charaly.runtime.domain.StartingScenario
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.StoryHealthAnalyzer
import dev.charaly.runtime.engine.StoryInstanceFactory

/**
 * The health gate: running the live [StoryHealthAnalyzer] over a pack's *initial* world.
 *
 * ## Why a story analyzer is a pack validator
 *
 * The analyzer answers "is this world internally consistent?", and a freshly created
 * story is a world like any other. Asking it here means a pack that starts its characters
 * in two places at once, hands a secret to someone who should not have it, or declares a
 * fact twice at conflicting confidence is caught *before* anybody plays it - instead of
 * becoming the kind of rot that is invisible until the fifth scene feels wrong.
 *
 * This is not a decorative report. Findings land in the same
 * [ValidationReport] that gates installation, with the same three severities. What was
 * built as a developer panel is now a load-bearing part of the install pipeline, and it
 * still works as a developer panel - [dev.charaly.runtime.session.CharalyRuntime.storyHealth]
 * is unchanged.
 *
 * ## What it does not do
 *
 * Nothing. It is read-only by construction (the analyzer never mutates), deterministic
 * (no model, no clock), and it never blocks on findings that are about *age* - a story
 * cannot have abandoned goals on its first turn, and pretending otherwise would make the
 * gate cry wolf.
 */
object PackHealthGate {

    /** What the gate learned, in analyzer terms and in install terms. */
    data class Result(
        val reports: Map<String, StoryHealthAnalyzer.Report>,
        val issues: List<ValidationIssue>,
    ) {
        val scenarioCount: Int get() = reports.size
        val findingCount: Int get() = reports.values.sumOf { it.findings.size }
        val isClean: Boolean get() = issues.isEmpty()
    }

    /**
     * Analyzes the initial world of every opening this pack offers.
     *
     * One report per scenario, because "First Day" and "Night Patrol" set the world up
     * differently and a pack is healthy only if all of its openings are.
     */
    fun analyse(pack: StoryPack): Result {
        val definition = WorldDefinition(pack.characters, pack.locations)
        val analyzer = StoryHealthAnalyzer(definition)
        val reports = linkedMapOf<String, StoryHealthAnalyzer.Report>()
        val issues = mutableListOf<ValidationIssue>()

        val scenarios: List<StartingScenario?> =
            if (pack.scenarios.isEmpty()) listOf(null) else pack.scenarios.map { it }

        scenarios.forEach { scenario ->
            val created = runCatching {
                StoryInstanceFactory.create(
                    pack = pack,
                    options = dev.charaly.runtime.engine.StoryCreationOptions(
                        instanceId = dev.charaly.runtime.domain.StoryInstanceId("health-${scenario?.id ?: "default"}"),
                        scenario = scenario,
                    ),
                )
            }.getOrElse {
                issues += ValidationIssue.critical(
                    code = IssueCode.IMPOSSIBLE_START,
                    message = "\"${scenario?.title ?: pack.title}\" açılışıyla hikâye hiç başlayamıyor.",
                    detail = "factory: ${it.message}",
                    subject = scenario?.id.orEmpty(),
                )
                return@forEach
            }

            val report = analyzer.analyse(created)
            reports[scenario?.id ?: "default"] = report

            report.findings.forEach { finding ->
                val issue = when {
                    finding.severity == StoryHealthAnalyzer.Status.ERROR ->
                        issueForError(pack, scenario, finding)
                    finding.severity == StoryHealthAnalyzer.Status.WARNING ->
                        issueForWarning(pack, scenario, finding)
                    else -> null
                }
                if (issue != null) issues += issue
            }
        }

        return Result(reports, issues)
    }

    /**
     * Which hard contradictions block installation outright.
     *
     * These are the findings that mean the opening world is *lying* on turn one: someone
     * is in two places, a secret is already readable by its victim, a fact is granted to a
     * person the pack says must not know it. A story that starts there cannot be repaired
     * by playing it well.
     */
    private fun issueForError(
        pack: StoryPack,
        scenario: StartingScenario?,
        finding: StoryHealthAnalyzer.Finding,
    ): ValidationIssue {
        val blocking = setOf(
            StoryHealthAnalyzer.Kind.IMPOSSIBLE_LOCATION,
            StoryHealthAnalyzer.Kind.UNAUTHORISED_KNOWLEDGE,
            StoryHealthAnalyzer.Kind.SECRET_LEAKAGE,
            StoryHealthAnalyzer.Kind.CONTRADICTORY_FACT,
            StoryHealthAnalyzer.Kind.ORPHANED_THREAD,
            StoryHealthAnalyzer.Kind.MISSING_PARTICIPANT,
            StoryHealthAnalyzer.Kind.UNRESOLVABLE_THREAD,
        )
        val message = messageFor(pack, scenario, finding)
        return if (finding.kind in blocking) {
            ValidationIssue.critical(
                code = IssueCode.STORY_HEALTH,
                message = message,
                detail = finding.evidence.joinToString(" | "),
                subject = finding.kind.name,
            )
        } else {
            ValidationIssue.warning(
                code = IssueCode.STORY_HEALTH,
                message = message,
                detail = finding.evidence.joinToString(" | "),
                subject = finding.kind.name,
            )
        }
    }

    private fun issueForWarning(
        pack: StoryPack,
        scenario: StartingScenario?,
        finding: StoryHealthAnalyzer.Finding,
    ): ValidationIssue = ValidationIssue.warning(
        code = IssueCode.STORY_HEALTH,
        message = messageFor(pack, scenario, finding),
        detail = finding.evidence.joinToString(" | ") + " | " + finding.summary,
        subject = finding.kind.name,
    )

    /**
     * A finding, said the way a pack author needs to hear it.
     *
     * The analyzer speaks in ids because it speaks to developers ([ValidationIssue.detail]
     * keeps its exact words). A player-facing report says *who* and *what*, in Turkish, and
     * never an internal id - "Adrien başlaması gereken yerde değil" is actionable;
     * "IMPOSSIBLE_LOCATION: ch-adrien @ loc-vanished" is a bug tracker.
     */
    private fun messageFor(
        pack: StoryPack,
        scenario: StartingScenario?,
        finding: StoryHealthAnalyzer.Finding,
    ): String {
        val where = scenario?.title?.let { "\"$it\" açılışı" } ?: "Başlangıç"
        val who = subjectOf(pack, finding)
        val text = when (finding.kind) {
            StoryHealthAnalyzer.Kind.CONTRADICTORY_MEMORY -> "$who hakkında birbirine uymayan anılar var."
            StoryHealthAnalyzer.Kind.CONTRADICTORY_FACT -> "$who hakkında birbirine uymayan bilgiler var."
            StoryHealthAnalyzer.Kind.IMPOSSIBLE_LOCATION -> "$who bu dünyada olmayan bir yerde başlıyor."
            StoryHealthAnalyzer.Kind.IMPOSSIBLE_TIMELINE -> "$who için zaman çizelgesi tutmuyor."
            StoryHealthAnalyzer.Kind.IMPOSSIBLE_RELATIONSHIP -> "$who ilişkisi kendisiyle çelişiyor."
            StoryHealthAnalyzer.Kind.UNAUTHORISED_KNOWLEDGE -> "$who bilmesi gerekmeyen bir şeyi biliyor."
            StoryHealthAnalyzer.Kind.SECRET_LEAKAGE -> "$who sırrını herkes okuyabiliyor."
            StoryHealthAnalyzer.Kind.DEAD_THREAD -> "\"$who\" uzun süredir hiç ilerlemedi."
            StoryHealthAnalyzer.Kind.ORPHANED_THREAD -> "\"$who\" var olmayan şeylere bağlı kalmış."
            StoryHealthAnalyzer.Kind.MISSING_PARTICIPANT -> "\"$who\" sahnesinde olması gerekenler orada değil."
            StoryHealthAnalyzer.Kind.DUPLICATE_MEMORY -> "$who aynı şeyi iki kez hatırlıyor."
            StoryHealthAnalyzer.Kind.STALE_GOAL -> "$who hedefi çok uzun süredir açık."
            StoryHealthAnalyzer.Kind.EVENT_CONFLICT -> "İki olay aynı sonucu iddia ediyor ($who)."
            StoryHealthAnalyzer.Kind.BROKEN_PROMISE -> "$who sözü süresi geçtiği halde hâlâ açık."
            StoryHealthAnalyzer.Kind.FORGOTTEN_PROMISE -> "$who sözünü unutmuş ama hâlâ sorumlu."
            StoryHealthAnalyzer.Kind.STUCK_CONSEQUENCE -> "$who sonucu tetiklenemiyor."
            StoryHealthAnalyzer.Kind.ABANDONED_GOAL -> "$who hedefi kimse tarafından ilerletilmiyor."
            StoryHealthAnalyzer.Kind.UNRESOLVABLE_THREAD -> "\"$who\" nasıl sonuçlanacağı belli değil."
        }
        return "$where: $text"
    }

    /** The first thing this finding is *about*, in the author's own naming. */
    private fun subjectOf(pack: StoryPack, finding: StoryHealthAnalyzer.Finding): String {
        val haystack = (finding.summary + " " + finding.evidence.joinToString(" "))
        pack.characters.forEach { if (haystack.contains(it.id.value) || haystack.contains(it.name)) return it.name }
        pack.initialStoryThreads.forEach { if (haystack.contains(it.id.value) || haystack.contains(it.title)) return it.title }
        pack.locations.forEach { if (haystack.contains(it.id.value) || haystack.contains(it.name)) return it.name }
        pack.scenes.forEach { if (haystack.contains(it.id)) return it.name }
        pack.objectives.forEach { if (haystack.contains(it.id)) return it.title }
        return "Bu dünya"
    }
}