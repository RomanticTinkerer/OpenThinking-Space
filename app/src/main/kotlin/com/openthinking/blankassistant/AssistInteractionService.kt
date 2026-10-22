package com.openthinking.blankassistant

import android.service.voice.VoiceInteractionService

/**
 * Long-lived service the system keeps bound while this app is the default
 * assistant. Being a valid [VoiceInteractionService] (plus the metadata in
 * res/xml/interaction_service.xml) is what makes the app selectable under
 * Settings > Apps > Default apps > Digital assistant app.
 *
 * Nothing to do here yet: the power-button / gesture invocation is routed by
 * the system straight to [AssistSessionService].
 */
class AssistInteractionService : VoiceInteractionService()
