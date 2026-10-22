package com.openthinking.blankassistant.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import timber.log.Timber

/** Plays one voice note. Observable state for the UI: [playing], [position], [duration] (ms). */
class NotePlayer(private val context: Context) {
    var playing by mutableStateOf(false)
        private set
    var position by mutableIntStateOf(0)
        private set
    var duration by mutableIntStateOf(0)
        private set
    var loaded: Uri? = null
        private set

    private var mp: MediaPlayer? = null

    fun load(uri: Uri?) {
        release()
        if (uri == null) return
        val p = MediaPlayer()
        try {
            p.setOnCompletionListener {
                playing = false
                position = 0
                it.seekTo(0)
            }
            p.setOnErrorListener { _, _, _ ->
                playing = false
                true
            }
            p.setDataSource(context, uri)
            p.prepare()
        } catch (e: Exception) {
            Timber.e(e, "Could not load $uri")
            p.release()
            return
        }
        mp = p
        loaded = uri
        duration = p.duration
    }

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) {
            p.pause()
            playing = false
        } else {
            p.start()
            playing = true
        }
    }

    fun seekTo(ms: Int) {
        mp?.seekTo(ms)
        position = ms
    }

    fun refresh() {
        mp?.let { position = it.currentPosition }
    }

    fun pause() {
        mp?.takeIf { it.isPlaying }?.pause()
        playing = false
    }

    fun release() {
        mp?.release()
        mp = null
        loaded = null
        playing = false
        position = 0
        duration = 0
    }
}
