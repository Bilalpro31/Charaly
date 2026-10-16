package dev.charaly.runtime.context

import dev.charaly.runtime.domain.CharacterId
import dev.charaly.runtime.domain.Scene
import dev.charaly.runtime.domain.StoryInstanceId
import dev.charaly.runtime.domain.StoryPack
import dev.charaly.runtime.domain.TranscriptEntry
import dev.charaly.runtime.domain.TranscriptRole
import dev.charaly.runtime.domain.WorldDefinition
import dev.charaly.runtime.engine.SampleWorlds
import dev.charaly.runtime.engine.StoryInstanceFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 10 A — "the character answers in the language the player used".
 *
 * ## What is actually being defended
 *
 * Charaly's prompts are written in English: the world rules, the action protocol, every
 * pack's character sheet, the scene description. For a long time the *only* thing telling
 * the model to answer a Turkish player in Turkish was one English sentence in the middle
 * of that. A 4B model reads two thousand characters of English and one sentence, and
 * follows the two thousand.
 *
 * These tests hold the fix in place at the level where it can break:
 *
 *  - the language is *decided*, not suggested (pure detector tests);
 *  - the decision survives a short or empty follow-up turn (stickiness);
 *  - the decision reaches the model *first*, and again at the end;
 *  - an English persona does not get a vote (the block precedes the identity sheet);
 *  - the lock costs a bounded amount of text and does not grow with the conversation.
 *
 * None of these proves the model obeys. That needs weights, and
 * `Phase10TurkishLatencyTest` runs it against Qwen3-4B on the real chain. What they prove
 * is that the instruction the weights obey is actually being sent, and sent first.
 */
class ReplyLanguageTest {

    private val pack: StoryPack = SampleWorlds.libraryPack()
    private val definition = WorldDefinition(pack.characters, pack.locations)
    private val builder = ContextBuilder(definition)
    private val alice = SampleWorlds.alice.id
    private val library = SampleWorlds.library.id

    private fun scene() = Scene(
        id = dev.charaly.runtime.domain.SceneId("scene-test"),
        locationId = library,
        participants = listOf(alice),
        focusCharacterId = alice,
    )

    private fun instanceWith(vararg playerLines: String): dev.charaly.runtime.domain.StoryInstance {
        var instance = StoryInstanceFactory.create(pack, StoryInstanceId("s1"))
        playerLines.forEachIndexed { index, line ->
            val turn = index + 1
            instance = instance.evolved(
                conversation = instance.conversation
                    .append(
                        TranscriptEntry(
                            id = "u$turn",
                            turn = turn,
                            role = TranscriptRole.USER,
                            text = line,
                            at = dev.charaly.runtime.domain.StoryTime.START,
                            sceneId = dev.charaly.runtime.domain.SceneId("scene-test"),
                        ),
                    )
                    .append(
                        TranscriptEntry(
                            id = "c$turn",
                            turn = turn,
                            role = TranscriptRole.CHARACTER,
                            text = "Tabii.",
                            at = dev.charaly.runtime.domain.StoryTime.START,
                            sceneId = dev.charaly.runtime.domain.SceneId("scene-test"),
                            speakerId = alice,
                        ),
                    ),
            )
        }
        return instance
    }

    // ------------------------------------------------------------------
    // the detector
    // ------------------------------------------------------------------

    @Test
    fun `the brief's four messages are all Turkish`() {
        val script = listOf(
            "Merhaba, nasılsın?",
            "Bugün okulda biraz endişeliyim.",
            "Sence arkadaşlarımla nasıl konuşmalıyım?",
            "Peki yarın ne yapabilirim?",
        )
        script.forEach { line ->
            assertEquals(
                "\"$line\" must be recognised as Turkish",
                ReplyLanguage.TURKISH,
                ReplyLanguage.of(line),
            )
        }
    }

