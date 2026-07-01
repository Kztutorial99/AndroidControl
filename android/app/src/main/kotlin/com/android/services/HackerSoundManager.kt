package com.android.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class HackerSoundManager(private val context: Context, private val ttsSpeed: Float = 0.60f) {

    private var tts: TextToSpeech? = null
    private var droneThread: Thread? = null
    @Volatile private var droneTrack: AudioTrack? = null
    @Volatile private var droneRunning = false

    fun start(text: String) {
        startDrone()
        initTts(text)
    }

    private fun startDrone() {
        droneRunning = true
        droneThread = Thread {
            val sampleRate = 44100
            val bufSize = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                bufSize * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            droneTrack = track
            track.play()
            val buffer = ShortArray(bufSize)
            var phase = 0.0
            while (droneRunning) {
                for (i in buffer.indices) {
                    val drone    = sin(2.0 * PI * 55.0  * phase / sampleRate) * 0.22
                    val harmonic = sin(2.0 * PI * 110.0 * phase / sampleRate) * 0.08
                    val noise    = (Random.nextDouble() * 2.0 - 1.0) * 0.035
                    buffer[i] = ((drone + harmonic + noise) * Short.MAX_VALUE).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    phase++
                    if (phase >= sampleRate.toDouble() * 1000.0) phase = 0.0
                }
                if (droneRunning) try { track.write(buffer, 0, buffer.size) } catch (_: Exception) { break }
            }
            try { track.pause(); track.flush(); track.stop(); track.release() } catch (_: Exception) {}
            droneTrack = null
        }.also { it.isDaemon = true; it.start() }
    }

    private fun initTts(text: String) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language    = Locale.ENGLISH
                tts?.setPitch(0.35f)
                tts?.setSpeechRate(ttsSpeed.coerceIn(0.10f, 2.00f))
                Handler(Looper.getMainLooper()).postDelayed({
                    val clean = text.replace("\n", ". ").trim().ifEmpty { "System breach initiated" }
                    tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "hacker_tts")
                }, 1800)
            }
        }
    }

    fun stop() {
        droneRunning = false
        // Stop + release AudioTrack first — unblocks any blocked write() immediately
        try { droneTrack?.stop()    } catch (_: Exception) {}
        try { droneTrack?.release() } catch (_: Exception) {}
        droneTrack = null
        // Interrupt thread after nulling reference is fine; keep ref for interrupt
        val t = droneThread; droneThread = null; t?.interrupt()
        // Stop TTS immediately
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
