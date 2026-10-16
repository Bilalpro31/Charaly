package dev.charaly.runtime.context

import java.util.Locale

/**
 * The language the character must answer in.
 *
 * ## Why this is a value and not a sentence in the prompt
 *
 * "Reply in Turkish when the user writes in Turkish" was, for most of Charaly's life, a
 * single English line buried in the middle of a long English system prompt. That is a
 * *wish*, not a rule: a 4B model reading two thousand characters of English world rules,
 * an English character sheet and an English action protocol will answer in English
 * regardless of one sentence asking otherwise, because the overwhelming prior of the
 * text around it is English.
 *
 * So the decision is lifted out of the prose. The prompt now gets a block that is the
 * *first* thing the model reads, it is written in the language it is asking for, and it
 * names the specific trap - the English notes below are note-taking, not the voice.
 *
 * ## Why detection is sticky
 *
 * A single line rarely decides a conversation. "Peki yarın ne yapabilirim?" is Turkish;
 * "Tamam." carries almost no signal; an empty "continue" turn carries none at all. A
 * detector that answered per turn would flip to the default on exactly the short turns
 * where flipping is most visible. So a turn inherits the most recent *decided* player
 * line, which is what a human speaker does.
 */
enum class ReplyLanguage {

    TURKISH,
    ENGLISH,

    /** No player line has decided a language yet. Resolved to the product default. */
    UNKNOWN,
    ;

    /**
     * The block that goes at the very top of the system prompt.
     *
     * ## Why it is in Turkish
     *
     * A rule asked for in the target language is a stronger instruction than the same
     * rule asked for in English, and measurably so for small models: the English version
     * competes with every other English sentence in the context, while the Turkish one
     * is the only text in that language the model has been given. Cost is bounded - the
     * block sits in the cache-stable prefix, so it is evaluated once per scene and
     * reused by the KV cache every turn after that.
     *
     * ## Why the second bullet exists at all
     *
     * Every pack character is described in English: appearance, personality, background,
     * goals, fears, voice, even the example dialogue. That is an authoring language, not
     * a speaking language, but a model reads "all of this character is written in
     * English" as "this character speaks English". Naming the distinction explicitly -
     * the notes are written in English, the voice is not - is what stops the persona
     * sheet from winning an argument it was never having.
     */
    fun promptBlock(characterName: String): String = when (this) {
        TURKISH ->
            """
            |LANGUAGE - THIS IS THE FIRST RULE, AND IT OVERRIDES EVERYTHING ELSE BELOW
            |- The person you are talking to writes in Turkish. You answer in TURKISH, in every sentence.
            |- Spell it the way a Turkish speaker spells it: ç ğ ı İ ö ş ü - not c g i I o s u.
            |- Everything below this block - your notes, the world rules, the memories, the scene, the action
            |  protocol - is written in English because it is the author's own note-taking. That is the
            |  language those notes are WRITTEN in. It is never the language you SPEAK.
            |- Never reply in English. No English sentence, no English greeting, no English summary, and no
            |  line that explains yourself in English.
            |- Answer what the person just said, in Turkish, in ${characterName}'s own voice.
            """.trimMargin()

        ENGLISH ->
            """
            |LANGUAGE - THIS IS THE FIRST RULE, AND IT OVERRIDES EVERYTHING ELSE BELOW
            |- The person you are talking to writes in English. You answer in ENGLISH, in every sentence.
            |- The world rules, the action protocol and the scene facts below are written in English, and the
            |  notes about you are the author's own note-taking - neither of those is a licence to change
            |  how you speak, and the person sets the language, not the paperwork.
            |- Answer what the person just said, in English, in ${characterName}'s own voice.
            """.trimMargin()

        UNKNOWN -> ""
    }

    /**
     * The one-line reminder at the end of the prompt.
     *
     * Recency is the cheapest attention there is. The block above is the strongest
     * instruction and the most likely to be forgotten by the time the model reaches the
     * actual question, so the same instruction is repeated as five words immediately
     * before the model's turn opens. Five words cost nothing; a Turkish player reading an
     * English answer costs everything.
     */
    fun closingReminder(): String = when (this) {
        TURKISH -> "Reply in Turkish."
        ENGLISH -> "Reply in English."
        UNKNOWN -> ""
    }

