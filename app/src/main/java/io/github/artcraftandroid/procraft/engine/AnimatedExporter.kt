package io.github.artcraftandroid.procraft.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure Kotlin GIF89a animated encoder, H.264 MP4 exporter, and filmstrip sheet exporter.
 * Encodes multi-frame artwork animations and Time-lapse sequences cleanly without external binaries.
 */
object AnimatedExporter {

    fun encodeMp4(
        frames: List<Bitmap>,
        outputFile: File,
        fps: Int = 12,
        maxDim: Int = 1080,
    ): Boolean {
        if (frames.isEmpty()) return false
        val safeFps = fps.coerceIn(1, 60)
        val first = frames.first()
        val scale = min(maxDim.toFloat() / max(first.width, 1), maxDim.toFloat() / max(first.height, 1)).coerceAtMost(1f)
        val w = max(64, ((first.width * scale).roundToInt() / 16) * 16)
        val h = max(64, ((first.height * scale).roundToInt() / 16) * 16)

        val sequence = if (frames.size == 1) {
            List(safeFps.coerceIn(2, 12)) { frames.first() }
        } else {
            frames
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        val scaledBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val drawCanvas = Canvas(scaledBmp)
        val drawPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        val argb = IntArray(w * h)

        return try {
            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                val bitRate = (w * h * safeFps * 0.14f).roundToInt().coerceIn(800_000, 12_000_000)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            if (outputFile.exists()) outputFile.delete()
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var inputDone = false
            var outputDone = false
            var frameIndex = 0
            val frameDurationUs = 1_000_000L / safeFps
            val timeoutUs = 10_000L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(timeoutUs)
                    if (inIdx >= 0) {
                        if (frameIndex < sequence.size) {
                            val src = sequence[frameIndex]
                            drawCanvas.drawColor(Color.WHITE)
                            drawCanvas.drawBitmap(
                                src,
                                android.graphics.Rect(0, 0, src.width, src.height),
                                android.graphics.Rect(0, 0, w, h),
                                drawPaint,
                            )
                            scaledBmp.getPixels(argb, 0, w, 0, 0, w, h)
                            val ptsUs = frameIndex * frameDurationUs
                            val img = runCatching { codec.getInputImage(inIdx) }.getOrNull()
                            val bytesWritten = if (img != null) {
                                try {
                                    fillImageYuv420(img, argb, w, h)
                                } finally {
                                    img.close()
                                }
                            } else {
                                val buf = codec.getInputBuffer(inIdx)
                                if (buf != null) fillBufferNv12(buf, argb, w, h) else 0
                            }
                            codec.queueInputBuffer(inIdx, 0, bytesWritten, ptsUs, 0)
                            frameIndex++
                        } else {
                            codec.queueInputBuffer(
                                inIdx,
                                0,
                                0,
                                frameIndex * frameDurationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        }
                    }
                }

                var draining = true
                while (draining) {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> draining = false
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = codec.outputFormat
                            trackIndex = muxer.addTrack(outFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                if (!muxerStarted) {
                                    trackIndex = muxer.addTrack(codec.outputFormat)
                                    muxer.start()
                                    muxerStarted = true
                                }
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outIdx, false)
                            if (eos) {
                                outputDone = true
                                draining = false
                            }
                        }
                    }
                }
            }
            muxerStarted && outputFile.isFile && outputFile.length() > 0L
        } catch (_: Throwable) {
            runCatching { if (outputFile.exists()) outputFile.delete() }
            false
        } finally {
            scaledBmp.recycle()
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private fun fillImageYuv420(image: android.media.Image, argb: IntArray, w: Int, h: Int): Int {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (j in 0 until h) {
            val rowOff = j * w
            val yBase = j * yRowStride
            for (i in 0 until w) {
                val c = argb[rowOff + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yBuf.put(yBase + i * yPixelStride, y.toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val uvRow = j shr 1
                    val uvCol = i shr 1
                    uBuf.put(uvRow * uRowStride + uvCol * uPixelStride, u.toByte())
                    vBuf.put(uvRow * vRowStride + uvCol * vPixelStride, v.toByte())
                }
            }
        }
        return (w * h * 3) / 2
    }

    private fun fillBufferNv12(buf: ByteBuffer, argb: IntArray, w: Int, h: Int): Int {
        buf.clear()
        val ySize = w * h
        val needed = (ySize * 3) / 2
        if (buf.capacity() < needed) return 0
        var yIdx = 0
        var uvIdx = ySize
        for (j in 0 until h) {
            val rowOff = j * w
            for (i in 0 until w) {
                val c = argb[rowOff + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                buf.put(yIdx++, y.toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    buf.put(uvIdx++, u.toByte())
                    buf.put(uvIdx++, v.toByte())
                }
            }
        }
        return needed
    }

    fun encodeGif(
        frames: List<Bitmap>,
        output: OutputStream,
        fps: Int = 8,
        transparentBg: Boolean = false,
        maxDim: Int = 512,
    ) {
        if (frames.isEmpty()) return
        val first = frames.first()
        val scale = min(maxDim.toFloat() / max(first.width, 1), maxDim.toFloat() / max(first.height, 1)).coerceAtMost(1f)
        val w = max(16, (first.width * scale).roundToInt())
        val h = max(16, (first.height * scale).roundToInt())
        val delayCs = (100f / fps.coerceIn(1, 30)).roundToInt().coerceIn(3, 200)

        // Header: GIF89a
        output.write("GIF89a".toByteArray(Charsets.US_ASCII))
        // Logical Screen Descriptor (no global color table — each frame writes a 256-color quantized table)
        writeShort(output, w)
        writeShort(output, h)
        output.write(0x70) // 8-bit color resolution, no global table
        output.write(0x00) // bg color index
        output.write(0x00) // pixel aspect ratio

        // Netscape 2.0 Application Extension (infinite loop)
        output.write(0x21)
        output.write(0xFF)
        output.write(11)
        output.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        output.write(3)
        output.write(1)
        writeShort(output, 0) // loop forever
        output.write(0)

        val scaledBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(scaledBmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val pixels = IntArray(w * h)

        // Build shared uniform 6x7x6 palette (252 colors) + reserved transparent/white entries
        val paletteBytes = buildWebCubePalette()

        for (src in frames) {
            canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            canvas.drawBitmap(src, android.graphics.Rect(0, 0, src.width, src.height), android.graphics.Rect(0, 0, w, h), paint)
            scaledBmp.getPixels(pixels, 0, w, 0, 0, w, h)

            // Graphic Control Extension
            output.write(0x21)
            output.write(0xF9)
            output.write(4)
            val packed = if (transparentBg) 0x09 else 0x08 // disposal=2 (restore background) + transparent flag
            output.write(packed)
            writeShort(output, delayCs)
            output.write(if (transparentBg) 255 else 0) // transparent index = 255
            output.write(0)

            // Image Descriptor
            output.write(0x2C)
            writeShort(output, 0)
            writeShort(output, 0)
            writeShort(output, w)
            writeShort(output, h)
            output.write(0x87) // Local color table present, size = 2^(7+1) = 256
            output.write(paletteBytes)

            val indices = ByteArray(pixels.size) { i ->
                val c = pixels[i]
                val a = (c ushr 24) and 0xFF
                if (transparentBg && a < 64) {
                    255.toByte()
                } else {
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    quantizeToCube(r, g, b).toByte()
                }
            }
            LzwEncoder(8, indices).encode(output)
        }

        scaledBmp.recycle()
        output.write(0x3B) // GIF Trailer
        output.flush()
    }

    fun buildFilmstripBitmap(frames: List<Bitmap>, maxCellDim: Int = 360): Bitmap? {
        if (frames.isEmpty()) return null
        val sample = frames.first()
        val scale = min(maxCellDim.toFloat() / sample.width, maxCellDim.toFloat() / sample.height).coerceAtMost(1f)
        val cw = max(32, (sample.width * scale).roundToInt())
        val ch = max(32, (sample.height * scale).roundToInt())
        val cols = min(frames.size, ceil(sqrt(frames.size.toDouble())).toInt().coerceAtLeast(1))
        val rows = ((frames.size + cols - 1) / cols).coerceAtLeast(1)
        val out = Bitmap.createBitmap(cols * cw, rows * ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        frames.forEachIndexed { idx, bmp ->
            val col = idx % cols
            val row = idx / cols
            val dst = android.graphics.Rect(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch)
            c.drawBitmap(bmp, android.graphics.Rect(0, 0, bmp.width, bmp.height), dst, p)
        }
        return out
    }

    private fun buildWebCubePalette(): ByteArray {
        val out = ByteArray(256 * 3)
        var idx = 0
        // 6 x 7 x 6 = 252 colors
        for (r in 0 until 6) {
            for (g in 0 until 7) {
                for (b in 0 until 6) {
                    out[idx++] = ((r * 255) / 5).toByte()
                    out[idx++] = ((g * 255) / 6).toByte()
                    out[idx++] = ((b * 255) / 5).toByte()
                }
            }
        }
        // 252..255 grayscale ramp (255 = pure white / transparent slot)
        val grays = intArrayOf(64, 128, 192, 255)
        for (v in grays) {
            out[idx++] = v.toByte()
            out[idx++] = v.toByte()
            out[idx++] = v.toByte()
        }
        return out
    }

    private fun quantizeToCube(r: Int, g: Int, b: Int): Int {
        val ri = (r * 5 + 127) / 255
        val gi = (g * 6 + 127) / 255
        val bi = (b * 5 + 127) / 255
        return (ri * 42 + gi * 6 + bi).coerceIn(0, 251)
    }

    private fun writeShort(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private class LzwEncoder(
        private val minCodeSize: Int,
        private val pixels: ByteArray,
    ) {
        private val clearCode = 1 shl minCodeSize
        private val eofCode = clearCode + 1
        private var codeSize = minCodeSize + 1
        private var nextCode = eofCode + 1
        private val table = HashMap<Int, Int>(4096)

        private var curAccum = 0
        private var curBits = 0
        private val blockBuf = ByteArrayOutputStream(256)

        fun encode(out: OutputStream) {
            out.write(minCodeSize)
            resetTable()
            emitCode(clearCode, out)

            if (pixels.isEmpty()) {
                emitCode(eofCode, out)
                flushBits(out)
                out.write(0)
                return
            }

            var prefix = pixels[0].toInt() and 0xFF
            for (i in 1 until pixels.size) {
                val c = pixels[i].toInt() and 0xFF
                val key = (prefix shl 12) or c
                val existing = table[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    emitCode(prefix, out)
                    if (nextCode < 4096) {
                        table[key] = nextCode++
                        if (nextCode > (1 shl codeSize) && codeSize < 12) {
                            codeSize++
                        }
                    } else {
                        emitCode(clearCode, out)
                        resetTable()
                    }
                    prefix = c
                }
            }
            emitCode(prefix, out)
            emitCode(eofCode, out)
            flushBits(out)
            out.write(0) // zero-length block terminator
        }

        private fun resetTable() {
            table.clear()
            codeSize = minCodeSize + 1
            nextCode = eofCode + 1
        }

        private fun emitCode(code: Int, out: OutputStream) {
            curAccum = curAccum or (code shl curBits)
            curBits += codeSize
            while (curBits >= 8) {
                blockBuf.write(curAccum and 0xFF)
                if (blockBuf.size() >= 254) {
                    flushBlock(out)
                }
                curAccum = curAccum ushr 8
                curBits -= 8
            }
        }

        private fun flushBits(out: OutputStream) {
            if (curBits > 0) {
                blockBuf.write(curAccum and 0xFF)
            }
            flushBlock(out)
        }

        private fun flushBlock(out: OutputStream) {
            val sz = blockBuf.size()
            if (sz > 0) {
                out.write(sz)
                out.write(blockBuf.toByteArray())
                blockBuf.reset()
            }
        }
    }
}
