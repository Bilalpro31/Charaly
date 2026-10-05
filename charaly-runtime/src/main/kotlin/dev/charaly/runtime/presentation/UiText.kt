package dev.charaly.runtime.presentation

import dev.charaly.runtime.domain.PackColor
import dev.charaly.runtime.domain.PackTheme
import dev.charaly.runtime.domain.StoryTime
import kotlin.math.abs
import kotlin.math.max

/**
 * Presentation helpers that are pure, deterministic and unit testable.
 *
 * Everything here lives in the runtime module on purpose: a screen is allowed to
 * be a function of its inputs, and being able to test that in milliseconds on a
 * plain JVM is worth more than putting the formatting next to the composables.
 */

/** "12 min ago", "Yesterday", "3 Mar" - never "null", never an empty string. */
object RelativeTime {

    fun describe(nowEpochMs: Long, thenEpochMs: Long): String {
        if (thenEpochMs <= 0L) return "never"
        val delta = nowEpochMs - thenEpochMs
        val days = delta / DAY
        return when {
            delta < 0L -> "just now"
            delta < MINUTE -> "Just now"
            delta < HOUR -> "${delta / MINUTE} min ago"
            delta < 6 * HOUR -> "${delta / HOUR} h ago"
            // "Yesterday" covers everything from 6 hours ago until two days back,
            // which is what a person means by it.
            days < 2L -> "Yesterday"
            days < 7 -> "$days days ago"
            else -> "Earlier"
        }
    }

    /** Compact form for dense rows: "12m", "3h", "Yesterday". */
    fun compact(nowEpochMs: Long, thenEpochMs: Long): String {
        if (thenEpochMs <= 0L) return "never"
        val delta = nowEpochMs - thenEpochMs
        val days = delta / DAY
        return when {
            delta < MINUTE -> "now"
            delta < HOUR -> "${delta / MINUTE}m"
            delta < 2 * DAY -> "Yesterday"
            days < 7 -> "${days}d"
            else -> "Earlier"
        }
    }

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}

/** "Good evening" for the Home greeting, from the device's wall clock. */
fun greetingFor(epochMs: Long): String {
    val hour = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }.get(java.util.Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        in 18..21 -> "Good evening"
        else -> "Good night"
    }
}

/** Story clock label: "18:42", with the day only when it is not the first. */
fun StoryTime.clockLabel(): String = "%02d:%02d".format(hour, minute)

fun StoryTime.storyLabel(): String = if (day <= 1) clockLabel() else "Day $day · ${clockLabel()}"

/** Deterministic 32-bit hash: used for stable generated artwork. */
fun stableSeed(value: String): Int {
    var hash = 0x811C9DC5.toInt()
    for (char in value) {
        hash = hash xor char.code
        hash *= 0x01000193
    }
    return hash
}

/**
 * A resolved, non-nullable palette for one pack.
 *
 * The app converts these ARGB longs with `Color(0xFF...)`. Keeping the parse in
 * the runtime means a malformed hex string can never crash a screen.
 */
data class ResolvedTheme(
    val primary: Long,
    val secondary: Long,
    val accent: Long,
    val ink: Long,
    val surface: Long,
    val mood: String,
) {
    companion object {
        /** Charaly's own brand palette, used when a pack has no theme of its own. */
        val BRAND = ResolvedTheme(
            primary = PackColor.parse("#8B7BF0"),
            secondary = PackColor.parse("#E0659B"),
            accent = PackColor.parse("#F2B25C"),
            ink = PackColor.parse("#F4F1FA"),
            surface = PackColor.parse("#141319"),
            mood = "",
        )

        fun of(theme: PackTheme): ResolvedTheme = ResolvedTheme(
            primary = theme.primary(),
            secondary = theme.secondary(),
            accent = theme.accent(),
            ink = theme.ink(),
            surface = theme.surface(),
            mood = theme.mood,
        )
    }
}

/**
 * Every empty state in Charaly has real copy.
 *
 * `EmptyState` is data so the library, the sessions screen and the model screen
 * can all use the same shape, and so a test can assert that no screen ever shows
 * a raw "No data".
 */