    companion object {

        /**
         * Charaly's product language.
         *
         * The whole interface is Turkish, so a line the detector cannot place is answered
         * in Turkish. This is the choice that makes "the character never switches to
         * English by accident" true rather than merely likely: the player has to *ask*
         * for another language to get one, and asking is explicit by construction.
         */
        val DEFAULT = TURKISH

        /**
         * The language of one player line, or [UNKNOWN] when the line does not decide.
         *
         * Explicit requests win over scoring, in both directions, because "please answer
         * in English" is unambiguous in a way that "I'm fine" is not.
         */
        fun of(line: String): ReplyLanguage {
            val text = line.trim().lowercase(Locale.ROOT)
            if (text.isEmpty()) return UNKNOWN
            if (EXPLICIT_TURKISH.any { it in text }) return TURKISH
            if (EXPLICIT_OTHER_LANGUAGE.any { it in text }) return ENGLISH

            val words = text.split(NON_LETTER).filter { it.isNotBlank() }
            val turkishWords = words.count { TURKISH_WORDS.contains(it) }
            val turkishLetters = TURKISH_ONLY_LETTERS.count { text.indexOf(it) >= 0 }
            // ç, ö, ü are shared with German, French and Hungarian, so they are evidence
            // but not evidence on their own; the dotless ı and the soft ğ are Turkish's
            // own and count double.
            val turkish = turkishWords * 2 + turkishLetters
            val englishWords = words.count { ENGLISH_WORDS.contains(it) }
            val english = englishWords * 2

            return when {
                // Any Turkish evidence at all, as long as English is not clearly ahead.
                // "Nasılsın?" is one word and one diacritic, and it is unambiguously
                // Turkish; refusing to call it Turkish would hand the turn to the default
                // by accident rather than by decision.
                turkish > 0 && turkish >= english -> TURKISH
                // English needs a clear margin, not a single word. "a question" is two
                // English words and no Turkish ones, and it is also the sort of thing a
                // player writes while switching language mid-conversation - so one word
                // must never be enough to move the whole conversation.
                englishWords >= 2 && english >= turkish + ENGLISH_MARGIN -> ENGLISH
                else -> UNKNOWN
            }
        }

        /**
         * How far English must lead Turkish to take the conversation.
         *
         * Four points is two English words. Below that the detector would react to the
         * single most common English token in the language, and a player who types one
         * English word into a Turkish conversation would lose Turkish for the rest of it.
         */
        private const val ENGLISH_MARGIN = 4

        /**
         * The language of this turn: the line's own decision, else the most recent
         * *decided* earlier player line, else the product default.
         *
         * [previousPlayerLines] is walked newest-first. The blank line of a "continue"
         * turn and the two-word acknowledgement both land on the inherited answer, which
         * is the behaviour a Turkish speaker expects and the one a per-turn detector
         * cannot give.
         */
        fun forTurn(line: String, previousPlayerLines: List<String> = emptyList()): ReplyLanguage {
            val own = of(line)
            if (own != UNKNOWN) return own
            previousPlayerLines.asReversed().forEach { earlier ->
                val decided = of(earlier)
                if (decided != UNKNOWN) return decided
            }
            return DEFAULT
        }

        /** Letters Turkish owns outright. ç, ö, ü are deliberately not here. */
        private val TURKISH_ONLY_LETTERS = listOf('ı', 'İ', 'ğ', 'Ğ', 'ş', 'Ş')

        /** Turkish function words: the densest, cheapest signal in a short line. */
        private val TURKISH_WORDS = setOf(
            "merhaba", "selam", "nasılsın", "nasıl", "neden", "ne", "iyi", "kötü", "güzel",
            "ve", "ile", "için", "olarak", "çok", "daha", "bir", "bu", "şu", "ben",
            "sen", "siz", "biz", "onlar", "var", "yok", "değil", "mi", "mı", "mu", "mü",
            "ama", "fakat", "gibi", "her", "şey", "gün", "bugün", "yarın", "dün", "okul",
            "arkadaş", "arkadaşlar", "birlikte", "yap", "yapabilirim", "yapmalıyım",
            "endişeli", "korku", "korkuyorum", "yardım", "teşekkür", "lütfen", "evet",
            "hayır", "tamam", "aslında", "sana", "seni", "beni", "kendini", "bana", "senin",
            "benim", "çünkü", "zaman", "günler", "kafa", "yorgun", "mutlu", "üzgün",
            "tanışalım", "anlatalım", "fikir", "hadi", "konuş", "konuşmalıyım", "dinle",
        )

        /** English function words, weighted the same as the Turkish ones. */
        private val ENGLISH_WORDS = setOf(
            "the", "of", "and", "to", "in", "is", "are", "you", "your", "i", "my", "me",
            "we", "they", "it", "that", "this", "for", "with", "not", "but", "have",
            "will", "can", "would", "should", "could", "was", "were", "be", "do", "if",
            "so", "as", "at", "on", "or", "an", "a", "he", "she", "his", "him", "them",
            "what", "how", "why", "when", "where", "there", "about", "from", "hello",
            "thanks", "sorry", "yes", "okay",
        )

        /**
         * "Speak Turkish", asked for outright.
         *
         * Checked before anything else, so a player who corrects the model into Turkish
         * gets Turkish on that very turn rather than on the next one that scores well.
         */
        private val EXPLICIT_TURKISH = listOf(
            "türkçe yaz", "türkçe konuş", "türkçe cevap", "türkçe devam", "in turkish",
            "speak turkish", "reply in turkish", "answer in turkish", "write in turkish",
        )

        /**
         * "Answer in another language", asked for outright.
         *
         * Deliberately specific. "English" alone is not enough: a Turkish player can
         * legitimately write "İngilizce biliyorum, ama Türkçe konuşalım" and mean
         * Turkish, and a rule that flipped the answer on the mere presence of the word
         * would hand the language back to the model on the first mixed sentence.
         */
        private val EXPLICIT_OTHER_LANGUAGE = listOf(
            "in english", "reply in english", "answer in english", "write in english",
            "speak english", "switch to english", "use english", "ingilizce yaz",
            "ingilizce konuş", "ingilizce cevap", "başka bir dil", "another language",
        )

        /** Everything that is not a letter of either language. */
        private val NON_LETTER = Regex("[^a-zçğıöşü]+")
    }
}
