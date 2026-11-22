package io.github.artcraftandroid.procraft.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import io.github.artcraftandroid.procraft.model.TimelineClip
import io.github.artcraftandroid.procraft.model.TimelineTrack
import io.github.artcraftandroid.procraft.model.TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DecodedAudioResult(
    val title: String,
    val pcmFile: File,
    val sampleRate: Int,
    val durationMs: Long,
    val peaks: FloatArray,
)

data class DecodedVideoResult(
    val title: String,
    val frames: List<Bitmap>,
    val durationMs: Long,
)

object MediaTimelineEngine {
    const val TARGET_SAMPLE_RATE = 22050
    const val PEAKS_PER_SECOND = 20

    fun queryDisplayName(context: Context, uri: Uri, fallback: String): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return@runCatching c.getString(idx).substringBeforeLast('.')
                }
            }
            null
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun decodeAudioToPcmCache(
        context: Context,
        uri: Uri,
        outFile: File,
        fallbackTitle: String = "Audio Clip",
    ): DecodedAudioResult? {
        val title = queryDisplayName(context, uri, fallbackTitle)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = f
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return null
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            var inputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val rawOut = ByteArrayOutputStream(131072)
            var inputDone = false
            var outputDone = false
            val maxDecodeBytes = TARGET_SAMPLE_RATE * 2 * 180 // cap ~3 minutes resampled safety

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf != null) {
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            inputSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            inputChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            appendDownsampledMonoPcm(chunk, inputChannels, inputSampleRate, TARGET_SAMPLE_RATE, rawOut)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || rawOut.size() >= maxDecodeBytes) {
                            outputDone = true
                        }
                    }
                }
            }

            val pcmBytes = rawOut.toByteArray()
            if (pcmBytes.isEmpty()) return null
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { it.write(pcmBytes) }

            val totalSamples = pcmBytes.size / 2
            val durationMs = (totalSamples * 1000L) / TARGET_SAMPLE_RATE
            val peaks = computeWaveformPeaks(pcmBytes, TARGET_SAMPLE_RATE, PEAKS_PER_SECOND)
            DecodedAudioResult(
                title = title,
                pcmFile = outFile,
                sampleRate = TARGET_SAMPLE_RATE,
                durationMs = durationMs,
                peaks = peaks,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun appendDownsampledMonoPcm(
        pcm16Le: ByteArray,
        channels: Int,
        srcRate: Int,
        dstRate: Int,
        out: ByteArrayOutputStream,
    ) {
        val frameBytes = channels * 2
        val frameCount = pcm16Le.size / frameBytes
        if (frameCount <= 0) return
        val bb = ByteBuffer.wrap(pcm16Le).order(ByteOrder.LITTLE_ENDIAN)
        val mono = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels) {
                sum += bb.short.toInt()
            }
            mono[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstCount = max(1, (frameCount / ratio).roundToInt())
        val outBytes = ByteArray(dstCount * 2)
        val outBb = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (j in 0 until dstCount) {
            val srcIdx = (j * ratio).toInt().coerceIn(0, frameCount - 1)
            outBb.putShort(mono[srcIdx])
        }
        out.write(outBytes)
    }

    fun computeWaveformPeaks(pcmBytes: ByteArray, sampleRate: Int, peaksPerSec: Int = PEAKS_PER_SECOND): FloatArray {
        val totalSamples = pcmBytes.size / 2
        if (totalSamples <= 0) return FloatArray(0)
        val samplesPerBin = (sampleRate / peaksPerSec.coerceAtLeast(1)).coerceAtLeast(64)
        val binCount = ((totalSamples + samplesPerBin - 1) / samplesPerBin).coerceAtLeast(1)
        val peaks = FloatArray(binCount)
        val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (b in 0 until binCount) {
            val start = b * samplesPerBin
            val end = min(totalSamples, start + samplesPerBin)
            var maxAbs = 0
            for (s in start until end) {
                val sample = abs(bb.getShort(s * 2).toInt())
                if (sample > maxAbs) maxAbs = sample
            }
            peaks[b] = (maxAbs / 32768f).coerceIn(0.03f, 1f)
        }
        return peaks
    }

    fun loadWaveformPeaksFromFile(pcmFile: File, sampleRate: Int = TARGET_SAMPLE_RATE): FloatArray {
        if (!pcmFile.isFile) return FloatArray(0)
        return runCatching {
            val bytes = pcmFile.readBytes()
            computeWaveformPeaks(bytes, sampleRate, PEAKS_PER_SECOND)
        }.getOrElse { FloatArray(0) }
    }

    fun importVideoFrames(
        context: Context,
        uri: Uri,
        canvasWidth: Int,
        canvasHeight: Int,
        fps: Int,
        maxFrames: Int = 180,
    ): DecodedVideoResult? {
        val title = queryDisplayName(context, uri, "Video Clip")
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durStr?.toLongOrNull()?.coerceAtLeast(100L) ?: 2000L
            val safeFps = fps.coerceIn(1, 60)
            val targetCount = ((durationMs * safeFps) / 1000L).toInt().coerceIn(1, maxFrames)
            val frames = ArrayList<Bitmap>(targetCount)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            for (i in 0 until targetCount) {
                val timeUs = (i * 1_000_000L) / safeFps
                val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val fitted = Bitmap.createBitmap(canvasWidth, canvasHeight, Bitmap.Config.ARGB_8888)
                val c = Canvas(fitted)
                c.drawColor(Color.TRANSPARENT)
                if (raw != null) {
                    val scale = min(canvasWidth.toFloat() / raw.width.coerceAtLeast(1), canvasHeight.toFloat() / raw.height.coerceAtLeast(1))
                    val dw = raw.width * scale
                    val dh = raw.height * scale
                    val left = (canvasWidth - dw) * 0.5f
                    val top = (canvasHeight - dh) * 0.5f
                    c.drawBitmap(raw, null, RectF(left, top, left + dw, top + dh), paint)
                    raw.recycle()
                }
                frames.add(fitted)
            }
            DecodedVideoResult(title = title, frames = frames, durationMs = durationMs)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * Dedicated AudioTrack-backed mixer for real-time audio track scrubbing and frame playback.
 */
class TimelineAudioEngine {
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher)
    private var audioTrack: AudioTrack? = null
    private var lastScrubJob: Job? = null
    private var lastScrubTimeMs = 0L

    private fun ensureTrack(sampleRate: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null && existing.state == AudioTrack.STATE_INITIALIZED && existing.sampleRate == sampleRate) {
            if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { existing.play() }
            }
            return existing
        }
        runCatching { existing?.release() }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 4 * 2)

        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
                .also {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        it.play()
                    }
                    audioTrack = it
                }
        }.getOrNull()
    }

    /**
     * Plays a crisp ~55ms audio snippet at [frame] when Audio Scrubbing is toggled ON.
     * Targets ONLY unmuted Audio tracks (and respects parent Group visibility/mute).
     */
    fun scrubFrame(frame: Int, fps: Int, tracks: List<TimelineTrack>) {
        val now = System.currentTimeMillis()
        if (now - lastScrubTimeMs < 35L) return
        lastScrubTimeMs = now

        val audibleClips = collectAudibleAudioClipsAtFrame(frame, tracks)
        if (audibleClips.isEmpty()) return

        lastScrubJob?.cancel()
        lastScrubJob = scope.launch {
            val sr = MediaTimelineEngine.TARGET_SAMPLE_RATE
            val snippetSamples = (sr * 0.055f).roundToInt().coerceIn(512, 2048)
            val mixed = mixAudioClipsSlice(audibleClips, frame, fps, snippetSamples, sr) ?: return@launch
            // Gentle fade-in / fade-out envelope so scrubbing never clicks
            val edge = min(96, mixed.size / 4)
            for (i in 0 until edge) {
                val env = i.toFloat() / edge
                mixed[i] = (mixed[i] * env).toInt().toShort()
                val tail = mixed.size - 1 - i
                mixed[tail] = (mixed[tail] * env).toInt().toShort()
            }
            val track = ensureTrack(sr) ?: return@launch
            runCatching {
                track.write(mixed, 0, mixed.size, AudioTrack.WRITE_NON_BLOCKING)
            }
        }
    }

    /**
     * Plays exactly one frame's worth of audio during continuous timeline playback.
     */
    fun playFrameSlice(frame: Int, fps: Int, tracks: List<TimelineTrack>) {
        val audibleClips = collectAudibleAudioClipsAtFrame(frame, tracks)
        if (audibleClips.isEmpty()) return
        scope.launch {
            val sr = MediaTimelineEngine.TARGET_SAMPLE_RATE
            val samples = (sr.toFloat() / fps.coerceIn(1, 60)).roundToInt().coerceIn(256, sr)
            val mixed = mixAudioClipsSlice(audibleClips, frame, fps, samples, sr) ?: return@launch
            val track = ensureTrack(sr) ?: return@launch
            runCatching {
                track.write(mixed, 0, mixed.size)
            }
        }
    }

    fun stopPlayback() {
        lastScrubJob?.cancel()
        scope.launch {
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
            }
        }
    }

    fun release() {
        lastScrubJob?.cancel()
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
        executor.shutdownNow()
    }

    private fun collectAudibleAudioClipsAtFrame(frame: Int, tracks: List<TimelineTrack>): List<TimelineClip> {
        val groups = tracks.filter { it.kind == TrackKind.GROUP }.associateBy { it.id }
        val result = ArrayList<TimelineClip>(4)
        for (tr in tracks) {
            if (tr.kind != TrackKind.AUDIO) continue
            if (!tr.visible || tr.muted) continue
            val parent = tr.groupId?.let { groups[it] }
            if (parent != null && (!parent.visible || parent.muted)) continue
            val clip = tr.clipAt(frame) ?: continue
            if (clip.audioFilePath != null) result.add(clip)
        }
        return result
    }

    private fun mixAudioClipsSlice(
        clips: List<TimelineClip>,
        frame: Int,
        fps: Int,
        sampleCount: Int,
        sampleRate: Int,
    ): ShortArray? {
        val accum = IntArray(sampleCount)
        var anyRead = false
        val safeFps = fps.coerceIn(1, 60)

        for (clip in clips) {
            val path = clip.audioFilePath ?: continue
            val file = File(path)
            if (!file.isFile) continue
            val relFrames = (frame - clip.startFrame + clip.sourceFrameOffset).coerceAtLeast(0)
            val startSample = ((relFrames.toDouble() / safeFps) * sampleRate).roundToInt().coerceAtLeast(0)
            val byteOffset = startSample.toLong() * 2L
            val byteLen = sampleCount * 2
            val buf = ByteArray(byteLen)
            val readCount = runCatching {
                RandomAccessFile(file, "r").use { raf ->
                    if (byteOffset >= raf.length()) return@use 0
                    raf.seek(byteOffset)
                    val avail = min(byteLen.toLong(), raf.length() - byteOffset).toInt()
                    raf.readFully(buf, 0, avail)
                    avail
                }
            }.getOrDefault(0)
            if (readCount >= 2) {
                anyRead = true
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                val samplesRead = readCount / 2
                for (i in 0 until samplesRead) {
                    accum[i] += bb.getShort(i * 2).toInt()
                }
            }
        }
        if (!anyRead) return null
        return ShortArray(sampleCount) { i ->
            accum[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
