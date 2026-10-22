package com.openthinking.blankassistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/** Placeholder: never recognises anything, just satisfies the assistant metadata. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
