package ch.beachscore.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import ch.beachscore.app.score.Language
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * [Speaker] backed by Android [TextToSpeech] on the media stream. If the engine fails to start
 * or has no voice for the language, everything keeps working silently and [availability]
 * reports UNAVAILABLE so settings can show a notice.
 *
 * Lives as long as the process (see AppContainer), so an activity restart does not re-init TTS.
 */
class TtsSpeaker(context: Context) : Speaker {

    private val _availability = MutableStateFlow(SpeechAvailability.UNKNOWN)
    override val availability: StateFlow<SpeechAvailability> = _availability.asStateFlow()

    @Volatile private var ready = false
    @Volatile private var engineFailed = false
    @Volatile private var wanted: Language? = null
    private var applied: Language? = null
    private var utteranceId = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            wanted?.let(::applyLanguage)
        } else {
            engineFailed = true
            _availability.value = SpeechAvailability.UNAVAILABLE
        }
    }

    init {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    override fun prepare(language: Language) {
        wanted = language
        if (engineFailed) return
        if (ready) applyLanguage(language)
    }

    override fun speak(text: String, language: Language) {
        if (!ready) return
        if (applied != language && !applyLanguage(language)) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "beachscore-${utteranceId++}")
    }

    @Synchronized
    private fun applyLanguage(language: Language): Boolean {
        val result = runCatching { tts.setLanguage(Locale.forLanguageTag(language.tag)) }
            .getOrDefault(TextToSpeech.ERROR)
        val ok = result >= TextToSpeech.LANG_AVAILABLE
        applied = if (ok) language else null
        if (language == wanted) {
            _availability.value = if (ok) SpeechAvailability.AVAILABLE else SpeechAvailability.UNAVAILABLE
        }
        return ok
    }
}
