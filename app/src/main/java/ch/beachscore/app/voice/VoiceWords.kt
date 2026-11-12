package ch.beachscore.app.voice

import ch.beachscore.app.score.Language

/** The three configurable fields of the voice words (undo is fixed). */
enum class WordField { BLUE, RED, POINT }

/**
 * The user's words for one language, as stored. An empty list means "the defaults" for that
 * field; a filled one replaces them.
 */
data class CustomWords(
    val blue: List<String> = emptyList(),
    val red: List<String> = emptyList(),
    val point: List<String> = emptyList(),
) {
    operator fun get(field: WordField): List<String> = when (field) {
        WordField.BLUE -> blue
        WordField.RED -> red
        WordField.POINT -> point
    }

    fun with(field: WordField, words: List<String>): CustomWords = when (field) {
        WordField.BLUE -> copy(blue = words)
        WordField.RED -> copy(red = words)
        WordField.POINT -> copy(point = words)
    }

    val all: List<String> get() = blue + red + point
    val isEmpty: Boolean get() = all.isEmpty()
}

/**
 * The words actually listened for in one language: the user's fields, or the defaults where a
 * field is empty. [replaced] are default words a filled field took out; they stay in the
 * grammar as decoys (see [CommandParser.grammar]).
 */
data class CommandWords(
    val language: Language,
    val point: List<String>,
    val blue: List<String>,
    val red: List<String>,
    val undo: String,
    val replaced: List<String> = emptyList(),
) {
    val all: List<String> get() = point + blue + red + undo

    companion object {
        private val DEFAULTS = mapOf(
            Language.DE to CustomWords(blue = listOf("blau"), red = listOf("rot"), point = listOf("punkt", "punk")),
            Language.EN to CustomWords(blue = listOf("blue"), red = listOf("red"), point = listOf("point", "points")),
        )
        private val UNDO = mapOf(Language.DE to "zurück", Language.EN to "undo")

        fun defaults(language: Language): CommandWords = of(language, CustomWords())

        fun defaultsOf(language: Language): CustomWords = DEFAULTS.getValue(language)

        fun undoWord(language: Language): String = UNDO.getValue(language)

        fun of(language: Language, custom: CustomWords): CommandWords {
            val defaults = DEFAULTS.getValue(language)
            fun pick(field: WordField) = custom[field].ifEmpty { defaults[field] }
            val replaced = WordField.entries.filter { custom[it].isNotEmpty() }.flatMap { defaults[it] }
            val active = WordField.entries.flatMap(::pick).toSet()
            return CommandWords(
                language = language,
                point = pick(WordField.POINT),
                blue = pick(WordField.BLUE),
                red = pick(WordField.RED),
                undo = UNDO.getValue(language),
                replaced = replaced.filter { it !in active },
            )
        }
    }
}

/** Why a set of voice words cannot be saved. */
sealed interface WordProblem {
    /** An entry that is not exactly one word of letters ("punkt eins", "tor!"). */
    data class NotOneWord(val entry: String) : WordProblem
    /** The fixed undo word. */
    data class UndoWord(val word: String) : WordProblem
    /** A word in two fields (counting the defaults of empty fields). */
    data class Duplicate(val word: String) : WordProblem
    /** A word the speech model of the language does not know. */
    data class Unknown(val word: String) : WordProblem
    /** The speech model could not be loaded to check the words; nothing was saved. */
    data object ModelUnavailable : WordProblem
}

sealed interface WordsCheck {
    data class Ok(val words: CustomWords) : WordsCheck
    data class Failed(val problem: WordProblem) : WordsCheck
}

/** Parsing, checking and storing the voice-word fields. Pure. */
object VoiceWords {

    private val WORD = Regex("[\\p{L}]+")

    /**
     * Checks the field texts of one language (comma separated, any case). Everything except
     * the vocabulary of the speech model, which needs the model ([WordProblem.Unknown]).
     */
    fun check(language: Language, fields: Map<WordField, String>): WordsCheck {
        var custom = CustomWords()
        for (field in WordField.entries) {
            val entries = split(fields[field].orEmpty())
            entries.firstOrNull { !it.matches(WORD) }?.let { return WordsCheck.Failed(WordProblem.NotOneWord(it)) }
            custom = custom.with(field, entries.distinct())
        }
        val undo = CommandWords.undoWord(language)
        custom.all.firstOrNull { it == undo }?.let { return WordsCheck.Failed(WordProblem.UndoWord(it)) }
        val defaults = CommandWords.defaultsOf(language)
        val effective = WordField.entries.flatMap { custom[it].ifEmpty { defaults[it] } }
        effective.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
            ?.let { return WordsCheck.Failed(WordProblem.Duplicate(it.key)) }
        return WordsCheck.Ok(custom)
    }

    /** Stored form: words joined by commas. */
    fun encode(words: List<String>): String = words.joinToString(",")

    /** Reads a stored value; tolerant of spaces, case and empty entries. */
    fun decode(value: String?): List<String> = split(value.orEmpty()).filter { it.matches(WORD) }.distinct()

    /** The text shown in a field for editing. */
    fun display(words: List<String>): String = words.joinToString(", ")

    private fun split(text: String): List<String> =
        text.split(',').map { it.trim().replace(Regex("\\s+"), " ").lowercase() }.filter { it.isNotEmpty() }
}
