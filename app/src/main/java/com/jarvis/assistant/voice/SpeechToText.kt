package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class SpeechToText(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var isListeningContinuously = false
    private var lastWakeMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var longSessionActive = false
    private val longTranscript = StringBuilder()
    private var longSessionStartedAt = 0L
    private var longQuietRunnable: Runnable? = null
    private var longLimitRunnable: Runnable? = null
    private var longResultCallback: ((String) -> Unit)? = null
    private var longErrorCallback: (() -> Unit)? = null
    private val longSessionLimitMs = 5 * 60_000L
    private val longQuietMs = 7_000L

    private val wakeWords = listOf(
        "hey jarvis", "ok jarvis", "okay jarvis", "hi jarvis",
        "hello jarvis", "hey jarwis", "jarvis ji",
        "jarvis", "jarwis", "jaarvis", "jarviz", "jarves", "jarvus",
        "jervis", "jarvish",
        "जार्विस", "जारविस", "जर्विस", "हे जार्विस"
    )

    /**
     * Long dictation mode: accumulates final recognition chunks for up to five minutes.
     * Android SpeechRecognizer often ends a single segment after a short pause, so each
     * final segment is appended and recognition restarts. A seven-second quiet period
     * finishes the complete utterance. The five-minute limit is a hard safety bound.
     */
    fun listenLong(onResult: (String) -> Unit, onError: () -> Unit) {
        stopInternal()
        longSessionActive = true
        longTranscript.setLength(0)
        longSessionStartedAt = System.currentTimeMillis()
        longResultCallback = onResult
        longErrorCallback = onError
        longQuietRunnable?.let { mainHandler.removeCallbacks(it) }
        longLimitRunnable?.let { mainHandler.removeCallbacks(it) }
        longLimitRunnable = Runnable { finishLongSession() }
        mainHandler.postDelayed(longLimitRunnable!!, longSessionLimitMs)
        startLongSession()
    }

    private fun startLongSession() {
        if (!longSessionActive) return
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                if (!longSessionActive) return
                val segment = bestTranscript(results).trim()
                if (segment.isNotBlank()) {
                    if (longTranscript.isNotEmpty()) longTranscript.append(' ')
                    longTranscript.append(segment)
                    longQuietRunnable?.let { mainHandler.removeCallbacks(it) }
                    longQuietRunnable = Runnable { finishLongSession() }
                    mainHandler.postDelayed(longQuietRunnable!!, longQuietMs)
                }
                if (System.currentTimeMillis() - longSessionStartedAt >= longSessionLimitMs) {
                    finishLongSession()
                } else {
                    try { recognizer?.destroy() } catch (_: Exception) {}
                    recognizer = null
                    mainHandler.postDelayed({ startLongSession() }, 250L)
                }
            }

            override fun onError(error: Int) {
                if (!longSessionActive) return
                Log.d("SpeechToText", "Long dictation segment error: $error")
                if (longTranscript.isNotEmpty()) {
                    longQuietRunnable?.let { mainHandler.removeCallbacks(it) }
                    longQuietRunnable = Runnable { finishLongSession() }
                    mainHandler.postDelayed(longQuietRunnable!!, longQuietMs)
                }
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    mainHandler.postDelayed({ startLongSession() }, 500L)
                } else if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    mainHandler.postDelayed({ startLongSession() }, 300L)
                } else {
                    if (longTranscript.isEmpty()) finishLongSession()
                    else mainHandler.postDelayed({ startLongSession() }, 400L)
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer?.startListening(buildIntent(forWakeWord = false))
    }

    private fun finishLongSession() {
        if (!longSessionActive) return
        longSessionActive = false
        longQuietRunnable?.let { mainHandler.removeCallbacks(it) }
        longLimitRunnable?.let { mainHandler.removeCallbacks(it) }
        longQuietRunnable = null
        longLimitRunnable = null
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        val complete = longTranscript.toString().trim()
        if (complete.isNotBlank()) longResultCallback?.invoke(complete)
        else longErrorCallback?.invoke()
        longResultCallback = null
        longErrorCallback = null
    }

    fun listenOnce(onResult: (String) -> Unit, onError: () -> Unit) {
        stopInternal()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(simpleListener(onResult, onError))
        recognizer?.startListening(buildIntent(forWakeWord = false))
    }

    fun listenContinuous(onWakeWordDetected: (trailingCommand: String) -> Unit) {
        if (isListeningContinuously) return
        isListeningContinuously = true
        startContinuousSession(onWakeWordDetected)
    }

    fun isListening(): Boolean = isListeningContinuously

    private fun startContinuousSession(onWakeWordDetected: (String) -> Unit) {
        if (!isListeningContinuously) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            private var triggeredThisSession = false

            override fun onPartialResults(partialResults: Bundle?) {
                if (triggeredThisSession) return
                val heard = bestTranscript(partialResults).lowercase().trim()
                if (heard.isBlank()) return
                tryFireWake(heard, onWakeWordDetected) { triggeredThisSession = true }
            }

            override fun onResults(results: Bundle?) {
                if (!triggeredThisSession) {
                    val heard = bestTranscript(results).lowercase().trim()
                    Log.d("SpeechToText", "Final heard: $heard")
                    tryFireWake(heard, onWakeWordDetected) { triggeredThisSession = true }
                }
                restartContinuous(onWakeWordDetected)
            }

            override fun onError(error: Int) {
                Log.d("SpeechToText", "Recognizer error: $error")
                restartContinuous(onWakeWordDetected)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer?.startListening(buildIntent(forWakeWord = true))
    }

    private fun tryFireWake(
        heard: String,
        onWakeWordDetected: (String) -> Unit,
        onFired: () -> Unit
    ) {
        val now = System.currentTimeMillis()
        if (now - lastWakeMs < 900) return
        val match = findWakeWord(heard) ?: return
        lastWakeMs = now
        onFired()
        val trailing = extractTrailing(heard, match)
        Log.d("SpeechToText", "Wake matched=$match trailing=$trailing")
        onWakeWordDetected(trailing)
    }

    private fun findWakeWord(heard: String): String? {
        for (w in wakeWords.sortedByDescending { it.length }) {
            if (matchesWake(heard, w)) return w
        }
        return null
    }

    private fun matchesWake(heard: String, wake: String): Boolean {
        if (heard == wake) return true
        if (heard.startsWith("$wake ")) return true
        if (heard.endsWith(" $wake")) return true
        if (heard.contains(" $wake ")) return true
        if (wake.length >= 4 && heard.contains(wake)) return true
        return false
    }

    private fun extractTrailing(heard: String, wake: String): String {
        val idx = heard.indexOf(wake)
        if (idx < 0) return ""
        return heard.substring(idx + wake.length).trim().trimStart(',', '.', ' ')
    }

    private fun restartContinuous(onWakeWordDetected: (String) -> Unit) {
        recognizer?.destroy()
        recognizer = null
        if (!isListeningContinuously) return
        mainHandler.postDelayed({
            if (isListeningContinuously) startContinuousSession(onWakeWordDetected)
        }, 280)
    }

    fun stopLongListening() {
        if (!longSessionActive) return
        finishLongSession()
    }

    fun stopContinuous() {
        isListeningContinuously = false
        mainHandler.removeCallbacksAndMessages(null)
        stopInternal()
    }

    private fun stopInternal() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun simpleListener(onResult: (String) -> Unit, onError: () -> Unit) =
        object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                onResult(bestTranscript(results))
            }
            override fun onError(error: Int) = onError()
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

    private fun bestTranscript(results: Bundle?): String {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return ""
        if (matches.isEmpty()) return ""
        val boost = listOf(
            "jarvis", "screen", "analyze", "analyse", "weather", "mausam",
            "remember", "macro", "whatsapp", "call", "brief", "morning",
            "chrome", "bluetooth", "flashlight", "volume", "alarm"
        )
        return matches.filter { it.isNotBlank() }.maxByOrNull { m ->
            val lower = m.lowercase()
            var score = m.length
            boost.forEach { if (lower.contains(it)) score += 40 }
            score
        } ?: matches.first()
    }

    private fun buildIntent(forWakeWord: Boolean) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Hindi recognizer handles Hindi speech; English app names remain in the transcript where supported.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
        if (forWakeWord) {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300L)
        } else {
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 400L)
        }
    }

    fun destroy() {
        isListeningContinuously = false
        longSessionActive = false
        longQuietRunnable?.let { mainHandler.removeCallbacks(it) }
        longLimitRunnable?.let { mainHandler.removeCallbacks(it) }
        mainHandler.removeCallbacksAndMessages(null)
        stopInternal()
    }
}
