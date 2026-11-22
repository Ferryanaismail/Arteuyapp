package io.github.artcraftandroid.procraft.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Lightweight procedural paper-grain stroke feedback using Android AudioTrack.
 * Synthesizes soft tactile brush whisper proportionally to stroke velocity without external audio files.
 */
class StrokeSoundEngine(private val scope: CoroutineScope) {
    @Volatile
    private var targetGain = 0f

    @Volatile
    private var enabled = false

    private var playJob: Job? = null

    fun setEnabled(enable: Boolean) {
        enabled = enable
        if (!enable) {
            targetGain = 0f
            playJob?.cancel()
            playJob = null
        } else if (playJob == null) {
            startLoop()
        }
    }

    fun onStrokeMove(speedPx: Float, pressure: Float) {
        if (!enabled) return
        val normalized = (speedPx / 45f).coerceIn(0f, 1f) * pressure.coerceIn(0.2f, 1f)
        targetGain = normalized * 0.14f
    }

    fun onStrokeEnd() {
        targetGain = 0f
    }

    fun release() {
        enabled = false
        targetGain = 0f
        playJob?.cancel()
        playJob = null
    }

    private fun startLoop() {
        playJob?.cancel()
        playJob = scope.launch(Dispatchers.Default) {
            val sampleRate = 22050
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(1024)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@launch

            val buf = ShortArray(512)
            val rng = Random(7)
            var currentGain = 0f
            var prevNoise = 0f

            runCatching {
                track.play()
                while (isActive && enabled) {
                    val tgt = targetGain
                    targetGain *= 0.86f
                    for (i in buf.indices) {
                        currentGain = currentGain * 0.96f + tgt * 0.04f
                        if (currentGain < 0.001f) {
                            buf[i] = 0
                        } else {
                            // Low-passed tactile paper grain noise
                            val raw = rng.nextFloat() * 2f - 1f
                            val lp = prevNoise * 0.65f + raw * 0.35f
                            prevNoise = lp
                            val sample = (lp * currentGain * 14000f).toInt().coerceIn(-32767, 32767)
                            buf[i] = sample.toShort()
                        }
                    }
                    track.write(buf, 0, buf.size)
                }
            }
            runCatching {
                track.stop()
                track.release()
            }
        }
    }
}
