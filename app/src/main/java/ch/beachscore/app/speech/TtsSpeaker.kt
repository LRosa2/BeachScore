package ch.beachscore.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ch.beachscore.app.score.Language
import ch.beachscore.app.voice.EchoGuard
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
class TtsSpeaker(context: Context, private val echoGuard: EchoGuard? = null) : Speaker {

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
        // Tells the voice input when our own announcement plays, so it is not taken as a command.
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) { echoGuard?.utteranceFinished(utteranceId) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { echoGuard?.utteranceFinished(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { echoGuard?.utteranceFinished(utteranceId) }
            override fun onError(utteranceId: String, errorCode: Int) { echoGuard?.utteranceFinished(utteranceId) }
        })
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
        val id = "beachscore-${utteranceId++}"
        // Mute from the request on, not from onStart: the first syllable must not slip through.
        echoGuard?.utteranceStarted(id)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) echoGuard?.utteranceFinished(id)
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
