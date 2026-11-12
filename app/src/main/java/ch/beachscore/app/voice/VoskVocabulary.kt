package ch.beachscore.app.voice

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.vosk.Model

/**
 * Whether a model knows a word. The Java API of vosk-android has no call for it, but the native
 * library exports `vosk_model_find_word` (the same symbol table the grammar is matched against:
 * a grammar word missing from it is silently dropped by Vosk).
 */
internal object VoskVocabulary {
    @Suppress("FunctionName")
    private interface Lib : Library {
        fun vosk_model_find_word(model: Pointer, word: String): Int
    }

    private val lib: Lib by lazy { Native.load("vosk", Lib::class.java) }

    fun knows(model: Model, word: String): Boolean = lib.vosk_model_find_word(model.pointer, word) >= 0
}
