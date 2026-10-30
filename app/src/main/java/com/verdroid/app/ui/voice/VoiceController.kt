package com.verdroid.app.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.verdroid.app.data.voice.VoiceText
import java.util.Locale

/**
 * Speech-to-text (SpeechRecognizer, device default language) and text-to-speech (TextToSpeech) for one chat screen.
 * Main thread only. [release] in onDispose. Falls back to RecognizerIntent when no recognizer service exists
 * ([needsIntentFallback]); the screen launches [recognizeIntent] for that.
 */
class VoiceController(private val context: Context) {
    enum class Mic { IDLE, LISTENING, PROCESSING }

    var mic by mutableStateOf(Mic.IDLE); private set
    var speaking by mutableStateOf(false); private set
    /** Live partial transcript while listening. */
    var partial by mutableStateOf(""); private set

    var onResult: (String) -> Unit = {}
    var onError: (code: Int) -> Unit = {}
    /** Called when a reply finished speaking (not when stopped). */
    var onSpeechDone: () -> Unit = {}

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: String? = null
    private var stopped = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    val recognizerAvailable: Boolean get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
    fun needsIntentFallback(): Boolean = !recognizerAvailable

    fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Verdroid")

    fun startListening() {
        stopSpeaking()
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()?.also {
            it.setRecognitionListener(listener); recognizer = it
        } ?: run { onError(SpeechRecognizer.ERROR_CLIENT); return }
        partial = ""
        mic = Mic.LISTENING
        runCatching {
            r.startListening(recognizeIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName))
        }.onFailure { mic = Mic.IDLE; onError(SpeechRecognizer.ERROR_CLIENT) }
    }

    fun stopListening() { runCatching { recognizer?.stopListening() }; if (mic == Mic.LISTENING) mic = Mic.PROCESSING }
    fun cancelListening() { runCatching { recognizer?.cancel() }; mic = Mic.IDLE; partial = "" }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { mic = Mic.LISTENING }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { mic = Mic.PROCESSING }
        override fun onError(error: Int) { mic = Mic.IDLE; partial = ""; this@VoiceController.onError(error) }
        override fun onResults(results: Bundle?) {
            mic = Mic.IDLE; partial = ""
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) onResult(text) else this@VoiceController.onError(SpeechRecognizer.ERROR_NO_MATCH)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Speaks a markdown reply (markup and code stripped). Interrupts anything already speaking. */
    fun speak(markdown: String?) {
        val text = VoiceText.forSpeech(markdown)
        if (text.isBlank()) return
        stopped = false
        if (tts == null) TextToSpeech(context) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) { runCatching { tts?.language = Locale.getDefault() }; pending?.let { pending = null; say(it) } }
                else { pending = null; speaking = false }
            }
        }.also { t ->
            tts = t
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { main.post { speaking = true } }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId?.startsWith(LAST) == true) main.post { speaking = false; if (!stopped) onSpeechDone() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { main.post { speaking = false } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { main.post { speaking = false } }
            })
        }
        if (ttsReady) say(text) else { pending = text; speaking = true }
    }

    private fun say(text: String) {
        val t = tts ?: return
        val parts = VoiceText.chunks(text)
        parts.forEachIndexed { i, p ->
            val id = if (i == parts.lastIndex) "$LAST$i" else "part$i"
            t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
        speaking = parts.isNotEmpty()
    }

    fun stopSpeaking() {
        stopped = true; pending = null
        runCatching { tts?.stop() }
        speaking = false
    }

    fun release() {
        stopSpeaking()
        runCatching { recognizer?.destroy() }; recognizer = null
        runCatching { tts?.shutdown() }; tts = null; ttsReady = false
        mic = Mic.IDLE
    }

    private companion object { const val LAST = "last" }
}
