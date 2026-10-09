package io.github.ollamadesk.remote

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records the microphone as 16 kHz mono 16-bit PCM, exactly what whisper.cpp on the computer wants,
 * and wraps it in a WAV header. Nothing is written to storage.
 */
class Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    val isRecording get() = running

    /** Call only once RECORD_AUDIO has been granted. */
    @SuppressLint("MissingPermission")
    fun start() {
        val minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, RATE) * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("The microphone isn't available")
        }
        synchronized(pcm) { pcm.reset() }
        record = recorder
        running = true
        recorder.startRecording()
        thread = Thread {
            val chunk = ByteArray(4096)
            while (running) {
                val n = recorder.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(pcm) {
                    if (pcm.size() < MAX_BYTES) pcm.write(chunk, 0, n)
                }
            }
        }.also { it.start() }
    }

    /** Stops and returns the recording as a WAV file in memory. */
    fun stop(): ByteArray {
        running = false
        thread?.join(1000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        return wav(data)
    }

    fun cancel() {
        stop()
        synchronized(pcm) { pcm.reset() }
    }

    private fun wav(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)  // PCM, mono
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)       // byte rate, block align, bits
            put("data".toByteArray()); putInt(data.size)
        }
        return header.array() + data
    }

    companion object {
        const val RATE = 16_000
        const val MAX_SECONDS = 120
        private const val MAX_BYTES = RATE * 2 * MAX_SECONDS
    }
}

/** Plays speech the computer synthesised. One clip at a time. */
class Player(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(wav: ByteArray, onDone: () -> Unit) {
        stop()
        val file = File(context.cacheDir, "speech.wav")
        file.writeBytes(wav)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                stop()
                onDone()
            }
            setOnErrorListener { _, _, _ ->
                stop()
                onDone()
                true
            }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        File(context.cacheDir, "speech.wav").delete()
    }
}
