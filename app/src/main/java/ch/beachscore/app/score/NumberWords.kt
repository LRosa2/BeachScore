package ch.beachscore.app.score

/**
 * Scores as words, so the TTS engine always says "zero"/"null" and never reads a digit
 * oddly (e.g. as an ordinal or a year). Covers 0..99, which no beach set ever leaves;
 * anything larger falls back to digits.
 */
object NumberWords {

    private val EN_SMALL = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val EN_TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private val DE_SMALL = listOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn",
        "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )

    /** Units as they appear in front of "und…": "ein", not "eins". */
    private val DE_UNITS = listOf("", "ein", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun")
    private val DE_TENS = listOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    fun of(n: Int, language: Language): String {
        if (n !in 0..99) return n.toString()
        return when (language) {
            Language.EN -> english(n)
            Language.DE -> german(n)
        }
    }

    private fun english(n: Int): String {
        if (n < 20) return EN_SMALL[n]
        val tens = EN_TENS[n / 10]
        return if (n % 10 == 0) tens else "$tens-${EN_SMALL[n % 10]}"
    }

    private fun german(n: Int): String {
        if (n < 20) return DE_SMALL[n]
        val tens = DE_TENS[n / 10]
        return if (n % 10 == 0) tens else "${DE_UNITS[n % 10]}und$tens"
    }
}
