package ch.beachscore.app.voice

import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.score.Language
import ch.beachscore.app.score.Team

/**
 * Turns one Vosk final result into a command or a rejection reason. Pure; no Android.
 *
 * Rules (spec "False-trigger protection"):
 *  - A command is *spotted* anywhere in the result: the point word and a team word next to
 *    each other in either order ("punkt blau" / "blau punkt"), or the undo word. Other words
 *    and `[unk]` around it do not matter; command words that are not adjacent are not a command.
 *  - Only the command words must reach the confidence threshold.
 *  - The same command several times counts once; two different commands give none.
 *  - Undo counts only with nothing but `[unk]` (noise) around it: the word is common in
 *    ordinary sentences ("zurück zum Auto") and is then heard with full confidence.
 *  - A point counts only with at most [MAX_WORDS_BEFORE_POINT] other words before it in the
 *    same result: a command phrase after a long stretch of talk is talk.
 *  Both give [RejectReason.IN_SPEECH]. Measured with VoiceSpottingTest.
 */
object CommandParser {

    const val UNKNOWN_WORD = "[unk]"

    /** See the class comment. With 4, chatter gave a command in 4 of 144 streams (3.1.0: 20). */
    const val MAX_WORDS_BEFORE_POINT = 4

    /** A command found in a token list: tokens [first]..[last]. */
    data class Match(val command: ScoreCommand, val first: Int, val last: Int)

    /**
     * Ordinary words the recogniser may hear instead of a command: frequent words, court words,
     * numbers, and words that sound like the command words (but no exact homophones such as
     * "read"/"red", which would take real commands). With only the commands and `[unk]` in
     * the grammar, chatter was heard as command words with full confidence ("zurück" 1.00);
     * with these it is heard as what it is. Words missing from a model are ignored by Vosk.
     */
    private val DECOYS = mapOf(
        Language.DE to """
            der die das den dem des ein eine einen einem einer und oder aber doch nicht kein keine
            ich du er sie es wir ihr mich dich sich mir dir uns ist sind war waren hat haben hatte
            wird werden kann können muss soll will mal noch schon jetzt dann da hier dort so wie was
            wer wo wann warum ja nein gut sehr auch nur mehr alles zu zum zur von vom mit für auf aus
            an am im in bei nach über unter vor gegen ohne um bis los weiter genau okay super schön
            toll klar gleich ball netz satz spiel spieler team mannschaft seite aufschlag annahme
            angriff block linie feld sand wind sonne wasser auto pause zeit stand führt führung
            null eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf dreizehn vierzehn
            fünfzehn sechzehn siebzehn achtzehn neunzehn zwanzig
            punkte punkten bunt blatt blaue blauen braun rote roten rotes brot boot tot not
            rock rücken brücke glück stück drück zucker zug zurecht zuerst
        """,
        Language.EN to """
            the a an and or but not no yes i you he she it we they me him her us is are was were has
            have had will would can could must should does did to of in on at by for from with about
            out up down over again here there now then so just very good great nice well okay right
            left what who where when why how this that these those all more one two three four five
            six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen
            eighteen nineteen twenty ball net set game serve team side line sand wind sun water car
            break time score lead leads
            pointed pint paint joint appoint glue blues blow bluff bed head said rid road under
            window undone new do
        """,
    ).mapValues { (_, words) -> words.trim().split(Regex("\\s+")) }

    /**
     * The closed grammar for the recogniser: every point word with every team word in both
     * orders, undo, the decoys and `[unk]`. Default words a user field replaced become decoys
     * (heard as themselves rather than bent into the new words); the active words never are.
     */
    fun grammar(words: CommandWords): List<String> {
        val commands = buildList {
            for (p in words.point) for (t in words.blue + words.red) {
                add("$p $t")
                add("$t $p")
            }
            add(words.undo)
        }
        val active = words.all.toSet()
        val decoys = (DECOYS.getValue(words.language) + words.replaced).distinct().filter { it !in active }
        return commands + decoys + UNKNOWN_WORD
    }

    fun grammar(language: Language): List<String> = grammar(CommandWords.defaults(language))

    /** The decoy words of [language] with the default words (for tests). */
    fun decoys(language: Language): List<String> = DECOYS.getValue(language)

    fun grammarJson(words: CommandWords): String =
        grammar(words).joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

    fun grammarJson(language: Language): String = grammarJson(CommandWords.defaults(language))

    fun find(tokens: List<String>, language: Language): List<Match> = find(tokens, CommandWords.defaults(language))

    /** Every command in [tokens] (lower case), in order; overlapping pairs are all reported. */
    fun find(tokens: List<String>, words: CommandWords): List<Match> {
        fun team(t: String) = when (t) {
            in words.blue -> Team.BLUE
            in words.red -> Team.RED
            else -> null
        }
        val out = mutableListOf<Match>()
        for (i in tokens.indices) {
            if (tokens[i] == words.undo) out += Match(ScoreCommand.Undo, i, i)
            if (i + 1 < tokens.size) {
                val a = tokens[i]
                val b = tokens[i + 1]
                val t = when {
                    a in words.point -> team(b)
                    b in words.point -> team(a)
                    else -> null
                }
                if (t != null) out += Match(ScoreCommand.TeamPoint(t), i, i + 1)
            }
        }
        return out
    }

    /** Returns null for an empty result (silence), which is not worth showing. */
    fun parse(result: VoskResult, language: Language, minConfidence: Double): Heard? =
        parse(result, CommandWords.defaults(language), minConfidence)

    fun parse(result: VoskResult, words: CommandWords, minConfidence: Double): Heard? {
        val text = result.text.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val tokens = text.lowercase().split(' ')
        val confidences = if (result.words.size == tokens.size) result.words.map { it.confidence } else null
        val allMin = result.words.minOfOrNull { it.confidence }

        val matches = find(tokens, words)
        val commands = matches.map { it.command }.distinct()
        if (commands.isEmpty()) return Heard(text, allMin, Verdict.Rejected(RejectReason.UNKNOWN))
        if (commands.size > 1) return Heard(text, allMin, Verdict.Rejected(RejectReason.MULTIPLE))

        val command = commands.single()
        // The most confident occurrence decides.
        val best = if (confidences == null) matches.first() else matches.maxBy { m -> (m.first..m.last).minOf { confidences[it] } }
        val spotted = tokens.subList(best.first, best.last + 1).joinToString(" ")
        val display = (if (best.first > 0) "… " else "") + spotted + (if (best.last < tokens.size - 1) " …" else "")
        if (confidences == null) return Heard(text, allMin, Verdict.Rejected(RejectReason.LOW_CONFIDENCE), display)
        val confidence = (best.first..best.last).minOf { confidences[it] }
        val covered = matches.flatMap { it.first..it.last }.toSet()
        fun talk(range: IntRange) = range.count { it !in covered && tokens[it] != UNKNOWN_WORD }
        val first = matches.minOf { it.first }
        val inSpeech = if (command == ScoreCommand.Undo) talk(tokens.indices) > 0 else talk(0 until first) > MAX_WORDS_BEFORE_POINT
        val verdict = when {
            confidence < minConfidence -> Verdict.Rejected(RejectReason.LOW_CONFIDENCE)
            inSpeech -> Verdict.Rejected(RejectReason.IN_SPEECH)
            else -> Verdict.Accepted(command)
        }
        return Heard(text, confidence, verdict, display)
    }
}
