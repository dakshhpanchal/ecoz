package app.ecoz

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager

/**
 * Plays the "find my phone" alarm. It uses the alarm audio stream, which sounds even on
 * silent and in most Do Not Disturb modes, and pushes that stream to full volume while
 * ringing, restoring what it was afterwards.
 */
object RingController {
    private var player: MediaPlayer? = null
    private var savedVolume = -1

    /** Returns false if it was already ringing or no sound could be played. */
    @Synchronized
    fun start(context: Context): Boolean {
        if (player != null) return false
        val audio = context.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        return try {
            val sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(context, sound)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            restoreVolume(audio)
            false
        }
    }

    @Synchronized
    fun stop(context: Context) {
        player?.run { runCatching { stop() }; release() }
        player = null
        restoreVolume(context.getSystemService(AudioManager::class.java))
    }

    private fun restoreVolume(audio: AudioManager) {
        if (savedVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        savedVolume = -1
    }
}
