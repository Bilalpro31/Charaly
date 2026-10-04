package dev.charaly.runtime.domain

import kotlinx.serialization.Serializable

/**
 * Conversation transcript.
 *
 * The transcript is NOT world state. It records what was said (by the user, by
 * a character, by narration). Reality is defined by the deterministic runtime and
 * its event log. Keeping the two apart is what prevents "the model said it, so
 * it must be true".
 */
@Serializable
data class ConversationHistory(
    val entries: List<TranscriptEntry> = emptyList(),
) {
    val size: Int get() = entries.size

    fun append(entry: TranscriptEntry): ConversationHistory =
        copy(entries = entries + entry)

    /**
     * The most recent [limit] *turns*, not entries.
     *
     * A turn is one exchange, so a caller asking for 6 turns gets the last 6
     * user/assistant pairs rather than an arbitrary 6 lines.
     */
    fun lastTurns(turns: Int): List<TranscriptEntry> {
        if (turns <= 0 || entries.isEmpty()) return emptyList()
        val cutoff = entries.maxOf { it.turn } - turns + 1
        return entries.filter { it.turn >= cutoff }
    }

    fun last(limit: Int = 1): List<TranscriptEntry> =
        if (limit <= 0) emptyList() else entries.takeLast(limit)

    fun sinceTurn(turn: Int): List<TranscriptEntry> = entries.filter { it.turn >= turn }

    fun forScene(sceneId: SceneId): List<TranscriptEntry> = entries.filter { it.sceneId == sceneId }

    fun nextTurn(): Int = (entries.maxOfOrNull { it.turn } ?: 0) + 1

    fun characterTurnCount(characterId: CharacterId): Int = entries.count { it.speakerId == characterId }

    companion object {
        val EMPTY = ConversationHistory()
    }
}

@Serializable
data class TranscriptEntry(
    val id: String,
    val turn: Int,
    val role: TranscriptRole,
    val text: String,
    val at: StoryTime,
    val sceneId: SceneId? = null,
    /** Which character produced this line (assistant) or is being addressed. */
    val speakerId: CharacterId? = null,
    /** Narration vs dialogue: the model must not be able to fake a system line. */
    val style: TranscriptStyle = TranscriptStyle.DIALOGUE,
) {
    init {
        require(text.isNotBlank()) { "Transcript entries cannot be blank ($id)" }
    }
}

@Serializable
enum class TranscriptRole {
    USER,
    CHARACTER,
    NARRATION,
    SYSTEM,
}

@Serializable
enum class TranscriptStyle {
    DIALOGUE,
    NARRATION,
    ACTION,
}
