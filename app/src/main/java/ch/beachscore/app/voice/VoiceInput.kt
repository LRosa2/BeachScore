package ch.beachscore.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Microphone choice: the chosen input (see [Microphones]) is set as the recorder's preferred
 * device. Plugging and unplugging is followed while listening: when the chosen device is gone
 * the recorder is reopened with automatic routing, and when it returns it is used again. A read
 * error (a device vanished under the recorder) reopens the microphone instead of giving up.
 * A device that refuses 16 kHz mono is recorded at one of its own formats and converted
 * ([PcmConverter]). [activeMicrophone] reports the device Android actually routed to.
 */
class VoiceInput(
    context: Context,
    private val modelStore: ModelStore,
    private val echoGuard: EchoGuard,
    private val scope: CoroutineScope,
) : VoiceControl, ScoreInput {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _heard = MutableSharedFlow<Heard>(extraBufferCapacity = 16)
    override val heard: Flow<Heard> = _heard.asSharedFlow()
    override val commands: Flow<ScoreCommand> = heard.mapNotNull { (it.verdict as? Verdict.Accepted)?.command }

    private val _status = MutableStateFlow<VoiceStatus>(VoiceStatus.Off)
    override val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    private val _microphones = MutableStateFlow(inputs())
    override val microphones: StateFlow<List<InputDevice>> = _microphones.asStateFlow()

    private val _active = MutableStateFlow<InputDevice?>(null)
    override val activeMicrophone: StateFlow<InputDevice?> = _active.asStateFlow()

    /** Bumped when the device list or the choice changes; the capture loop then re-checks routing. */
    private val routingChanges = MutableStateFlow(0)

    @Volatile
    private var choice: MicChoice? = null

    private var job: Job? = null
    private data class Config(val language: Language, val minConfidence: Double, val words: CommandWords, val maxPauseMs: Long)

    private var config: Config? = null

    /** One loaded model at a time (the active language); kept across stop/start for a quick resume. */
    private var loaded: Pair<Language, Model>? = null

    /**
     * Guards [loaded] only. Not `this`: loading takes seconds, and [start]/[stop] (main thread)
     * must never wait for it (3.5.1, an app freeze / ANR while the model loaded). Listens stay in
     * order anyway: each one joins the previous job before it loads.
     */
    private val modelLock = Any()

    init {
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = devicesChanged()
            },
            mainHandler,
        )
    }

    private fun devicesChanged() {
        val now = inputs()
        if (now != _microphones.value) Log.i(TAG, "inputs: $now")
        _microphones.value = now
        routingChanges.value++
    }

    @Synchronized
    override fun start(language: Language, minConfidence: Double, microphone: MicChoice?, words: CommandWords, maxPauseMs: Long) {
        if (choice != microphone) {
            choice = microphone
            routingChanges.value++
        }
        val wanted = Config(language, minConfidence, words, maxPauseMs)
        if (config == wanted && job?.isActive == true) return
        config = wanted
        val previous = job
        job = scope.launch(Dispatchers.Default) {
            previous?.cancelAndJoin() // the microphone must be released before it is reopened
            listen(wanted)
        }
    }

    @Synchronized
    override fun stop() {
        config = null
        // Kept (cancelled), so the next start() joins it: never two listens, models or microphones at once.
        job?.cancel()
        _status.value = VoiceStatus.Off
        _active.value = null
    }

    override suspend fun unknownWords(language: Language, words: List<String>): List<String>? = withContext(Dispatchers.IO) {
        // A model of its own: the listening one may be closed or swapped meanwhile.
        try {
            val dir = modelStore.ensure(language)
            Model(dir.absolutePath).use { model -> words.filterNot { VoskVocabulary.knows(model, it) } }
        } catch (e: Exception) {
            Log.e(TAG, "speech model unavailable for the word check", e)
            null
        }
    }

    private suspend fun listen(config: Config) {
        val model = try {
            loadModel(config.language)
        } catch (e: Exception) {
            Log.e(TAG, "speech model unavailable", e)
            _status.value = VoiceStatus.Failed(VoiceStatus.Failed.Reason.MODEL)
            return
        }
        if (!currentCoroutineContext().isActive) return

        CommandRecognizer(model, config.words, config.minConfidence, maxPauseMs = config.maxPauseMs).use { recognizer ->
            val gate = EchoGate(echoGuard)
            var failuresInARow = 0
            try {
                while (currentCoroutineContext().isActive) {
                    val stamp = routingChanges.value
                    val target = preferredDevice()
                    // If the chosen device will not open, automatic routing still lets voice work.
                    val mic = openMicrophone(target) ?: if (target != null) openMicrophone(null) else null
                    if (mic == null) {
                        _status.value = VoiceStatus.Failed(VoiceStatus.Failed.Reason.MICROPHONE)
                        _active.value = null
                        // Try again after a device change, or every few seconds.
                        withTimeoutOrNull(RETRY_MS) { routingChanges.first { it != stamp } }
                        continue
                    }
                    val ended = try {
                        capture(mic, target, stamp, recognizer, gate)
                    } finally {
                        runCatching { mic.record.stop() }
                        mic.record.release()
                    }
                    if (ended == Ended.READ_ERROR) {
                        failuresInARow++
                        if (failuresInARow >= 3) _status.value = VoiceStatus.Failed(VoiceStatus.Failed.Reason.MICROPHONE)
                        delay(if (failuresInARow >= 3) RETRY_MS else 300)
                    } else {
                        failuresInARow = 0
                    }
                    recognizer.reset() // never join half a phrase from one microphone to the next
                }
            } finally {
                _active.value = null
            }
        }
    }

    private enum class Ended { REROUTE, READ_ERROR }

    /** Reads until cancelled (null), a read error, or the preferred device changes. */
    private suspend fun capture(
        mic: Microphone,
        target: AudioDeviceInfo?,
        stamp: Int,
        recognizer: CommandRecognizer,
        gate: EchoGate,
    ): Ended? {
        val record = mic.record
        val routing = AudioRouting.OnRoutingChangedListener { reportRouted(record) }
        record.addOnRoutingChangedListener(routing, mainHandler)
        try {
            val buffer = ShortArray(record.sampleRate / 10 * record.channelCount) // 100 ms
            var seen = stamp
            var reads = 0
            reportRouted(record)
            _status.value = VoiceStatus.Listening
            while (currentCoroutineContext().isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    Log.w(TAG, "AudioRecord.read failed: $n, reopening the microphone")
                    return Ended.READ_ERROR
                }
                if (routingChanges.value != seen) {
                    seen = routingChanges.value
                    if (preferredDevice()?.id != target?.id) {
                        Log.i(TAG, "preferred microphone changed, reopening")
                        return Ended.REROUTE
                    }
                }
                if (++reads % 10 == 0) reportRouted(record)
                if (gate.beforeBuffer() == EchoGate.Action.RESET_THEN_FEED) recognizer.reset()
                _status.value = if (gate.muted) VoiceStatus.Muted else VoiceStatus.Listening
                if (n == 0) continue
                val samples = mic.converter.convert(buffer, n)
                if (samples.isEmpty()) continue
                val heard = recognizer.accept(samples, samples.size) ?: continue
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
            return null
        } finally {
            record.removeOnRoutingChangedListener(routing)
        }
    }

    private fun reportRouted(record: AudioRecord) {
        val routed = record.routedDevice?.toInput() ?: return
        if (_active.value != routed) Log.i(TAG, "microphone in use: $routed")
        _active.value = routed
    }

    private fun loadModel(language: Language): Model = synchronized(modelLock) {
        loaded?.let { (lang, model) -> if (lang == language) return model else model.close() }
        loaded = null
        val dir = modelStore.ensure(language) { progress -> _status.value = VoiceStatus.Preparing(progress) }
        Model(dir.absolutePath).also { loaded = language to it }
    }

    private fun inputs(): List<InputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.toInput() }

    /** The connected device for the current choice, or null for automatic routing. */
    private fun preferredDevice(): AudioDeviceInfo? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val wanted = Microphones.resolve(choice, devices.map { it.toInput() }) ?: return null
        return devices.firstOrNull { it.id == wanted.id }
    }

    private class Microphone(val record: AudioRecord, val converter: PcmConverter)

    /** Opens [device] (null: automatic) at the first format it accepts; recording, or null. */
    private fun openMicrophone(device: AudioDeviceInfo?): Microphone? {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val formats = captureFormats(device?.sampleRates ?: IntArray(0), device?.channelCounts ?: IntArray(0))
        for (format in formats) {
            val record = tryOpen(format, device) ?: continue
            if (record.sampleRate != CommandRecognizer.SAMPLE_RATE || record.channelCount != 1) {
                Log.i(TAG, "recording at ${record.sampleRate} Hz x ${record.channelCount}, converting to 16 kHz mono")
            }
            return Microphone(record, PcmConverter(record.sampleRate, record.channelCount))
        }
        Log.e(TAG, "no usable recording format for ${device?.toInput() ?: "automatic"}")
        return null
    }

    private fun tryOpen(format: CaptureFormat, device: AudioDeviceInfo?): AudioRecord? {
        val mask = if (format.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .apply { if (format.channels <= 2) setChannelMask(mask) else setChannelIndexMask((1 shl format.channels) - 1) }
            .build()
        val minBytes = AudioRecord.getMinBufferSize(format.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = maxOf(minBytes, format.sampleRate * format.channels) // at least 0.5 s
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bytes)
                .build()
        } catch (e: SecurityException) {
            Log.e(TAG, "microphone permission missing", e)
            return null
        } catch (e: Exception) { // UnsupportedOperationException / IllegalArgumentException: format refused
            Log.i(TAG, "format $format refused: ${e.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        if (device != null) record.preferredDevice = device
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            return null
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return null
        }
        return record
    }

    private fun AudioDeviceInfo.toInput() = InputDevice(id, Microphones.kindOf(type), productName?.toString().orEmpty())

    private companion object {
        const val TAG = "BeachScoreVoice"
        const val RETRY_MS = 2_000L
    }
}
