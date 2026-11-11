package ch.beachscore.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import ch.beachscore.app.input.ScoreCommand
import ch.beachscore.app.input.ScoreInput
import ch.beachscore.app.score.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model

/**
 * Continuous offline command recognition: microphone → echo gate → Vosk (closed grammar) →
 * [CommandParser]. Owns the model, the recogniser and the audio capture; the view model only
 * starts and stops it and acts on [heard].
 *
 * Audio source: [MediaRecorder.AudioSource.VOICE_RECOGNITION]. Android's CDD requires it to be
 * tuned for speech recognition: flat frequency response, no automatic gain control and noise
 * suppression off by default. That is what Vosk's models expect (they are trained on
 * unprocessed speech), and it behaves the same across phones. The default `MIC` source is
 * vendor-tuned for calls/recording, often with AGC and noise suppression that pump the level
 * and smear distant speech. For far-field pickup the unprocessed source plus Vosk's own
 * feature normalisation is the more predictable choice.
 */
class VoiceInput(
    context: Context,
    private val modelStore: ModelStore,
    private val echoGuard: EchoGuard,
    private val scope: CoroutineScope,
) : VoiceControl, ScoreInput {

    private val appContext = context.applicationContext

    private val _heard = MutableSharedFlow<Heard>(extraBufferCapacity = 16)
    override val heard: Flow<Heard> = _heard.asSharedFlow()
    override val commands: Flow<ScoreCommand> = heard.mapNotNull { (it.verdict as? Verdict.Accepted)?.command }

    private val _status = MutableStateFlow<VoiceStatus>(VoiceStatus.Off)
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var config: Pair<Language, VoiceSensitivity>? = null

    /** One loaded model at a time (the active language); kept across stop/start for a quick resume. */
    private var loaded: Pair<Language, Model>? = null

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
    }

    @Synchronized
    override fun start(language: Language, sensitivity: VoiceSensitivity) {
        if (config == language to sensitivity && job?.isActive == true) return
        config = language to sensitivity
        val previous = job
        job = scope.launch(Dispatchers.Default) {
            previous?.cancelAndJoin() // the microphone must be released before it is reopened
            listen(language, sensitivity)
        }
    }

    @Synchronized
    override fun stop() {
        config = null
        job?.cancel()
        job = null
        _status.value = VoiceStatus.Off
    }

    private suspend fun listen(language: Language, sensitivity: VoiceSensitivity) {
        val model = try {
            loadModel(language)
        } catch (e: Exception) {
            Log.e(TAG, "speech model unavailable", e)
            _status.value = VoiceStatus.Failed(VoiceStatus.Failed.Reason.MODEL)
            return
        }
        if (!currentCoroutineContext().isActive) return

        val record = openMicrophone() ?: run {
            _status.value = VoiceStatus.Failed(VoiceStatus.Failed.Reason.MICROPHONE)
            return
        }
        try {
            CommandRecognizer(model, language, sensitivity.minConfidence).use { recognizer ->
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    _status.value = VoiceStatus.Failed(VoiceStatus.Failed.Reason.MICROPHONE)
                    return
                }
                val gate = EchoGate(echoGuard)
                val buffer = ShortArray(CommandRecognizer.SAMPLE_RATE / 10) // 100 ms
                _status.value = VoiceStatus.Listening
                while (currentCoroutineContext().isActive) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        Log.e(TAG, "AudioRecord.read failed: $n")
                        _status.value = VoiceStatus.Failed(VoiceStatus.Failed.Reason.MICROPHONE)
                        return
                    }
                    if (gate.beforeBuffer() == EchoGate.Action.RESET_THEN_FEED) recognizer.reset()
                    _status.value = if (gate.muted) VoiceStatus.Muted else VoiceStatus.Listening
                    if (n == 0) continue
                    val heard = recognizer.accept(buffer, n) ?: continue
                    val result = if (gate.discardResults()) {
                        // Our own announcement. Only worth showing if it looked like a command.
                        if (heard.verdict is Verdict.Rejected) continue
                        heard.copy(verdict = Verdict.Rejected(RejectReason.ECHO_GUARD))
                    } else {
                        heard
                    }
                    Log.i(TAG, "heard \"${result.text}\" min=${result.minConfidence} -> ${result.verdict}")
                    _heard.emit(result)
                }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    @Synchronized
    private fun loadModel(language: Language): Model {
        loaded?.let { (lang, model) -> if (lang == language) return model else model.close() }
        loaded = null
        val dir = modelStore.ensure(language) { progress -> _status.value = VoiceStatus.Preparing(progress) }
        return Model(dir.absolutePath).also { loaded = language to it }
    }

    private fun openMicrophone(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val rate = CommandRecognizer.SAMPLE_RATE
        val minBytes = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) return null
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBytes, rate), // at least 0.5 s of 16-bit audio
            ).takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: SecurityException) {
            Log.e(TAG, "microphone permission missing", e)
            null
        }
    }

    private companion object {
        const val TAG = "BeachScoreVoice"
    }
}