data class EmptyState(
    val title: String,
    val body: String,
    val actionLabel: String = "",
    val artSeed: String = "",
)

/** A single narrative segment inside a character reply. */
data class NarrativeSegment(
    val kind: SegmentKind,
    val text: String,
)

enum class SegmentKind {
    DIALOGUE,
    NARRATION,
    ACTION,
    ;

    val isDialogue: Boolean get() = this == DIALOGUE
}

/**
 * Splits a character reply into dialogue / narration / action.
 *
 * The model is a language generator, so its output mixes spoken lines with stage
 * direction in prose. Rendering that as one grey block is what makes a chat screen
 * look like a log file. This parser is purely presentational: nothing it produces
 * can change world state.
 *
 * Rules (deliberately conservative, so prose is never lost):
 *  * text inside double quotes is dialogue;
 *  * text inside asterisks or parentheses is action;
 *  * everything else is narration.
 */
object NarrativeSegments {

    fun parse(text: String): List<NarrativeSegment> {
        if (text.isBlank()) return emptyList()
        val segments = mutableListOf<NarrativeSegment>()
        val buffer = StringBuilder()
        var index = 0

        fun flush() {
            val value = buffer.toString().trim()
            if (value.isNotEmpty()) segments += NarrativeSegment(SegmentKind.NARRATION, value)
            buffer.clear()
        }

        while (index < text.length) {
            val char = text[index]
            when (char) {
                '"' -> {
                    flush()
                    val end = text.indexOf('"', index + 1)
                    if (end < 0) {
                        buffer.append(text.substring(index))
                        index = text.length
                    } else {
                        val spoken = text.substring(index + 1, end).trim()
                        if (spoken.isNotEmpty()) {
                            segments += NarrativeSegment(SegmentKind.DIALOGUE, spoken)
                        }
                        index = end + 1
                    }
                }
                '*', '(', '[' -> {
                    val closing = when (char) {
                        '*' -> '*'
                        '(' -> ')'
                        else -> ']'
                    }
                    val end = text.indexOf(closing, index + 1)
                    if (end < 0) {
                        buffer.append(char)
                        index++
                    } else {
                        flush()
                        val action = text.substring(index + 1, end).trim()
                        if (action.isNotEmpty()) {
                            segments += NarrativeSegment(SegmentKind.ACTION, action)
                        }
                        index = end + 1
                    }
                }
                else -> {
                    buffer.append(char)
                    index++
                }
            }
        }
        flush()
        return segments
    }

    /** True when the reply is only narration (no spoken line at all). */
    fun isNarrationOnly(text: String): Boolean =
        parse(text).none { it.kind != SegmentKind.NARRATION }
}

/** Pluralisation that does not need a resource system. */
fun plural(count: Int, singular: String, plural: String = singular + "s"): String =
    if (count == 1) "$count $singular" else "$count $plural"

/** A short, non-technical label for a character's current activity. */
fun activityLabel(activity: dev.charaly.runtime.domain.CharacterActivity): String = when (activity) {
    dev.charaly.runtime.domain.CharacterActivity.IDLE -> "Idle"
    dev.charaly.runtime.domain.CharacterActivity.WORKING -> "Working"
    dev.charaly.runtime.domain.CharacterActivity.RESTING -> "Resting"
    dev.charaly.runtime.domain.CharacterActivity.TRAVELLING -> "On the move"
    dev.charaly.runtime.domain.CharacterActivity.TALKING -> "Talking"
    dev.charaly.runtime.domain.CharacterActivity.INVESTIGATING -> "Investigating"
    dev.charaly.runtime.domain.CharacterActivity.FLEEING -> "In danger"
    dev.charaly.runtime.domain.CharacterActivity.UNKNOWN -> "Unknown"
}

/** Clamp used by layouts that must never divide by zero. */
internal fun safeFraction(part: Int, whole: Int): Float =
    if (whole <= 0) 0f else (part.toFloat() / whole).coerceIn(0f, 1f)

internal fun clampInt(value: Int, min: Int, max: Int): Int = max(min, minOf(value, max))

internal fun magnitude(a: Int, b: Int): Int = abs(a - b)