    @Test
    fun `a Turkish line with no diacritics at all is still Turkish`() {
        // "Merhaba, sen nasilsin?" is what a phone keyboard with the wrong layout, or a
        // player who cannot type Turkish letters, produces. It has a Turkish function word
        // and nothing English, and calling it UNKNOWN would let the default decide by
        // accident rather than by evidence.
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.of("Merhaba, sen nasilsin?"))
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.of("Tamam, sonra konusuruz"))
    }

    @Test
    fun `an English line is recognised as English`() {
        assertEquals(ReplyLanguage.ENGLISH, ReplyLanguage.of("Hi, how are you today?"))
        assertEquals(ReplyLanguage.ENGLISH, ReplyLanguage.of("I am a little worried about school."))
    }

    @Test
    fun `a single English word does not take the conversation away from Turkish`() {
        // The most common way a Turkish player writes: one English word mid-sentence. If
        // this flipped the language, the model would keep answering in English for the rest
        // of the conversation, which is the exact failure Phase 10 exists to end.
        assertEquals(ReplyLanguage.UNKNOWN, ReplyLanguage.of("a question"))
        assertEquals(ReplyLanguage.UNKNOWN, ReplyLanguage.of("ok"))
    }

    @Test
    fun `an explicit request overrides the scoring, in both directions`() {
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.of("Lütfen bundan sonra Türkçe yaz."))
        assertEquals(ReplyLanguage.ENGLISH, ReplyLanguage.of("Please answer in English from now on."))
        // The override is specific on purpose: mentioning English is not asking for it.
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.of("İngilizce biliyorum ama Türkçe konuşalım."))
    }

    @Test
    fun `a turn with no signal inherits the last decided player line`() {
        val earlier = listOf("Merhaba, bugün okulda biraz endişeliyim.")
        // "Peki yarın ne yapabilirim?" decides on its own.
        assertEquals(
            ReplyLanguage.TURKISH,
            ReplyLanguage.forTurn("Peki yarın ne yapabilirim?", earlier),
        )
        // "Tamam." decides nothing and must not lose Turkish.
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.forTurn("Tamam.", earlier))
        // The empty line behind "continue" decides nothing either.
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.forTurn("", earlier))
        // An English conversation stays English across the same turns.
        assertEquals(
            ReplyLanguage.ENGLISH,
            ReplyLanguage.forTurn("Okay.", listOf("Hi, how are you today?")),
        )
    }

    @Test
    fun `the newest decided player line wins`() {
        val lines = listOf("Merhaba, nasılsın?", "Hi, how are you today?")
        assertEquals(ReplyLanguage.ENGLISH, ReplyLanguage.forTurn("", lines))
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.forTurn("", lines.reversed()))
    }

    @Test
    fun `an undecided first turn still answers in the product language`() {
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.forTurn(""))
        assertEquals(ReplyLanguage.TURKISH, ReplyLanguage.forTurn("hm"))
    }

    // ------------------------------------------------------------------
    // what actually reaches the model
    // ------------------------------------------------------------------

    @Test
    fun `the language block is the FIRST thing the model reads`() {
        val context = builder.buildContext(instanceWith(), scene(), alice, "Merhaba, nasılsın?")
        val stable = context.systemPromptStable()
        val languageAt = stable.indexOf("LANGUAGE")
        val identityAt = stable.indexOf("YOUR IDENTITY")
        val rulesAt = stable.indexOf("WORLD RULES")
        assertTrue("the prompt must carry a language block", languageAt >= 0)
        assertTrue("the language block must precede the world rules ($languageAt vs $rulesAt)", languageAt < rulesAt)
        assertTrue("the language block must precede the identity sheet ($languageAt vs $identityAt)", languageAt < identityAt)
    }

    @Test
    fun `the block is written in the language it is asking for`() {
        val context = builder.buildContext(instanceWith(), scene(), alice, "Merhaba, nasılsın?")
        val stable = context.systemPromptStable()
        val block = stable.substring(0, stable.indexOf("WORLD RULES"))
        assertTrue(
            "a rule asked for in Turkish is a stronger instruction than the same rule in English",
            block.contains("Türkçe") || block.contains("TURKISH"),
        )
        assertFalse("the Turkish block must not tell the model to answer in English", block.contains("answer in ENGLISH"))
    }

    @Test
    fun `the block names the English-notes trap explicitly`() {
        val context = builder.buildContext(instanceWith(), scene(), alice, "Merhaba, nasılsın?")
        val block = context.replyLanguage.promptBlock("Marinette")
        assertTrue(
            "an English persona sheet must be told apart from the speaking language",
            block.contains("written in English") && block.contains("WRITTEN"),
        )
    }

    @Test
    fun `the instruction is repeated once at the very end of the prompt`() {
        val context = builder.buildContext(instanceWith(), scene(), alice, "Merhaba, nasılsın?")
        val volatile = context.systemPromptVolatile()
        assertTrue(
            "recency is the cheapest attention there is",
            volatile.trimEnd().endsWith("Reply in Turkish."),
        )
    }

    @Test
    fun `an English player gets the English block and the English reminder`() {
        val context = builder.buildContext(instanceWith(), scene(), alice, "Hi, how are you today?")
        assertEquals(ReplyLanguage.ENGLISH, context.replyLanguage)
        assertTrue(context.systemPromptVolatile().trimEnd().endsWith("Reply in English."))
        assertFalse(context.systemPromptStable().contains("Reply in Turkish."))
    }

    @Test
    fun `the language block reaches the rendered request exactly once`() {
        val instance = instanceWith("Merhaba, nasılsın?", "Bugün okulda biraz endişeliyim.")
        val request = builder.buildRequest(instance, scene(), alice, "Peki yarın ne yapabilirim?")
        val occurrences = Regex("LANGUAGE - ").findAll(request.systemPrompt).count()
        assertEquals("the block must not be repeated per turn or per message", 1, occurrences)
        // ...and it must not leak into the transcript either.
        assertFalse(request.messages.any { it.content.contains("LANGUAGE - ") })
    }

    @Test
    fun `the lock costs a bounded amount of text and does not grow with the conversation`() {
        val one = builder.buildRequest(instanceWith(), scene(), alice, "Merhaba, nasılsın?")
        val many = builder.buildRequest(
            instanceWith(
                "Merhaba, nasılsın?",
                "Bugün okulda biraz endişeliyim.",
                "Sence arkadaşlarımla nasıl konuşmalıyım?",
                "Peki yarın ne yapabilirim?",
                "Teşekkür ederim.",
                "Görüşürüz.",
            ),
            scene(),
            alice,
            "Peki yarın ne yapabilirim?",
        )
        val growth = many.systemPrompt.length - one.systemPrompt.length
        // The language decision is taken from the transcript that is ALREADY in the prompt,
        // so a longer conversation must not add a second copy of it. Everything else in the
        // growth is the transcript itself.
        assertTrue(
            "the language lock must not be re-sent per turn (system block grew by $growth chars)",
            growth < 400,
        )
    }

    @Test
    fun `the language block is shorter than the English world rules it outranks`() {
        // Not a quality judgement, a budget one: a rule that costs more than the thing it
        // overrides is a rule that will get trimmed away first.
        val context = builder.buildContext(instanceWith(), scene(), alice, "Merhaba, nasılsın?")
        val block = context.replyLanguage.promptBlock("Alice")
        assertTrue("language block = ${block.length} chars", block.length < 1200)
    }

    @Test
    fun `a hand-built context with no decision still speaks Turkish`() {
        val base = builder.buildContext(instanceWith(), scene(), alice, "Merhaba")
        val decided = base.copy(replyLanguage = ReplyLanguage.UNKNOWN)
        assertFalse(
            "an undecided context must not open with a language block",
            decided.systemPromptStable().startsWith("LANGUAGE"),
        )
        val defaulted = base.copy(replyLanguage = ReplyLanguage.DEFAULT)
        assertTrue(defaulted.systemPromptStable().contains("TURKISH"))
    }

    @Test
    fun `every turn of a Turkish conversation is Turkish, including the ones with no signal`() {
        var instance = instanceWith()
        val lines = listOf(
            "Merhaba, nasılsın?",
            "Bugün okulda biraz endişeliyim.",
            "Sence arkadaşlarımla nasıl konuşmalıyım?",
            "Peki yarın ne yapabilirim?",
            "",
            "Tamam.",
        )
        lines.forEach { line ->
            val context = builder.buildContext(instance, scene(), alice, line)
            assertEquals("\"$line\"", ReplyLanguage.TURKISH, context.replyLanguage)
            assertTrue("\"$line\"", context.systemPromptStable().contains("TURKISH"))
            instance = instance.evolved(
                conversation = instance.conversation.append(
                    TranscriptEntry(
                        id = "u${instance.conversation.size}",
                        turn = instance.conversation.nextTurn(),
                        role = TranscriptRole.USER,
                        text = line.ifBlank { "(continued)" },
                        at = dev.charaly.runtime.domain.StoryTime.START,
                        sceneId = dev.charaly.runtime.domain.SceneId("scene-test"),
                    ),
                ),
            )
        }
    }
}
