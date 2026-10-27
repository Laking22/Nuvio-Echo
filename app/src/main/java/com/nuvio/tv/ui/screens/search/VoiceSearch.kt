package com.nuvio.tv.ui.screens.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.nuvio.tv.R

@Stable
class VoiceSearchController internal constructor() {
    var isListening by mutableStateOf(false)
        internal set

    /** How loud the voice is, 0–1, while listening. */
    var level by mutableFloatStateOf(0f)
        internal set

    internal var startAction: () -> Unit = {}

    fun start() = startAction()
}

/**
 * Speech-to-text for the keyboard search screen, with the same recognizer setup, permission
 * prompt and error messages as the classic search screen.
 */
@Composable
fun rememberVoiceSearch(onResult: (String) -> Unit): VoiceSearchController {
    val context = LocalContext.current
    val strNoSpeech = stringResource(R.string.search_voice_no_speech)
    val strMicPermission = stringResource(R.string.search_voice_mic_permission)
    val strFailed = stringResource(R.string.search_voice_failed)
    val strUnavailable = stringResource(R.string.search_voice_unavailable)
    val latestOnResult by rememberUpdatedState(onResult)

    val controller = remember { VoiceSearchController() }
    val recognizer = remember(context) {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
    }
    val intent = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }
    val listen: () -> Unit = {
        controller.isListening = true
        runCatching {
            recognizer?.cancel()
            recognizer?.startListening(intent)
        }.onFailure {
            controller.isListening = false
            Toast.makeText(context, strUnavailable, Toast.LENGTH_SHORT).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else Toast.makeText(context, strMicPermission, Toast.LENGTH_SHORT).show()
    }
    controller.startAction = {
        when {
            recognizer == null -> Toast.makeText(context, strUnavailable, Toast.LENGTH_SHORT).show()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else -> listen()
        }
    }

    VoiceListeningGlowSync({ controller.isListening }, { controller.level })

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                controller.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onError(error: Int) {
                controller.isListening = false
                controller.level = 0f
                Log.w("VoiceSearch", "Voice recognition error: $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Toast.makeText(context, strNoSpeech, Toast.LENGTH_SHORT).show()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> Unit
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Toast.makeText(context, strMicPermission, Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(context, strFailed, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                controller.isListening = false
                controller.level = 0f
                val recognized = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (recognized.isNotEmpty()) {
                    latestOnResult(recognized)
                } else {
                    Toast.makeText(context, strNoSpeech, Toast.LENGTH_SHORT).show()
                }
            }
        })
        onDispose {
            recognizer?.setRecognitionListener(null)
            recognizer?.destroy()
        }
    }
    return controller
}
