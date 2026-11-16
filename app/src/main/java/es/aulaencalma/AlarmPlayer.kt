package es.aulaencalma

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Player is used only from the main thread. Audio stays local to the phone. */
class AlarmPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var media: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var generation = 0
    private var completed: (() -> Unit)? = null

    fun play(settings: AppSettings, onComplete: () -> Unit) {
        stop()
        val current = ++generation
        completed = onComplete
        if (settings.alertVolume == 0) {
            finish(current)
            return
        }
        if (settings.audioUri.isNotEmpty()) {
            try {
                val player = MediaPlayer()
                media = player
                player.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                player.setDataSource(context, Uri.parse(settings.audioUri))
                val volume = settings.alertVolume / 100f
                player.setVolume(volume, volume)
                player.setOnCompletionListener { finish(current) }
                player.setOnErrorListener { _, _, _ ->
                    if (current == generation) {
                        releaseMedia()
                        startBeep(settings.alertVolume, current)
                    }
                    true
                }
                player.setOnPreparedListener {
                    if (current == generation) it.start()
                }
                player.prepareAsync()
                return
            } catch (_: Exception) {
                releaseMedia()
                // If a selected file has been deleted or its permission revoked,
                // still give the class an audible fallback using the built-in beep.
            }
        }
        startBeep(settings.alertVolume, current)
    }

    private fun startBeep(volume: Int, generationAtStart: Int) {
        try {
            val generator = ToneGenerator(AudioManager.STREAM_MUSIC, volume.coerceIn(1, 100))
            tone = generator
            if (!generator.startTone(ToneGenerator.TONE_PROP_BEEP, 650)) {
                finish(generationAtStart)
                return
            }
            handler.postDelayed({ finish(generationAtStart) }, 750)
        } catch (_: Exception) {
            finish(generationAtStart)
        }
    }

    private fun finish(id: Int) {
        if (id != generation) return
        releaseMedia()
        releaseTone()
        val callback = completed
        completed = null
        callback?.invoke()
    }

    fun stop() {
        generation++
        completed = null
        releaseMedia()
        releaseTone()
    }

    private fun releaseMedia() {
        val current = media
        media = null
        if (current != null) {
            try { current.reset() } catch (_: Exception) { }
            try { current.release() } catch (_: Exception) { }
        }
    }

    private fun releaseTone() {
        try { tone?.stopTone() } catch (_: Exception) { }
        try { tone?.release() } catch (_: Exception) { }
        tone = null
    }
}
