package ch.beachscore.app.voice

/**
 * A Vosk final result: the recognised text and, with `setWords(true)`, each word with its
 * confidence. [parse] reads the recogniser's JSON with a few regular expressions (no JSON
 * library, so it runs unchanged in JVM tests); the format is fixed by Vosk. Braces and brackets
 * are always escaped: Android's ICU regex engine rejects a bare `}` that Java accepts.
 */
data class VoskResult(val text: String, val words: List<Word>) {
    data class Word(val word: String, val confidence: Double)

    companion object {
        private val PARTIAL = Regex(""""partial"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val TEXT = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val RESULT_ARRAY = Regex(""""result"\s*:\s*\[(.*?)\]\s*[,\}]""", RegexOption.DOT_MATCHES_ALL) // not the ] of "[unk]"
        private val OBJECT = Regex("""\{([^\{\}]*)\}""")
        private val WORD = Regex(""""word"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val CONF = Regex(""""conf"\s*:\s*([0-9.eE+-]+)""")

        fun parse(json: String): VoskResult? {
            val text = TEXT.find(json)?.groupValues?.get(1)?.let(::unescape) ?: return null
            val words = RESULT_ARRAY.find(json)?.groupValues?.get(1)?.let { array ->
                OBJECT.findAll(array).mapNotNull { obj ->
                    val body = obj.groupValues[1]
                    val word = WORD.find(body)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
                    val conf = CONF.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
                    Word(word, conf)
                }.toList()
            }.orEmpty()
            return VoskResult(text.trim(), words)
        }

        /** The text of a partial result (`{"partial" : "…"}`), or null if it is not one. */
        fun parsePartial(json: String): String? = PARTIAL.find(json)?.groupValues?.get(1)?.let(::unescape)?.trim()

        private fun unescape(s: String): String {
            if ('\\' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'u' -> if (i + 5 < s.length) {
                            out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6; continue
                        }
                        'n' -> { out.append('\n'); i += 2; continue }
                        't' -> { out.append('\t'); i += 2; continue }
                        else -> { out.append(n); i += 2; continue }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }
    }
}
